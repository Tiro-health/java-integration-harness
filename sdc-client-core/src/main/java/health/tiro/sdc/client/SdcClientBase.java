package health.tiro.sdc.client;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import health.tiro.sdc.compat.SdcHttpResponse;
import health.tiro.sdc.compat.SdcServerVersionProbe;
import health.tiro.sdc.compat.SdcVersionCheckOutcome;
import health.tiro.sdc.compat.SdcVersionCheckResult;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;

/**
 * FHIR-version-agnostic client for the stateless SDC server operations. A closed-binding subclass
 * (e.g. the R5 binding) fixes the FHIR resource types and supplies a lenient {@link IParser}; all
 * transport/serialization logic lives here.
 *
 * <p>Thin over a HAPI {@link IParser} plus an Apache {@link CloseableHttpClient}. The operations POST
 * a <b>bare</b> {@code QuestionnaireResponse} body (what the SDC server expects), not a
 * {@code Parameters} envelope, so HAPI's {@code client.operation()/validate()} helpers (which wrap in
 * {@code Parameters}) are deliberately not used.
 *
 * <p>Blocking: HAPI and Apache HttpClient are synchronous.
 *
 * @param <TQuestionnaireResponse> the FHIR QuestionnaireResponse type for the bound version
 * @param <TOperationOutcome> the FHIR OperationOutcome type for the bound version
 * @param <TBundle> the FHIR Bundle type for the bound version
 */
public abstract class SdcClientBase<
        TQuestionnaireResponse extends IBaseResource,
        TOperationOutcome extends IBaseOperationOutcome,
        TBundle extends IBaseBundle>
        implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SdcClientBase.class);

    private static final String FHIR_JSON = "application/fhir+json";

    // Finite timeouts for the internally-owned client. Apache's HttpClients.createDefault() applies
    // none (infinite), which would hang a caller — possibly a host UI thread — against a stalled
    // server. Callers who inject their own client keep full control.
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SOCKET_TIMEOUT_MS = 60_000;

    private final String baseUrl;
    private final FhirContext fhirContext;
    private final Class<TOperationOutcome> outcomeType;
    private final Class<TBundle> bundleType;
    private final CloseableHttpClient httpClient;
    private final boolean ownsClient;

    // The one-time SDC server version check (GH-24). Started by the first operation, never in
    // the constructor: constructing a client must not reach the network.
    private final AtomicBoolean versionCheckStarted = new AtomicBoolean();
    private volatile SdcVersionCheckResult versionCheck;

    /**
     * @param baseUrl the SDC server FHIR base, e.g. {@code https://host/fhir/r5}. Must be a plain
     *                path URL with no query/fragment (they can't survive relative resolution).
     * @param fhirContext the FHIR context for the bound version; a fresh, lenient parser is built
     *               from it per request (thread-safe — parsers aren't, and leniency is applied on the
     *               parser so the possibly-shared/cached context is never mutated).
     * @param httpClient optional pre-configured client (TLS/proxy); when {@code null}, an
     *                   internally-owned client is created and closed with this instance.
     */
    protected SdcClientBase(String baseUrl, FhirContext fhirContext, Class<TOperationOutcome> outcomeType,
                            Class<TBundle> bundleType, CloseableHttpClient httpClient) {
        if (baseUrl == null) throw new IllegalArgumentException("baseUrl is required");
        this.fhirContext = Objects.requireNonNull(fhirContext, "fhirContext");
        this.outcomeType = Objects.requireNonNull(outcomeType, "outcomeType");
        this.bundleType = Objects.requireNonNull(bundleType, "bundleType");

        final URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URI: " + baseUrl, e);
        }
        // A query/fragment can't survive relative-URI resolution, so it would be silently lost —
        // fail fast. FHIR bases are plain path URLs.
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "baseUrl must be a plain path URL with no query or fragment (e.g. https://host/fhir/r5).");
        }
        // Trailing slash so relative operation paths append to the full base.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

        if (httpClient == null) {
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)
                    .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
                    .setSocketTimeout(SOCKET_TIMEOUT_MS)
                    .build();
            this.httpClient = HttpClients.custom().setDefaultRequestConfig(requestConfig).build();
            this.ownsClient = true;
        } else {
            this.httpClient = httpClient;
            this.ownsClient = false;
        }
    }

    /**
     * The outcome of this client's SDC server version check, or {@code null} until the first
     * operation has run it. Exposed because the check's telemetry lands in the
     * <em>customer's</em> logs, not Tiro's — they self-host the server.
     */
    public SdcVersionCheckResult getServerVersionCheck() {
        return versionCheck;
    }

    /**
     * Runs the SDC server version check once per client instance, before the first operation.
     *
     * <p>Reports; never refuses. A server below the floor is an actionable warning ("upgrade
     * the SDC server"), an unreadable version a diagnostic, and both let the operation
     * proceed — see {@link health.tiro.sdc.compat.SdcCompatibility#minimumSdcVersion()} for why
     * enforcement is not shipped yet and what to add when the floor is first raised for a real
     * reason.
     *
     * <p>Only the first caller waits for it, and only for the probe's own 3s deadline; a
     * concurrent second caller proceeds immediately rather than queueing behind an advisory
     * check. The probe rides this client's {@link CloseableHttpClient}, so it travels the same
     * TLS, proxy and timeout path as the operations it guards.
     */
    private void ensureServerVersionChecked() {
        if (versionCheck != null || !versionCheckStarted.compareAndSet(false, true)) return;

        SdcVersionCheckResult result = SdcServerVersionProbe.check(URI.create(baseUrl), this::fetch);
        versionCheck = result;

        if (result.getOutcome() == SdcVersionCheckOutcome.SATISFIED) {
            log.debug("{}", result);
        } else {
            // Both remaining outcomes are logged at WARN, and they read differently on purpose:
            // TOO_OLD is actionable — upgrade the server — while UNKNOWN is a diagnostic about
            // the check itself. Collapsing them would turn the actionable one into noise.
            log.warn("{}", result);
        }
    }

    // The probe's transport, over this client. Deliberately not a second HttpClient: a
    // deployment that needs a proxy or a client certificate to reach the SDC server needs it
    // for the version check too, and a probe that fails for a reason the operations don't
    // share is a diagnostic about nothing.
    private SdcHttpResponse fetch(URI uri, String accept, int timeoutMillis, int maxBytes) throws IOException {
        HttpGet request = new HttpGet(uri);
        request.setHeader("Accept", accept);
        // Overrides whatever the injected client defaults to: the probe's deadline is short
        // because a startup check must not become the reason a form takes long to appear.
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(timeoutMillis)
                .setConnectionRequestTimeout(timeoutMillis)
                .setSocketTimeout(timeoutMillis)
                .build());

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            if (entity == null) return new SdcHttpResponse(status, new byte[0], false);

            try (InputStream stream = entity.getContent()) {
                ByteArrayOutputStream buffered = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = stream.read(chunk)) > 0) {
                    if (buffered.size() + read > maxBytes) {
                        return new SdcHttpResponse(status, buffered.toByteArray(), true);
                    }
                    buffered.write(chunk, 0, read);
                }
                return new SdcHttpResponse(status, buffered.toByteArray(), false);
            }
        }
    }

    /**
     * Validate a QuestionnaireResponse against its referenced Questionnaire
     * ({@code POST QuestionnaireResponse/$validate}). A validation failure is reported as issues in
     * the returned outcome, not as an exception.
     */
    public TOperationOutcome validate(TQuestionnaireResponse questionnaireResponse) {
        return post("QuestionnaireResponse/$validate", questionnaireResponse, outcomeType);
    }

    /**
     * Extract FHIR resources from a QuestionnaireResponse
     * ({@code POST QuestionnaireResponse/$extract}), returning the resulting transaction Bundle.
     */
    public TBundle extract(TQuestionnaireResponse questionnaireResponse) {
        return post("QuestionnaireResponse/$extract", questionnaireResponse, bundleType);
    }

    private <T extends IBaseResource> T post(String relativePath, IBaseResource body, Class<T> returnType) {
        if (body == null) throw new IllegalArgumentException("resource body is required");

        ensureServerVersionChecked();

        // Build a parser per call — FhirContext is thread-safe and parser creation is cheap, parsers aren't.
        // Lenient on the parser instance (not the shared context) so a newer server's unrecognized
        // elements/codes are tolerated without mutating a cached FhirContext other code shares.
        final IParser parser = fhirContext.newJsonParser()
                .setParserErrorHandler(new LenientErrorHandler().setErrorOnInvalidValue(false));
        final String json = parser.encodeResourceToString(body);
        final HttpPost request = new HttpPost(baseUrl + relativePath);
        request.setEntity(new StringEntity(json, ContentType.create(FHIR_JSON, StandardCharsets.UTF_8)));
        request.setHeader("Accept", FHIR_JSON);

        log.debug("SDC POST {}", request.getURI());
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            final int status = response.getStatusLine().getStatusCode();
            log.debug("SDC {} -> {}", relativePath, status);
            final HttpEntity entity = response.getEntity();
            final String responseBody = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);

            if (status < 200 || status >= 300) {
                // Best-effort: surface a server OperationOutcome if the error body carried one.
                // The lenient parser tolerates a newer server's unrecognized elements/codes.
                IBaseOperationOutcome outcome = null;
                try {
                    IBaseResource errorResource = parser.parseResource(responseBody);
                    if (errorResource instanceof IBaseOperationOutcome) {
                        outcome = (IBaseOperationOutcome) errorResource;
                    }
                } catch (RuntimeException ignored) {
                    // non-FHIR error body; leave outcome null
                }
                throw new SdcOperationException(relativePath, status, outcome,
                        "SDC operation '" + relativePath + "' failed with status " + status + ".");
            }

            final IBaseResource parsed;
            try {
                parsed = parser.parseResource(responseBody);
            } catch (RuntimeException ex) {
                throw new SdcOperationException(relativePath, status, null,
                        "SDC operation '" + relativePath + "' returned a body that could not be parsed as FHIR: "
                                + ex.getMessage(), ex);
            }

            if (returnType.isInstance(parsed)) {
                return returnType.cast(parsed);
            }
            // Wrong resource type on a success status. Carry the OperationOutcome (if that's what came
            // back) on the exception so the caller can inspect its diagnostics.
            IBaseOperationOutcome asOutcome = parsed instanceof IBaseOperationOutcome
                    ? (IBaseOperationOutcome) parsed : null;
            throw new SdcOperationException(relativePath, status, asOutcome,
                    "SDC operation '" + relativePath + "' returned '" + parsed.getClass().getSimpleName()
                            + "', expected '" + returnType.getSimpleName() + "'.");
        } catch (IOException ex) {
            throw new SdcOperationException(relativePath, 0, null,
                    "SDC operation '" + relativePath + "' failed: " + ex.getMessage(), ex);
        }
    }

    /** Closes the internally-created HttpClient; a no-op when one was injected. */
    @Override
    public void close() throws IOException {
        if (ownsClient) httpClient.close();
    }
}

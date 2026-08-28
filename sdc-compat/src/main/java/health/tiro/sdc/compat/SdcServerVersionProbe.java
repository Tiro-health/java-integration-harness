package health.tiro.sdc.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Reads the version of a live SDC server and evaluates it against
 * {@link SdcCompatibility#minimumSdcVersion()}.
 *
 * <p>One source: {@code GET {sdcBase}/metadata} &rarr;
 * {@code CapabilityStatement.software.version}, accepted only from a document that identifies
 * itself as the SDC server.
 *
 * <p>The request URI is built by <em>appending</em> to the configured SDC base, so the request
 * reaches the same host the forms and the operations talk to, and a gateway path prefix
 * survives. That is necessary but <b>not</b> sufficient for attribution, and it is worth being
 * precise about why: on a server predating this route, {@code {base}/metadata} has no local
 * handler and falls into the SDC server's data tunnel, which proxies it to the configured data
 * endpoint — so a self-hosted deployment with {@code DEFAULT_DATA_ENDPOINT} set answers with
 * the <em>hospital's own</em> CapabilityStatement. Base-relativity gets the request to the
 * right host; only the body can say who composed it. Hence the {@code software.name}
 * requirement below, which is what actually makes the read attributable.
 *
 * <p>Deliberately a plain GET plus a two-field JSON read, rather than HAPI's
 * {@code client.capabilities()} or a full {@code CapabilityStatement} parse: this runs on the
 * path to showing a clinician a form, two strings are all that is needed, and a single-field
 * read cannot trip over an element a newer server emits. It also keeps this module free of
 * HAPI, so the form filler can depend on it without dragging a FHIR structures jar along.
 *
 * <p>Every failure is a result, never an exception: an unreachable server must not become a
 * new way for a form launch to die.
 */
public final class SdcServerVersionProbe {

    /**
     * The probe's connect and read timeout. Bounded on purpose: a startup check must not become
     * the reason a form takes long to appear.
     *
     * <p>Note this is per socket operation, not a total deadline — a peer that keeps sending a
     * trickle of bytes can hold the read loop open past it, bounded then only by
     * {@code MAX_RESPONSE_BYTES}. Left as-is deliberately: an ordinary unreachable or stalled
     * server hits the timeout on its first read, and the alternative is a wall-clock budget
     * threaded through both fetchers to defend against a peer that has to be actively hostile
     * to reach. Worth revisiting if the check ever gains the power to refuse.</p>
     */
    public static final int TIMEOUT_MILLISECONDS = 3000;

    /**
     * Hard cap on how much of a response body is read. A safety valve against an unbounded or
     * hostile stream, not an expected limit: the real document is ~530 bytes, so this is
     * already two orders of magnitude of headroom.
     */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    /**
     * What the SDC server reports as {@code CapabilityStatement.software.name}. This is the
     * attribution signal: the version is used only when it matches. It is <b>required</b>, not
     * merely checked when present — {@code software.name} is {@code 1..1} whenever
     * {@code software} is present in R4 and R5, so a conformant server cannot drop it and
     * requiring it adds no way for a legitimate server-side change to disarm the check. A
     * document that omits it is by definition non-conformant, which is exactly the class (a
     * tunnelled response, a hand-written server, a proxy) that must not be trusted.
     *
     * <p>Compared case-insensitively and trimmed on purpose. The literal has a lowercase
     * {@code h} in "health"; a cosmetic capitalization change on the server would otherwise
     * disarm this check in every already-shipped binary, and no reading of the string's intent
     * depends on its case.
     */
    private static final String SDC_SERVER_SOFTWARE_NAME = "Tiro.health SDC Server";

    private static final String FHIR_JSON = "application/fhir+json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SdcServerVersionProbe() {}

    /**
     * Probes {@code sdcBaseAddress} over the JDK's HTTP stack, unauthenticated. A server that
     * requires a credential on {@code /metadata} answers 401/403, which is
     * {@link SdcVersionCheckOutcome#UNKNOWN} — i.e. fails open.
     *
     * @param sdcBaseAddress the SDC server FHIR base, e.g. {@code https://host/fhir/r5}
     */
    public static SdcVersionCheckResult check(URI sdcBaseAddress) {
        return check(sdcBaseAddress, defaultFetcher());
    }

    /**
     * Probes {@code sdcBaseAddress} using the supplied fetcher, so the probe can travel the
     * same TLS/proxy/auth path as the operations it guards.
     *
     * @throws IllegalArgumentException if {@code sdcBaseAddress} is not an absolute URI (a
     *                                  caller bug, not a server condition)
     */
    public static SdcVersionCheckResult check(URI sdcBaseAddress, SdcHttpFetcher fetcher) {
        if (sdcBaseAddress == null) throw new IllegalArgumentException("sdcBaseAddress is required");
        if (!sdcBaseAddress.isAbsolute()) {
            throw new IllegalArgumentException("sdcBaseAddress must be an absolute URI: " + sdcBaseAddress);
        }
        if (fetcher == null) throw new IllegalArgumentException("fetcher is required");

        // A trailing slash on the PATH is required so "metadata" resolves against the full base
        // (".../fhir/r5/" + "metadata") instead of replacing the last segment.
        String path = sdcBaseAddress.getRawPath() == null ? "" : sdcBaseAddress.getRawPath();
        URI base = path.endsWith("/") ? sdcBaseAddress : sdcBaseAddress.resolve(path + "/");
        URI requestUri = base.resolve("metadata");

        SdcHttpResponse response;
        try {
            response = fetcher.get(requestUri, FHIR_JSON, TIMEOUT_MILLISECONDS, MAX_RESPONSE_BYTES);
        } catch (IOException e) {
            // Every transport failure (DNS, TLS, refused connection, proxy, timeout). Fails
            // open: the version is unknown, which must never brick a deployment.
            return SdcVersionCheckResult.unavailable("GET " + requestUri + " → "
                    + e.getClass().getSimpleName() + ": " + SdcVersionCheckResult.clamp(e.getMessage()));
        } catch (RuntimeException e) {
            // A defect in a supplied fetcher must not be able to break a form launch either.
            return SdcVersionCheckResult.unavailable("GET " + requestUri + " → the version check itself "
                    + "failed: " + e.getClass().getSimpleName() + ": "
                    + SdcVersionCheckResult.clamp(e.getMessage()));
        }

        if (response == null) {
            return SdcVersionCheckResult.unavailable(
                    "GET " + requestUri + " → the fetcher returned no response.");
        }
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            return SdcVersionCheckResult.unavailable("GET " + requestUri + " → " + status + ".");
        }
        if (response.isTruncated()) {
            return SdcVersionCheckResult.unavailable(
                    "GET " + requestUri + " → body exceeded " + MAX_RESPONSE_BYTES + " bytes.");
        }
        if (response.getBody().length == 0) {
            return SdcVersionCheckResult.unavailable(
                    "GET " + requestUri + " → " + status + " with an empty body.");
        }

        JsonNode software = readSoftware(response.getBody());
        String version = readString(software, "version");
        if (version == null) {
            return SdcVersionCheckResult.unavailable(
                    "GET " + requestUri + " → " + status + " without a string software.version.");
        }

        // Attribution guard; see SDC_SERVER_SOFTWARE_NAME. Reported as "unknown" (fail open),
        // never as "too old" — a document we cannot attribute must not condemn a server.
        String name = readString(software, "name");
        if (name == null || !SDC_SERVER_SOFTWARE_NAME.equalsIgnoreCase(name.trim())) {
            return SdcVersionCheckResult.unavailable("GET " + requestUri
                    + " → a CapabilityStatement whose software.name is "
                    + (name == null ? "absent" : "'" + SdcVersionCheckResult.clamp(name) + "'")
                    + ", not '" + SDC_SERVER_SOFTWARE_NAME + "'. "
                    + "Its version is not the SDC server's and was not used.");
        }

        return SdcVersionCheckResult.fromReportedVersion(version);
    }

    /**
     * The JDK-backed fetcher used when no other is supplied. One connection per probe, no
     * pooling: this runs once per client or viewer, so there is nothing to pool.
     */
    public static SdcHttpFetcher defaultFetcher() {
        return (uri, accept, timeoutMillis, maxBytes) -> {
            URL url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", accept);
                connection.setConnectTimeout(timeoutMillis);
                connection.setReadTimeout(timeoutMillis);
                connection.setInstanceFollowRedirects(true);

                int status = connection.getResponseCode();
                // A non-2xx body arrives on the error stream, and HttpURLConnection will not
                // give it to getInputStream(). We don't parse it, but it has to be drained or
                // read as empty rather than throwing.
                try (InputStream stream = status >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream()) {
                    if (stream == null) return new SdcHttpResponse(status, new byte[0], false);

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
            } finally {
                connection.disconnect();
            }
        };
    }

    // The CapabilityStatement's `software` object, or null for anything unreadable.
    private static JsonNode readSoftware(byte[] utf8Json) {
        try {
            // Jackson rejects a leading UTF-8 BOM, which a hand-written server or a proxy can
            // prepend; skip it rather than reporting a malformed body.
            int start = utf8Json.length >= 3
                    && (utf8Json[0] & 0xFF) == 0xEF
                    && (utf8Json[1] & 0xFF) == 0xBB
                    && (utf8Json[2] & 0xFF) == 0xBF ? 3 : 0;
            JsonNode root = MAPPER.readTree(new java.io.ByteArrayInputStream(
                    utf8Json, start, utf8Json.length - start));
            if (root == null || !root.isObject()) return null;
            JsonNode software = root.get("software");
            return software != null && software.isObject() ? software : null;
        } catch (IOException malformed) {
            return null;
        }
    }

    private static String readString(JsonNode parent, String field) {
        if (parent == null) return null;
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) return null;
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }
}

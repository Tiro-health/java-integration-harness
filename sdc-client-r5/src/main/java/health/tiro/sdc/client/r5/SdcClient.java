package health.tiro.sdc.client.r5;

import org.apache.http.impl.client.CloseableHttpClient;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.QuestionnaireResponse;

import ca.uhn.fhir.context.FhirContext;
import health.tiro.sdc.client.SdcClientBase;

/**
 * FHIR R5 SDC client. Wraps the stateless SDC server operations
 * {@code QuestionnaireResponse/$validate} and {@code QuestionnaireResponse/$extract}.
 *
 * <p>These operations are R5-only on the SDC server. Construct with the server's FHIR base
 * (e.g. {@code https://host/fhir/r5}); optionally inject a pre-configured
 * {@link CloseableHttpClient} for custom TLS/proxy.
 *
 * <p>There is intentionally no default base URL: it is the same concept as the form viewer's
 * {@code FormFillerConfig.sdcEndpointAddress}, so a host that embeds the form and also calls this
 * client should configure the SDC address once and pass that single value to both, rather than
 * risk the two pointing at different servers.
 */
public final class SdcClient extends SdcClientBase<QuestionnaireResponse, OperationOutcome, Bundle> {

    // forR5Cached() is a shared, thread-safe context; the base builds a fresh lenient parser from it
    // per request and never mutates it (so other code using forR5Cached() is unaffected).
    private static final FhirContext CTX = FhirContext.forR5Cached();

    public SdcClient(String baseUrl) {
        this(baseUrl, null);
    }

    public SdcClient(String baseUrl, CloseableHttpClient httpClient) {
        super(baseUrl, CTX, OperationOutcome.class, Bundle.class, httpClient);
    }
}

package health.tiro.sdc.client.r5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.QuestionnaireResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.LenientErrorHandler;
import health.tiro.sdc.client.SdcOperationException;
import health.tiro.sdc.compat.SdcCompatibility;
import health.tiro.sdc.compat.SdcVersionCheckOutcome;

class SdcClientTest {

    private static final FhirContext CTX = FhirContext.forR5Cached();

    private HttpServer server;
    private RecordingHandler handler;
    private SdcClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handler = new RecordingHandler();
        server.createContext("/", handler);
        server.start();
        int port = server.getAddress().getPort();
        client = new SdcClient("http://127.0.0.1:" + port + "/fhir/r5");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    private static QuestionnaireResponse sampleResponse() {
        QuestionnaireResponse qr = new QuestionnaireResponse();
        qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
        qr.setQuestionnaire("http://example.org/Questionnaire/intake|1.0.0");
        return qr;
    }

    @Test
    void validate_postsBareQuestionnaireResponse_andReturnsOutcome() {
        handler.respond(200, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\",\"code\":\"informational\",\"diagnostics\":\"valid\"}]}");

        OperationOutcome outcome = client.validate(sampleResponse());

        assertNotNull(outcome);
        assertEquals(OperationOutcome.IssueSeverity.INFORMATION, outcome.getIssueFirstRep().getSeverity());

        assertEquals("POST", handler.method);
        assertEquals("/fhir/r5/QuestionnaireResponse/$validate", handler.path);
        assertTrue(handler.contentType.startsWith("application/fhir+json"));

        // The body is a BARE QuestionnaireResponse — not a Parameters envelope (guards the design decision).
        IBaseResource sentBody = CTX.newJsonParser().setParserErrorHandler(new LenientErrorHandler())
                .parseResource(handler.requestBody);
        assertTrue(sentBody instanceof QuestionnaireResponse, "expected a bare QuestionnaireResponse body");
    }

    @Test
    void validate_withErrorIssues_returnsOutcomeWithoutThrowing() {
        handler.respond(200, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"required\",\"diagnostics\":\"missing answer\"}]}");

        OperationOutcome outcome = client.validate(sampleResponse());

        // A validation failure is data, not an exception.
        assertEquals(OperationOutcome.IssueSeverity.ERROR, outcome.getIssueFirstRep().getSeverity());
    }

    @Test
    void extract_postsToExtract_andReturnsBundle() {
        handler.respond(200, "{\"resourceType\":\"Bundle\",\"type\":\"transaction\"}");

        Bundle bundle = client.extract(sampleResponse());

        assertNotNull(bundle);
        assertEquals(Bundle.BundleType.TRANSACTION, bundle.getType());
        assertEquals("/fhir/r5/QuestionnaireResponse/$extract", handler.path);
    }

    @Test
    void nonSuccessStatus_throwsSdcOperationException_carryingOutcome() {
        handler.respond(400, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"fatal\",\"code\":\"processing\",\"diagnostics\":\"boom\"}]}");

        SdcOperationException ex = assertThrows(SdcOperationException.class, () -> client.validate(sampleResponse()));

        assertEquals(400, ex.getStatusCode());
        assertNotNull(ex.getOutcome());
        assertEquals(OperationOutcome.IssueSeverity.FATAL,
                ((OperationOutcome) ex.getOutcome()).getIssueFirstRep().getSeverity());
    }

    @Test
    void recoverableBody_isParsed_notThrown() {
        // A 200 carrying an element this HAPI version doesn't recognize (as a newer server would emit).
        // The lenient parser should tolerate it rather than fail.
        handler.respond(200, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\",\"code\":\"informational\"}],\"madeUpFutureElement\":\"x\"}");

        OperationOutcome outcome = client.validate(sampleResponse());

        assertEquals(OperationOutcome.IssueSeverity.INFORMATION, outcome.getIssueFirstRep().getSeverity());
    }

    // ---- SDC server version check (GH-24) ----

    @Test
    void versionCheck_readsTheServerVersionOnce_andExposesIt() {
        handler.respondToMetadata(200, capabilityStatement("Tiro.health SDC Server", "v0.9.39"));
        handler.respond(200, "{\"resourceType\":\"Bundle\",\"type\":\"transaction\"}");

        client.extract(sampleResponse());
        client.extract(sampleResponse());

        assertEquals(SdcVersionCheckOutcome.SATISFIED, client.getServerVersionCheck().getOutcome());
        assertEquals("v0.9.39", client.getServerVersionCheck().getReportedVersion());
        assertEquals(SdcCompatibility.minimumSdcVersion(), client.getServerVersionCheck().getMinimumVersion());
        // Once per client, not per operation: this is a startup check, not a preflight.
        assertEquals(1, handler.metadataRequests());
    }

    @Test
    void versionCheck_reportsATooOldServer_butStillRunsTheOperation() {
        handler.respondToMetadata(200, capabilityStatement("Tiro.health SDC Server", "v0.9.38"));
        handler.respond(200, "{\"resourceType\":\"Bundle\",\"type\":\"transaction\"}");

        // Reports; never refuses. The operation must still happen.
        assertNotNull(client.extract(sampleResponse()));
        assertEquals(SdcVersionCheckOutcome.TOO_OLD, client.getServerVersionCheck().getOutcome());
    }

    /**
     * A server that cannot answer the probe — one predating the route, or behind something that
     * 404s it — must not become a client that cannot do its job.
     */
    @Test
    void versionCheck_failsOpen_whenTheServerCannotAnswer() {
        handler.respondToMetadata(404, "");
        handler.respond(200, "{\"resourceType\":\"Bundle\",\"type\":\"transaction\"}");

        assertNotNull(client.extract(sampleResponse()));
        assertEquals(SdcVersionCheckOutcome.UNKNOWN, client.getServerVersionCheck().getOutcome());
        assertTrue(client.getServerVersionCheck().getDetail().contains("404"),
                client.getServerVersionCheck().getDetail());
    }

    @Test
    void versionCheck_isNotRunUntilTheFirstOperation() {
        // Constructing a client must not reach the network.
        assertNull(client.getServerVersionCheck());
        assertEquals(0, handler.metadataRequests());
    }

    private static String capabilityStatement(String softwareName, String version) {
        return "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\",\"software\":{"
                + "\"name\":\"" + softwareName + "\",\"version\":\"" + version + "\"}}";
    }

    @Test
    void constructor_rejectsBaseUrlWithQuery() {
        assertThrows(IllegalArgumentException.class,
                () -> new SdcClient("https://sdc.test.local/fhir/r5?key=abc"));
    }

    /** Captures the inbound request and serves a canned FHIR JSON response. */
    private static final class RecordingHandler implements HttpHandler {
        private int responseStatus = 200;
        private String responseBody = "";
        // The version probe's GET {base}/metadata is answered separately, so an operation test
        // never has to care that it happens and a probe test never has to fake an operation.
        private int metadataStatus = 404;
        private String metadataBody = "";
        private final List<String> metadataPaths = new CopyOnWriteArrayList<>();
        String method;
        String path;
        String contentType;
        String requestBody;

        void respond(int status, String fhirJson) {
            this.responseStatus = status;
            this.responseBody = fhirJson;
        }

        void respondToMetadata(int status, String fhirJson) {
            this.metadataStatus = status;
            this.metadataBody = fhirJson;
        }

        int metadataRequests() {
            return metadataPaths.size();
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().toString();
            if (requestPath.endsWith("/metadata")) {
                metadataPaths.add(requestPath);
                byte[] metadata = metadataBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
                exchange.sendResponseHeaders(metadataStatus, metadata.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(metadata);
                }
                return;
            }

            method = exchange.getRequestMethod();
            path = requestPath;
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestBody = readAll(exchange.getRequestBody());

            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            exchange.sendResponseHeaders(responseStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        }

        private static String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

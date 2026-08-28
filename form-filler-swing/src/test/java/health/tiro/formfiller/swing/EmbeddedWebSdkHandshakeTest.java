package health.tiro.formfiller.swing;

import health.tiro.swm.r4.SmartMessageHandler;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link FormFiller} does with the {@code client} report the bridge puts in the handshake
 * (GH-24): the page must be running the bundle the harness embeds, or the session is refused.
 */
class EmbeddedWebSdkHandshakeTest {

    @Test
    void acceptsTheEmbeddedBundle() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"name\":\"tiro-web-sdk\",\"version\":\"" + WebSdkAssets.getVersion()
                    + "\",\"source\":\"embedded\"");

            f.filler.waitForHandshake().get(5, TimeUnit.SECONDS);
            assertEquals(WebSdkAssets.getVersion(), f.filler.getPageWebSdkVersion());
        }
    }

    @Test
    void refusesAPageThatLoadsItsOwnSdk() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"name\":\"tiro-web-sdk\",\"version\":\"0.1.0\",\"source\":\"collision\"");

            WebSdkLoadException failure = expectRefusal(f);
            assertEquals("collision", failure.getReason());
            assertTrue(failure.getMessage().contains("<script>"), failure.getMessage());
            // The version the page substituted is worth keeping even though it did not
            // decide anything: it is what tells support which build a clinician was on.
            assertEquals("0.1.0", f.filler.getPageWebSdkVersion());
        }
    }

    @Test
    void refusesAPageWhereTheEmbeddedBundleCouldNotLoad() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"name\":\"tiro-web-sdk\",\"version\":null,\"source\":\"error\"");

            WebSdkLoadException failure = expectRefusal(f);
            assertEquals("error", failure.getReason());
            assertNull(f.filler.getPageWebSdkVersion());
        }
    }

    /**
     * A handshake with no {@code client} at all — not this bridge, so there is no evidence of
     * a bad pairing, only an absence of evidence. Reported, not refused.
     */
    @Test
    void allowsAHandshakeThatCarriesNoReport() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshakeWithPayload("{}");

            f.filler.waitForHandshake().get(5, TimeUnit.SECONDS);
            assertNull(f.filler.getPageWebSdkVersion());
        }
    }

    /**
     * A version that disagrees with the embedded one does not refuse the session: the element's
     * self-report is not what makes a pairing safe, and refusing on it would only add a way to
     * break a working session if a future SDK dropped the field.
     */
    @Test
    void allowsAVersionThatDisagreesWithTheEmbeddedOne() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"name\":\"tiro-web-sdk\",\"version\":\"9.9.9\",\"source\":\"embedded\"");

            f.filler.waitForHandshake().get(5, TimeUnit.SECONDS);
            assertEquals("9.9.9", f.filler.getPageWebSdkVersion());
        }
    }

    private static WebSdkLoadException expectRefusal(Fixture f) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> f.filler.waitForHandshake().get(5, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof WebSdkLoadException,
                "expected a WebSdkLoadException, got " + thrown.getCause());
        return (WebSdkLoadException) thrown.getCause();
    }

    /** A FormFiller over a browser that renders nothing and records what it was told. */
    private static final class Fixture implements AutoCloseable {
        final List<String> sentToPage = new ArrayList<>();
        final List<String> handshakeAcks = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger handshakeCallbacks =
                new java.util.concurrent.atomic.AtomicInteger();
        final List<WebSdkLoadException> refusals =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        final FormFiller<SmartMessageHandler> filler;
        private Function<String, String> incoming;

        Fixture() {
            FormFillerConfig config = FormFillerConfig.builder()
                    .sdcEndpointAddress("http://localhost:8000/fhir/r5")
                    .handshakeTimeoutSeconds(2)
                    .build();
            // Overridden so these tests never reach the network: the SDC version check is
            // wired in FormFiller's constructor and is not what they are about.
            filler = new FormFiller<SmartMessageHandler>(config, new FakeBrowser(), new SmartMessageHandler()) {
                @Override
                protected health.tiro.sdc.compat.SdcVersionCheckResult checkSdcServerVersion(java.net.URI base) {
                    return health.tiro.sdc.compat.SdcVersionCheckResult.unavailable("not probed in this test");
                }
            };
            filler.addFormFillerListener(new FormFillerListener() {
                @Override public void onHandshakeReceived() { handshakeCallbacks.incrementAndGet(); }
                @Override public void onWebSdkLoadFailed(WebSdkLoadException e) { refusals.add(e); }
            });
        }

        void handshake(String clientFields) {
            handshakeWithPayload("{\"client\":{" + clientFields + "}}");
        }

        void handshakeWithPayload(String payloadJson) {
            String ack = incoming.apply("{\"messageId\":\"" + UUID.randomUUID() + "\","
                    + "\"messagingHandle\":\"smart-web-messaging\","
                    + "\"messageType\":\"status.handshake\","
                    + "\"payload\":" + payloadJson + "}");
            handshakeAcks.add(ack);
        }

        /** Listener callbacks are dispatched via invokeLater; drain the EDT before asserting. */
        void settle() throws Exception {
            javax.swing.SwingUtilities.invokeAndWait(() -> {});
        }

        @Override
        public void close() {
            filler.close();
        }

        private final class FakeBrowser implements EmbeddedBrowser {
            @Override public Component createComponent() { return new JPanel(); }
            @Override public void loadUrl(String url) {}
            @Override public void executeJavaScript(String script) {}
            @Override public void sendMessage(String json) { sentToPage.add(json); }
            @Override public void setIncomingMessageHandler(Function<String, String> handler) { incoming = handler; }
            @Override public void addPageLoadListener(Runnable callback) {}
            @Override public void close() {}
        }
    }

    /**
     * A refused session must not merely fail {@code waitForHandshake()}: outbound messages are
     * queued on the same future, so they have to fail too rather than sit forever.
     */
    @Test
    void refusalAlsoFailsQueuedOutboundMessages() throws Exception {
        try (Fixture f = new Fixture()) {
            java.util.concurrent.CompletableFuture<String> queued =
                    f.filler.getMessageHandler().sendFormRequestSubmitAsync(null, null);
            f.handshake("\"source\":\"error\"");

            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> queued.get(5, TimeUnit.SECONDS));
            assertTrue(thrown.getCause() instanceof WebSdkLoadException,
                    "expected a WebSdkLoadException, got " + thrown.getCause());
            assertTrue(f.sentToPage.isEmpty(), "nothing should reach a refused page");
        }
    }

    /** And a session nobody refuses still delivers those queued messages. */
    @Test
    void acceptedSessionFlushesQueuedOutboundMessages() throws Exception {
        try (Fixture f = new Fixture()) {
            f.filler.getMessageHandler().sendFormRequestSubmitAsync(null, null);
            f.handshake("\"source\":\"embedded\",\"version\":\"" + WebSdkAssets.getVersion() + "\"");

            f.filler.waitForHandshake().get(5, TimeUnit.SECONDS);
            assertEquals(1, f.sentToPage.size());
            assertTrue(f.sentToPage.get(0).contains("ui.form.requestSubmit"), f.sentToPage.get(0));
        }
    }

    /**
     * The page has to be told. Nothing else in the harness can reach it: without an error ack
     * the bridge logs "Connected" over a form that will never render, which is what a clinician
     * would be looking at.
     */
    @Test
    void refusalIsAnsweredWithAnErrorAck() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"source\":\"collision\"");

            String ack = f.handshakeAcks.get(0);
            assertTrue(ack != null && ack.contains("\"$type\":\"error\""),
                    "the refused handshake should be acked as an error, got: " + ack);
        }
    }

    @Test
    void refusalNotifiesTheListenerAndSuppressesTheHandshakeCallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"source\":\"error\"");
            f.settle();

            assertEquals(1, f.refusals.size());
            assertEquals("error", f.refusals.get(0).getReason());
            assertEquals(0, f.handshakeCallbacks.get(),
                    "a refused session must not also report a successful handshake");
        }
    }

    /**
     * A refused session is terminal. A page that reloads into a working state must not be able
     * to announce a handshake the future has already failed — the host would be told the
     * session is up while every message it sends still fails forever.
     */
    @Test
    void aRefusedSessionStaysRefusedWhenTheNextHandshakeIsClean() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"source\":\"collision\",\"version\":\"0.1.0\"");
            f.handshake("\"source\":\"embedded\",\"version\":\"" + WebSdkAssets.getVersion() + "\"");
            f.settle();

            assertEquals(0, f.handshakeCallbacks.get(), "the recovery must not be announced");
            assertEquals(1, f.refusals.size(), "and the refusal must not be announced twice");
            assertEquals("collision", expectRefusal(f).getReason());
            assertTrue(f.handshakeAcks.get(1).contains("\"$type\":\"error\""),
                    "the second handshake should be refused too, not acked as success");
        }
    }

    /**
     * The other direction: a page that handshakes cleanly and then navigates somewhere that
     * cannot run the bundle. The refusal is real or it is not — announcing it while messages
     * keep flowing would be worse than not checking at all.
     */
    @Test
    void aCleanSessionIsNotRetroactivelyRefusedButStaysConsistent() throws Exception {
        try (Fixture f = new Fixture()) {
            f.handshake("\"source\":\"embedded\",\"version\":\"" + WebSdkAssets.getVersion() + "\"");
            f.filler.waitForHandshake().get(5, TimeUnit.SECONDS);
            f.handshake("\"source\":\"error\"");
            f.settle();

            // The future settled successfully and cannot un-settle, so the listener must not
            // claim a refusal the two other channels will contradict.
            assertEquals(1, f.handshakeCallbacks.get());
            assertTrue(f.refusals.isEmpty(),
                    "a refusal that cannot be enforced must not be announced");
        }
    }

    /** A retried handshake must not multiply the callbacks or the telemetry. */
    @Test
    void repeatedCleanHandshakesReportOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            String client = "\"source\":\"embedded\",\"version\":\"" + WebSdkAssets.getVersion() + "\"";
            f.handshake(client);
            f.handshake(client);
            f.handshake(client);
            f.settle();

            assertEquals(1, f.handshakeCallbacks.get());
        }
    }

    /** The handshake timeout still applies when the page never answers at all. */
    @Test
    void timesOutWhenNoHandshakeArrives() throws Exception {
        try (Fixture f = new Fixture()) {
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> f.filler.waitForHandshake().get(10, TimeUnit.SECONDS));
            assertTrue(thrown.getCause() instanceof TimeoutException,
                    "expected a TimeoutException, got " + thrown.getCause());
        }
    }
}

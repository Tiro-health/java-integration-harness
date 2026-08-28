package health.tiro.formfiller.swing;

import health.tiro.sdc.compat.SdcVersionCheckOutcome;
import health.tiro.sdc.compat.SdcVersionCheckResult;
import health.tiro.swm.r4.SmartMessageHandler;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Component;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link FormFiller} runs the SDC server version check (GH-24). What the probe itself
 * decides is covered in {@code sdc-compat}; this is about the wiring — that it happens off the
 * constructing thread, against the configured base, and that nothing it can return stops a
 * launch.
 */
class SdcServerVersionCheckWiringTest {

    @Test
    void probesTheConfiguredSdcBaseAndExposesTheVerdict() throws Exception {
        Probe probe = new Probe(SdcVersionCheckResult.fromReportedVersion("v0.9.39"));

        try (FormFiller<SmartMessageHandler> filler = viewer("http://sdc.example/fhir/r5", probe)) {
            assertTrue(probe.done.await(5, TimeUnit.SECONDS), "the probe should run");
            assertEquals(URI.create("http://sdc.example/fhir/r5"), probe.probed.get());
            assertEquals(SdcVersionCheckOutcome.SATISFIED, awaitVerdict(filler).getOutcome());
        }
    }

    /** A server below the floor is reported, and the viewer carries on. */
    @Test
    void aTooOldServerIsReportedNotRefused() throws Exception {
        Probe probe = new Probe(SdcVersionCheckResult.fromReportedVersion("v0.9.38"));

        try (FormFiller<SmartMessageHandler> filler = viewer("http://sdc.example/fhir/r5", probe)) {
            assertEquals(SdcVersionCheckOutcome.TOO_OLD, awaitVerdict(filler).getOutcome());
            // Nothing about the session is refused: the handshake is still the only gate.
            assertNull(filler.getPageWebSdkVersion());
        }
    }

    /**
     * The probe's contract is that a failure is a result, not an exception — but a broken
     * override must not be able to take the viewer's constructor thread down with it either.
     */
    @Test
    void aThrowingProbeFailsOpen() throws Exception {
        Probe probe = new Probe(null);

        try (FormFiller<SmartMessageHandler> filler = viewer("http://sdc.example/fhir/r5", probe)) {
            SdcVersionCheckResult verdict = awaitVerdict(filler);
            assertEquals(SdcVersionCheckOutcome.UNKNOWN, verdict.getOutcome());
            assertTrue(verdict.getDetail().contains("check itself failed"), verdict.getDetail());
        }
    }

    /** No SDC address configured — a custom page owns its own — so there is nothing to probe. */
    @Test
    void skipsTheCheckWhenNoSdcAddressIsConfigured() throws Exception {
        Probe probe = new Probe(SdcVersionCheckResult.fromReportedVersion("v0.9.39"));

        try (FormFiller<SmartMessageHandler> filler = viewer(null, probe)) {
            assertNull(filler.getSdcServerVersionCheck());
            assertNull(probe.probed.get());
        }
    }

    /** A malformed address is the page's problem to report, with a better message than this. */
    @Test
    void skipsTheCheckWhenTheAddressIsNotAbsolute() throws Exception {
        Probe probe = new Probe(SdcVersionCheckResult.fromReportedVersion("v0.9.39"));

        try (FormFiller<SmartMessageHandler> filler = viewer("/fhir/r5", probe)) {
            assertNull(filler.getSdcServerVersionCheck());
            assertNull(probe.probed.get());
        }
    }

    // ---- helpers ----

    private static SdcVersionCheckResult awaitVerdict(FormFiller<?> filler) throws InterruptedException {
        for (int i = 0; i < 100 && filler.getSdcServerVersionCheck() == null; i++) {
            Thread.sleep(20);
        }
        SdcVersionCheckResult verdict = filler.getSdcServerVersionCheck();
        assertTrue(verdict != null, "the verdict should be published");
        return verdict;
    }

    /** Records the base it was asked about; a null verdict makes it throw instead. */
    private static final class Probe {
        final AtomicReference<URI> probed = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        private final SdcVersionCheckResult verdict;

        Probe(SdcVersionCheckResult verdict) {
            this.verdict = verdict;
        }

        SdcVersionCheckResult apply(URI base) {
            probed.set(base);
            done.countDown();
            if (verdict == null) throw new IllegalStateException("probe blew up");
            return verdict;
        }
    }

    private static FormFiller<SmartMessageHandler> viewer(String sdcEndpointAddress, Probe probe) {
        FormFillerConfig.Builder builder = FormFillerConfig.builder().handshakeTimeoutSeconds(2);
        if (sdcEndpointAddress == null) {
            builder.targetUrl("file:///dev/null");
        } else {
            builder.sdcEndpointAddress(sdcEndpointAddress);
        }
        return new FormFiller<SmartMessageHandler>(builder.build(), new SilentBrowser(), new SmartMessageHandler()) {
            @Override
            protected SdcVersionCheckResult checkSdcServerVersion(URI sdcBaseAddress) {
                return probe.apply(sdcBaseAddress);
            }
        };
    }

    private static final class SilentBrowser implements EmbeddedBrowser {
        @Override public Component createComponent() { return new JPanel(); }
        @Override public void loadUrl(String url) {}
        @Override public void executeJavaScript(String script) {}
        @Override public void setIncomingMessageHandler(Function<String, String> handler) {}
        @Override public void addPageLoadListener(Runnable callback) {}
        @Override public void close() {}
    }
}

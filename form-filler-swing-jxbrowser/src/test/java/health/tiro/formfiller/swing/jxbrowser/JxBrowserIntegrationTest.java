package health.tiro.formfiller.swing.jxbrowser;

import com.teamdev.jxbrowser.engine.RenderingMode;
import health.tiro.formfiller.swing.EmbeddedBrowser;
import health.tiro.formfiller.swing.FormFiller;
import health.tiro.formfiller.swing.FormFillerConfig;
import health.tiro.formfiller.swing.WebSdkAssets;
import health.tiro.swm.r4.SmartMessageHandler;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JxBrowserIntegrationTest {

    /**
     * The whole GH-24 chain in a real browser: the bridge injects the embedded bundle from the
     * file:// URL the preamble gave it, the element registers, and the handshake reports that
     * element's build-time version. The page is empty and carries no SDK reference of its own,
     * which is the point — a handshake here proves the harness supplied the SDK.
     */
    @Test
    void handshakeCompletesWithInjectedBridge() throws Exception {
        String licenseKey = System.getProperty("jxbrowser.license.key");
        assumeTrue(licenseKey != null && !licenseKey.isEmpty(),
                "JxBrowser license key not provided (-Djxbrowser.license.key=...)");

        URL testPage = getClass().getClassLoader().getResource("test-page.html");
        assertNotNull(testPage, "test-page.html not found on classpath");

        EmbeddedBrowser browser = new JxBrowserAdapter(
                JxBrowserConfig.builder()
                        .licenseKey(licenseKey)
                        .renderingMode(RenderingMode.OFF_SCREEN)
                        .build()
        );
        SmartMessageHandler handler = new SmartMessageHandler();
        FormFillerConfig config = FormFillerConfig.builder()
                .targetUrl(testPage.toExternalForm())
                .build();
        FormFiller filler = new FormFiller(config, browser, handler);

        try {
            filler.waitForHandshake().get(15, TimeUnit.SECONDS);
            assertEquals(WebSdkAssets.getVersion(), filler.getPageWebSdkVersion(),
                    "the page should be running the bundle the harness embedded and served");
        } finally {
            filler.close();
        }
    }
}

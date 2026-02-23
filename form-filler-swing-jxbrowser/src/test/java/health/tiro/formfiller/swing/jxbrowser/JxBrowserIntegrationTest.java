package health.tiro.formfiller.swing.jxbrowser;

import com.teamdev.jxbrowser.engine.RenderingMode;
import health.tiro.formfiller.swing.EmbeddedBrowser;
import health.tiro.formfiller.swing.FormFiller;
import health.tiro.formfiller.swing.FormFillerConfig;
import health.tiro.swm.r4.SmartMessageHandler;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JxBrowserIntegrationTest {

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
        } finally {
            filler.dispose();
        }
    }
}

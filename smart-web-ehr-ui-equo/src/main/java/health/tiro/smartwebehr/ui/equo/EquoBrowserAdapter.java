package health.tiro.smartwebehr.ui.equo;

import com.equo.chromium.ChromiumBrowser;
import health.tiro.smartwebehr.ui.EmbeddedBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * {@link EmbeddedBrowser} implementation backed by Equo Chromium.
 *
 * <p>Uses URL scheme interception ({@code swm://postMessage/}) for JS→Java messaging.
 * Automatically injects {@code window.__equoHost = true} so the bridge JS detects the Equo transport.
 *
 * <pre>{@code
 * EmbeddedBrowser browser = new EquoBrowserAdapter();
 * }</pre>
 */
public class EquoBrowserAdapter implements EmbeddedBrowser {

    private static final Logger logger = LoggerFactory.getLogger(EquoBrowserAdapter.class);
    private static final String SWM_SCHEME = "swm://postMessage/";

    private final List<Runnable> pageLoadListeners = new CopyOnWriteArrayList<>();
    private ChromiumBrowser browser;
    private JPanel container;
    private Function<String, String> incomingMessageHandler;
    private Path tempFile;

    public EquoBrowserAdapter() {
    }

    public EquoBrowserAdapter(EquoBrowserConfig config) {
    }

    @Override
    public Component createComponent() {
        container = new JPanel(new BorderLayout());
        // Browser will be created in loadUrl() since ChromiumBrowser.swing() requires a URL
        return container;
    }

    @Override
    public void loadUrl(String url) {
        String resolvedUrl = resolveUrl(url);

        if (browser == null) {
            // First load: create the browser with the target URL
            browser = ChromiumBrowser.swing(container, BorderLayout.CENTER, resolvedUrl);
            setupUrlInterception();
            // Inject host flag so the JS bridge detects the Equo transport
            browser.executeJavaScript("window.__equoHost = true");
            logger.info("Equo Chromium browser created, loading: {}", resolvedUrl);
        } else {
            // Subsequent navigations
            browser.setUrl(resolvedUrl);
        }
    }

    @Override
    public void executeJavaScript(String script) {
        if (browser != null) {
            browser.executeJavaScript(script);
        }
    }

    @Override
    public void setIncomingMessageHandler(Function<String, String> handler) {
        this.incomingMessageHandler = handler;
    }

    @Override
    public void addPageLoadListener(Runnable callback) {
        pageLoadListeners.add(callback);
    }

    @Override
    public void dispose() {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                logger.warn("Failed to delete temp file: {}", tempFile, e);
            }
        }
    }

    /**
     * Get the underlying {@link ChromiumBrowser} for advanced use cases.
     */
    public ChromiumBrowser getBrowser() {
        return browser;
    }

    // ========== Internal ==========

    private void setupUrlInterception() {
        browser.subscribe().onBeforeBrowse(event -> {
            String url = event.getUrl();
            if (url != null && url.startsWith(SWM_SCHEME)) {
                event.prevent();
                handleIncomingMessage(url);
            }
        });
    }

    private void handleIncomingMessage(String url) {
        if (incomingMessageHandler == null) {
            logger.warn("No incoming message handler set, ignoring message");
            return;
        }
        try {
            String encoded = url.substring(SWM_SCHEME.length());
            String json = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
            logger.debug("Received from JS: {}", json);
            incomingMessageHandler.apply(json);
        } catch (Exception e) {
            logger.error("Error handling message from: {}", url, e);
        }
    }

    /**
     * Resolve a URL for loading in CEF.
     * CEF cannot load jar: URLs, so classpath resources are extracted to temp files.
     */
    private String resolveUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return url;
        }

        // Try as classpath resource
        URL resource = getClass().getResource(url);
        if (resource == null) {
            // Not a classpath resource, return as-is (might be a file path)
            return url;
        }

        if ("jar".equals(resource.getProtocol())) {
            try {
                tempFile = Files.createTempFile("smart-web-ehr-", ".html");
                tempFile.toFile().deleteOnExit();
                try (InputStream in = getClass().getResourceAsStream(url)) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return tempFile.toUri().toString();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to extract resource: " + url, e);
            }
        }

        return resource.toExternalForm();
    }
}

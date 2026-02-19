package health.tiro.smartwebehr.ui.equo;

import com.equo.chromium.ChromiumBrowser;
import health.tiro.smartwebehr.ui.EmbeddedBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * {@link EmbeddedBrowser} implementation backed by Equo Chromium.
 *
 * <p>Uses URL scheme interception ({@code swm://postMessage/}) for JS→Java messaging
 * and {@code window.swmReceiveMessage(...)} for Java→JS messaging.
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

    public EquoBrowserAdapter() {
    }

    public EquoBrowserAdapter(EquoBrowserConfig config) {
    }

    @Override
    public Component createComponent() {
        container = new JPanel(new BorderLayout());
        return container;
    }

    @Override
    public void loadUrl(String url) {
        if (browser == null) {
            browser = ChromiumBrowser.swing(container, BorderLayout.CENTER, url);
            setupUrlInterception();
            setupPageLoadListener();
            setupConsoleListener();
            logger.info("Equo Chromium browser created, loading: {}", url);
        } else {
            browser.setUrl(url);
        }
    }

    @Override
    public void executeJavaScript(String script) {
        if (browser != null) {
            browser.executeJavaScript(script);
        }
    }

    @Override
    public void sendMessage(String json) {
        sendToJsInternal(json);
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
        if (browser != null) {
            browser.close();
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

    private void setupPageLoadListener() {
        browser.subscribe().onLoadEnd(event -> {
            logger.info("Page load complete (status={})", event.getHttpStatusCode());
            browser.executeJavaScript(
                "if (!window.__equoHost) { " +
                "  window.__equoHost = true; " +
                "  console.log('[Equo] __equoHost flag injected'); " +
                "  if (window.SmartWebMessaging && typeof window.SmartWebMessaging.init === 'function') { " +
                "    window.SmartWebMessaging.init(); " +
                "  } " +
                "}"
            );
            for (Runnable listener : pageLoadListeners) {
                listener.run();
            }
        });
    }

    private void setupConsoleListener() {
        browser.addConsoleListener((level, message, source, line) -> {
            // CEF levels: 0=DEFAULT, 1=VERBOSE, 2=INFO(console.log), 3+=WARNING/ERROR
            if (level >= 3) {
                logger.warn("[JS] {}", message);
            } else {
                logger.debug("[JS] {}", message);
            }
            return false;
        });
    }

    private void handleIncomingMessage(String url) {
        try {
            String encoded = url.substring(SWM_SCHEME.length());
            String json = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
            logger.debug("Received from JS: {}", json);

            if (incomingMessageHandler != null) {
                String responseJson = incomingMessageHandler.apply(json);
                if (responseJson != null) {
                    sendToJsInternal(responseJson);
                }
            }
        } catch (Exception e) {
            logger.error("Error handling message from: {}", url, e);
        }
    }

    private void sendToJsInternal(String json) {
        String escaped = json
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
        browser.executeJavaScript("window.swmReceiveMessage('" + escaped + "');");
    }

}

package health.tiro.formfiller.swing.equo;

import com.equo.chromium.ChromiumBrowser;
import health.tiro.formfiller.swing.BridgeScriptLoader;
import health.tiro.formfiller.swing.EmbeddedBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class EquoBrowserAdapter implements EmbeddedBrowser, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(EquoBrowserAdapter.class);
    private static final String SWM_SCHEME = "swm://postMessage/";

    private final List<Runnable> pageLoadListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "swm-message-handler");
        t.setDaemon(true);
        return t;
    });
    private ChromiumBrowser browser;
    private JPanel container;
    private volatile Function<String, String> incomingMessageHandler;

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
    public synchronized void loadUrl(String url) {
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
    public void setIncomingMessageHandler(Function<String, String> handler) {
        this.incomingMessageHandler = handler;
    }

    @Override
    public void addPageLoadListener(Runnable callback) {
        pageLoadListeners.add(callback);
    }

    @Override
    public void close() {
        executor.shutdownNow();
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

            // 1. Inject the common bridge JS. Loading it also extracts the embedded web-sdk,
            // which can fail on a locked-down desktop (policy or antivirus blocking the temp
            // folder). Report that: uncaught, it aborts injection silently and the only symptom
            // is the handshake timing out 30s later, which reads as a page that never answered.
            try {
                browser.executeJavaScript(BridgeScriptLoader.getScript());
            } catch (RuntimeException e) {
                logger.error("Failed to inject the SWM bridge — the form cannot load. The page "
                        + "will never complete its handshake.", e);
                throw e;
            }

            // 2. Initialize with Equo transport (iframe URL scheme)
            browser.executeJavaScript(
                "if (window.SmartWebMessaging && typeof window.SmartWebMessaging.init === 'function') {" +
                "  window.SmartWebMessaging.init(function(message) {" +
                "    var json = JSON.stringify(message);" +
                "    var iframe = document.createElement('iframe');" +
                "    iframe.style.display = 'none';" +
                "    document.documentElement.appendChild(iframe);" +
                "    iframe.src = 'swm://postMessage/' + encodeURIComponent(json);" +
                "    setTimeout(function() {" +
                "      if (iframe.parentNode) iframe.parentNode.removeChild(iframe);" +
                "    }, 0);" +
                "  });" +
                "}"
            );

            for (Runnable listener : pageLoadListeners) {
                try {
                    listener.run();
                } catch (Exception e) {
                    logger.error("Error in page load listener", e);
                }
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
                executor.execute(() -> {
                    try {
                        String responseJson = incomingMessageHandler.apply(json);
                        if (responseJson != null) {
                            sendMessage(responseJson);
                        }
                    } catch (Exception e) {
                        logger.error("Error processing message", e);
                    }
                });
            }
        } catch (Exception e) {
            logger.error("Error handling message from: {}", url, e);
        }
    }

}

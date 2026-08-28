package health.tiro.formfiller.swing.jxbrowser;

import com.teamdev.jxbrowser.browser.Browser;
import com.teamdev.jxbrowser.js.JsAccessible;
import com.teamdev.jxbrowser.js.JsObject;
import health.tiro.formfiller.swing.BridgeScriptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Internal bridge between JxBrowser and Java.
 * Exposes a {@code @JsAccessible} {@link #postMessage(String)} method
 * that JS calls via {@code window.javaBridge.postMessage(json)}.
 */
public class JxBrowserBridge implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(JxBrowserBridge.class);

    private final Browser browser;
    private final Consumer<String> responseSender;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "swm-message-handler");
        t.setDaemon(true);
        return t;
    });
    private volatile Function<String, String> incomingMessageHandler;

    JxBrowserBridge(Browser browser, Consumer<String> responseSender) {
        this.browser = browser;
        this.responseSender = responseSender;
    }

    void setIncomingMessageHandler(Function<String, String> handler) {
        this.incomingMessageHandler = handler;
    }

    /**
     * Called from JavaScript via {@code window.javaBridge.postMessage(json)}.
     * JS → Java --> JS request path
     */
    @JsAccessible
    public void postMessage(String json) {
        logger.debug("Received message from JS: {}", json);
        if (incomingMessageHandler == null) {
            logger.warn("No incoming message handler set, ignoring message");
            return;
        }
        executor.execute(() -> {
            try {
                String response = incomingMessageHandler.apply(json);
                if (response != null) {
                    responseSender.accept(response);
                }
            } catch (Exception e) {
                 logger.error("Error handling message from JS", e);
            }
        });
    }
    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * Inject the SWM bridge into the browser page.
     * <ol>
     *   <li>Exposes this object as {@code window.javaBridge}</li>
     *   <li>Injects the common bridge JS from the classpath</li>
     *   <li>Initialises the bridge with the JxBrowser transport</li>
     * </ol>
     */
    void injectBridge() {
        browser.mainFrame().ifPresent(frame -> {
            // 1. Expose this Java object as window.javaBridge
            JsObject window = frame.executeJavaScript("window");
            if (window != null) {
                window.putProperty("javaBridge", this);
            }

            // 2. Inject the common bridge JS. Loading it also extracts the embedded web-sdk,
            // which can fail on a locked-down desktop (policy or antivirus blocking the temp
            // folder). Report that: uncaught, it aborts injection silently and the only symptom
            // is the handshake timing out 30s later, which reads as a page that never answered.
            try {
                frame.executeJavaScript(BridgeScriptLoader.getScript());
            } catch (RuntimeException e) {
                logger.error("Failed to inject the SWM bridge — the form cannot load. The page "
                        + "will never complete its handshake.", e);
                throw e;
            }

            // 3. Initialize with JxBrowser transport
            frame.executeJavaScript(
                "if (window.SmartWebMessaging && typeof window.SmartWebMessaging.init === 'function') {" +
                "  window.SmartWebMessaging.init(function(message) {" +
                "    window.javaBridge.postMessage(JSON.stringify(message));" +
                "  });" +
                "}"
            );
            logger.info("JxBrowser bridge injected");
        });
    }
}

package health.tiro.smartwebehr.ui.jxbrowser;

import com.teamdev.jxbrowser.browser.Browser;
import com.teamdev.jxbrowser.js.JsAccessible;
import com.teamdev.jxbrowser.js.JsObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Internal bridge between JxBrowser and Java.
 * Exposes a {@code @JsAccessible} {@link #postMessage(String)} method
 * that JS calls via {@code window.javaBridge.postMessage(json)}.
 */
public class JxBrowserBridge {

    private static final Logger logger = LoggerFactory.getLogger(JxBrowserBridge.class);

    private final Browser browser;
    private Function<String, String> incomingMessageHandler;

    JxBrowserBridge(Browser browser) {
        this.browser = browser;
    }

    void setIncomingMessageHandler(Function<String, String> handler) {
        this.incomingMessageHandler = handler;
    }

    /**
     * Called from JavaScript via {@code window.javaBridge.postMessage(json)}.
     */
    @JsAccessible
    public void postMessage(String json) {
        logger.debug("Received message from JS: {}", json);
        if (incomingMessageHandler == null) {
            logger.warn("No incoming message handler set, ignoring message");
            return;
        }
        try {
            incomingMessageHandler.apply(json);
        } catch (Exception e) {
            logger.error("Error handling message from JS", e);
        }
    }

    /**
     * Inject the bridge into the browser page.
     * Exposes this object as {@code window.javaBridge}, then calls
     * {@code SmartWebMessaging.init()} so the bridge JS detects the JxBrowser transport.
     */
    void injectBridge() {
        browser.mainFrame().ifPresent(frame -> {
            JsObject window = frame.executeJavaScript("window");
            if (window != null) {
                window.putProperty("javaBridge", this);
            }

            frame.executeJavaScript(
                "if (typeof SmartWebMessaging !== 'undefined' && SmartWebMessaging.init) {" +
                "  console.log('[SWM] Re-initializing for JxBrowser transport...');" +
                "  SmartWebMessaging.init();" +
                "}"
            );
            logger.info("JxBrowser bridge injected");
        });
    }
}

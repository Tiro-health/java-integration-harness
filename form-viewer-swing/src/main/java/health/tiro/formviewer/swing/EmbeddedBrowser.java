package health.tiro.formviewer.swing;

import java.awt.Component;
import java.util.function.Function;

/**
 * Abstraction over an embedded browser engine (JxBrowser, Equo Chromium, etc.).
 * Adapters implement this interface to plug into {@link FormViewer}.
 *
 * <p>The transport layer (how JS→Java messages arrive) is adapter-internal.
 * Java→JS messages are sent via {@link #sendMessage(String)}, which by default
 * calls {@code window.swmReceiveMessage(...)} in the browser.
 */
public interface EmbeddedBrowser {

    /**
     * Create the browser Swing component. Does NOT load a URL yet.
     *
     * @return a Swing {@link Component} that can be added to a container
     */
    Component createComponent();

    /**
     * Navigate the browser to the given URL.
     *
     * @param url the URL to load
     */
    void loadUrl(String url);

    /**
     * Execute JavaScript in the browser's main frame.
     *
     * @param script the JavaScript code to execute
     */
    void executeJavaScript(String script);

    /**
     * Set the handler for incoming messages from JS.
     * The handler receives raw JSON and returns an optional response JSON string
     * (null means no response). Called by the adapter when JS sends a message
     * via the transport layer.
     *
     * @param handler function that processes incoming JSON and returns optional response JSON
     */
    void setIncomingMessageHandler(Function<String, String> handler);

    /**
     * Send a JSON message from Java to JS.
     * Default implementation calls {@code window.swmReceiveMessage(...)} via
     * {@link #executeJavaScript(String)}.
     *
     * @param json the JSON message to send
     */
    default void sendMessage(String json) {
        String escaped = json.replace("\\", "\\\\")
                             .replace("'", "\\'")
                             .replace("\n", "\\n")
                             .replace("\r", "\\r")
                             .replace("\u2028", "\\u2028")
                             .replace("\u2029", "\\u2029")
                             .replace("\u0000", "\\u0000");
        executeJavaScript("window.swmReceiveMessage('" + escaped + "');");
    }

    /**
     * Register a callback for main-frame page load completion.
     * The callback may fire multiple times (on each navigation).
     *
     * @param callback invoked when the main frame finishes loading
     */
    void addPageLoadListener(Runnable callback);

    /**
     * Clean up all browser resources (engine, temp files, etc.).
     */
    void dispose();
}

package health.tiro.formfiller.swing.jxbrowser;

import com.teamdev.jxbrowser.browser.Browser;
import com.teamdev.jxbrowser.browser.event.ConsoleMessageReceived;
import com.teamdev.jxbrowser.engine.Engine;
import com.teamdev.jxbrowser.engine.EngineOptions;
import com.teamdev.jxbrowser.js.JsObject;
import com.teamdev.jxbrowser.navigation.event.FrameLoadFinished;
import com.teamdev.jxbrowser.permission.PermissionType;
import com.teamdev.jxbrowser.permission.callback.RequestPermissionCallback;
import com.teamdev.jxbrowser.view.swing.BrowserView;
import health.tiro.formfiller.swing.EmbeddedBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * {@link EmbeddedBrowser} implementation backed by JxBrowser.
 *
 * <pre>{@code
 * EmbeddedBrowser browser = new JxBrowserAdapter(
 *     JxBrowserConfig.builder().licenseKey("YOUR-KEY").build()
 * );
 * }</pre>
 */
public class JxBrowserAdapter implements EmbeddedBrowser, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(JxBrowserAdapter.class);

    private final List<Runnable> pageLoadListeners = new CopyOnWriteArrayList<>();
    private final Engine engine;
    private Browser browser;
    private JxBrowserBridge bridge;
    private Function<String, String> pendingIncomingMessageHandler;
    private boolean ownsEngine;

    /**
     * Creates a new adapter with an internally managed {@link Engine}.
     * <p>
     * Use this constructor when this adapter should have its own dedicated Chromium process.
     * The underlying engine will be automatically closed when {@link #close()} is called.
     *
     * @param config the configuration used to initialize the internal JxBrowser engine
     */
    public JxBrowserAdapter(JxBrowserConfig config) {
        this.engine = Engine.newInstance(EngineOptions.newBuilder(config.getRenderingMode())
                .licenseKey(config.getLicenseKey())
                .language(config.getLanguage())
                .build());
        this.ownsEngine = true;
    }

    /**
     * Creates a new adapter using an externally provided {@link Engine}.
     * <p>
     * Use this constructor to share a single Chromium process across multiple browser instances
     * to save memory. Note that calling {@link #close()} on this adapter will close the
     * {@link Browser} instance it created, but will <b>not</b> close the shared engine.
     *
     * @param engine the existing JxBrowser engine to be used by this adapter
     * @throws NullPointerException if the provided engine is null
     */
    public JxBrowserAdapter(Engine engine) {
        if (engine == null) {
            throw new NullPointerException("The provided engine cannot be null.");
        }
        this.engine = engine;
        this.ownsEngine = false;
    }

    @Override
    public Component createComponent() {

        engine.permissions().set(RequestPermissionCallback.class, (params, tell) -> {
            if (params.permissionType() == PermissionType.AUDIO_CAPTURE) {
                logger.info("Granting microphone permission");
                tell.grant();
            } else {
                tell.deny();
            }
        });

        browser = engine.newBrowser();

        browser.on(ConsoleMessageReceived.class, event ->
            logger.info("[JS Console] {}: {}",
                event.consoleMessage().level(),
                event.consoleMessage().message())
        );

        bridge = new JxBrowserBridge(browser, this::sendMessage);

        if (pendingIncomingMessageHandler != null) {
            bridge.setIncomingMessageHandler(pendingIncomingMessageHandler);
            pendingIncomingMessageHandler = null;
        }

        browser.navigation().on(FrameLoadFinished.class, event -> {
            if (event.frame().isMain()) {
                logger.info("Page loaded: {}", event.url());
                bridge.injectBridge();
                for (Runnable listener : pageLoadListeners) {
                    try {
                        listener.run();
                    } catch (Exception e) {
                        logger.error("Error in page load listener", e);
                    }
                }
            }
        });

        return BrowserView.newInstance(browser);
    }

    @Override
    public void loadUrl(String url) {
        browser.navigation().loadUrl(url);
    }

    @Override
    public void executeJavaScript(String script) {
        browser.mainFrame().ifPresent(frame -> frame.executeJavaScript(script));
    }

    @Override
    public void sendMessage(String json) {
        browser.mainFrame().ifPresent(frame -> {
            JsObject window = frame.executeJavaScript("window");
            if (window != null) {
                window.call("swmReceiveMessage", json);
            }
        });
    }

    @Override
    public void setIncomingMessageHandler(Function<String, String> handler) {
        if (bridge != null) {
            bridge.setIncomingMessageHandler(handler);
        } else {
            pendingIncomingMessageHandler = handler;
        }
    }

    @Override
    public void addPageLoadListener(Runnable callback) {
        pageLoadListeners.add(callback);
    }

    @Override
    public void close() {
        if (bridge != null) {
            bridge.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (ownsEngine && engine != null && !engine.isClosed()) {
            engine.close();
        }
    }

    /**
     * Get the underlying JxBrowser {@link Browser} for advanced use cases.
     */
    public Browser getBrowser() {
        return browser;
    }
}

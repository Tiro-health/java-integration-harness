package health.tiro.smartwebehr.ui.jxbrowser;

import com.teamdev.jxbrowser.browser.Browser;
import com.teamdev.jxbrowser.browser.event.ConsoleMessageReceived;
import com.teamdev.jxbrowser.engine.Engine;
import com.teamdev.jxbrowser.engine.EngineOptions;
import com.teamdev.jxbrowser.navigation.event.FrameLoadFinished;
import com.teamdev.jxbrowser.permission.PermissionType;
import com.teamdev.jxbrowser.permission.callback.RequestPermissionCallback;
import com.teamdev.jxbrowser.view.swing.BrowserView;
import health.tiro.smartwebehr.ui.EmbeddedBrowser;
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
public class JxBrowserAdapter implements EmbeddedBrowser {

    private static final Logger logger = LoggerFactory.getLogger(JxBrowserAdapter.class);

    private final JxBrowserConfig config;
    private final List<Runnable> pageLoadListeners = new CopyOnWriteArrayList<>();
    private Engine engine;
    private Browser browser;
    private JxBrowserBridge bridge;
    private Function<String, String> pendingIncomingMessageHandler;

    public JxBrowserAdapter(JxBrowserConfig config) {
        this.config = config;
    }

    @Override
    public Component createComponent() {
        engine = Engine.newInstance(EngineOptions.newBuilder(config.getRenderingMode())
            .licenseKey(config.getLicenseKey())
            .language(config.getLanguage())
            .build());

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

        bridge = new JxBrowserBridge(browser);

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
    public void dispose() {
        if (engine != null) {
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

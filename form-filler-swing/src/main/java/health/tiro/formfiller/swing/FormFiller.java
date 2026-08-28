package health.tiro.formfiller.swing;

import com.fasterxml.jackson.databind.JsonNode;
import health.tiro.formfiller.swing.tracing.FormFillerTracer;
import health.tiro.formfiller.swing.tracing.FormFillerTracerFactory;
import health.tiro.swm.AbstractSmartMessageHandler;
import health.tiro.swm.events.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller that wires an embedded browser to a SMART Web Messaging handler.
 * Manages the handshake, message routing, and event dispatch.
 *
 * <pre>{@code
 * var handler = new health.tiro.swm.r5.SmartMessageHandler();
 * var browser = new JxBrowserAdapter(JxBrowserConfig.builder().licenseKey("...").build());
 * var config = FormFillerConfig.builder().targetUrl("https://...").build();
 * var viewer = new FormFiller(config, browser, handler);
 *
 * viewer.addFormFillerListener(new FormFillerListener() {
 *     @Override
 *     public void onFormSubmitted(IBaseResource response, IBaseResource outcome) {
 *         // process
 *     }
 * });
 *
 * frame.add(viewer.getComponent(), BorderLayout.CENTER);
 * }</pre>
 */
public class FormFiller<H extends AbstractSmartMessageHandler> implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FormFiller.class);
    private static final Pattern MESSAGE_TYPE_PATTERN = Pattern.compile(
        "\"messageType\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final FormFillerConfig config;
    private final FormFillerTracer tracer;
    private final EmbeddedBrowser browser;
    private final H handler;
    private final Component component;
    private volatile CompletableFuture<Void> handshakeReceived = new CompletableFuture<>();
    private volatile String pageWebSdkVersion;
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "swm-handshake-timeout");
        t.setDaemon(true);
        return t;
    });
    private final List<FormFillerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a new FormFiller.
     *
     * @param config  configuration (target URL, timeouts)
     * @param browser the embedded browser adapter
     * @param handler the SMART Web Messaging handler (R4 or R5)
     */
    public FormFiller(FormFillerConfig config, EmbeddedBrowser browser, H handler) {
        this.config = config;
        this.browser = browser;
        this.handler = handler;
        this.tracer = FormFillerTracerFactory.create();

        tracer.startSession(config.getTargetUrl(), browser.getClass().getSimpleName());

        // Wire incoming messages: JS → handler → response sent by adapter via return value
        browser.setIncomingMessageHandler(json -> {
            tracer.traceMessageReceived(extractMessageType(json), handler.getMessageIdFromJson(json), json);
            return handler.handleMessage(json);
        });

        // Wire outgoing messages: handler → JS (queued until handshake completes)
        handler.setMessageSender(json -> {
            tracer.traceMessageSent(extractMessageType(json), handler.getMessageIdFromJson(json), json);
            return handshakeReceived.thenApply(v -> {
                browser.sendMessage(json);
                return null;
            });
        });

        // Track bridge injection on page load
        browser.addPageLoadListener(() -> tracer.traceBridgeInjected());

        // Listen for SMART Web Messaging events
        handler.addListener(new SmartMessageListener() {
            @Override
            public void onHandshakeReceived(HandshakeReceivedEvent event) {
                logger.info("Handshake received from web page");
                tracer.traceHandshakeReceived();
                if (acceptWebSdkReport(event.getPayload())) {
                    handshakeReceived.complete(null);
                    fireHandshakeReceived();
                }
            }

            @Override
            public void onFormSubmitted(FormSubmittedEvent event) {
                logger.info("Form submitted");
                tracer.traceFormSubmitted();
                fireFormSubmitted(event.getResponse(), event.getOutcome());
            }

            @Override
            public void onCloseApplication(CloseApplicationEvent event) {
                logger.info("Close requested by web page");
                fireCloseRequested();
            }
        });

        // Create the browser component
        this.component = browser.createComponent();

        // Load the target URL (use default page if no custom URL is configured)
        String url = config.getTargetUrl();
        if (url == null || url.trim().isEmpty()) {
            url = DefaultPageLoader.createPage(config.getSdcEndpointAddress(), config.getDataEndpointAddress());
        }
        browser.loadUrl(url);
    }

    // ========== Listener management ==========

    public void addFormFillerListener(FormFillerListener listener) {
        listeners.add(listener);
    }

    public void removeFormFillerListener(FormFillerListener listener) {
        listeners.remove(listener);
    }

    // ========== Embedded web-sdk check (GH-24) ==========

    /**
     * Decides whether the page may proceed, from the {@code client} report the bridge puts in
     * the handshake payload. Returns false — and refuses the session — when the page is not
     * running the bundle this harness embeds.
     *
     * <p>The decision is made on {@code source}, not on the version. {@code source} is the
     * bridge's own account of what it did (inject ours, stand aside for the page's own copy,
     * or fail), which needs no cooperation from the SDK; the version is the element's
     * self-report, which a foreign or tampered bundle can set to anything. Comparing versions
     * would only add a way to refuse a <em>working</em> session — if a future SDK dropped its
     * static version field, say — so a mismatch is logged and nothing more. It is real
     * information: with {@code source: "collision"} it names the version the page substituted.
     */
    private boolean acceptWebSdkReport(JsonNode payload) {
        JsonNode client = payload == null ? null : payload.get("client");
        String source = textOrNull(client, "source");
        String version = textOrNull(client, "version");
        this.pageWebSdkVersion = version;
        tracer.traceWebSdkReported(version, source);

        if ("collision".equals(source) || "error".equals(source)) {
            WebSdkLoadException failure = new WebSdkLoadException(source);
            logger.error("Refusing the form-filler session: {} (page reported tiro-web-sdk {}, embedded is {})",
                    failure.getMessage(), version == null ? "no version" : version, WebSdkAssets.getVersion());
            handshakeReceived.completeExceptionally(failure);
            fireWebSdkLoadFailed(failure);
            return false;
        }

        if (source == null) {
            // Not our bridge's handshake — an older bridge, or a page doing its own SMART Web
            // Messaging. There is no evidence of a bad pairing here, only an absence of
            // evidence, so this reports rather than refuses.
            logger.warn("The page's handshake carried no tiro-web-sdk report, so the harness cannot "
                    + "confirm it is running the embedded bundle ({}).", WebSdkAssets.getVersion());
        } else if (version != null && !version.equals(WebSdkAssets.getVersion())) {
            logger.warn("The page reports tiro-web-sdk {} but this harness embeds {}. The bridge injected "
                    + "the embedded bundle, so this is a self-report worth investigating rather than a "
                    + "refused session.", version, WebSdkAssets.getVersion());
        } else {
            logger.info("Page is running the embedded tiro-web-sdk {}", WebSdkAssets.getVersion());
        }
        return true;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * The {@code tiro-web-sdk} version the page reported at handshake, or {@code null} if it
     * reported none (or the handshake has not happened yet). Diagnostics: what the harness
     * embeds is {@link WebSdkAssets#getVersion()}, and the two agreeing is not what makes a
     * session safe — see {@link #acceptWebSdkReport}.
     */
    public String getPageWebSdkVersion() {
        return pageWebSdkVersion;
    }

    private void fireWebSdkLoadFailed(WebSdkLoadException error) {
        SwingUtilities.invokeLater(() -> {
            for (FormFillerListener listener : listeners) {
                try {
                    listener.onWebSdkLoadFailed(error);
                } catch (Exception e) {
                    logger.error("Error in listener onWebSdkLoadFailed", e);
                }
            }
        });
    }

    private void fireHandshakeReceived() {
        SwingUtilities.invokeLater(() -> {
            for (FormFillerListener listener : listeners) {
                try {
                    listener.onHandshakeReceived();
                } catch (Exception e) {
                    logger.error("Error in listener onHandshakeReceived", e);
                }
            }
        });
    }

    private void fireFormSubmitted(IBaseResource response, IBaseResource outcome) {
        SwingUtilities.invokeLater(() -> {
            for (FormFillerListener listener : listeners) {
                try {
                    listener.onFormSubmitted(response, outcome);
                } catch (Exception e) {
                    logger.error("Error in listener onFormSubmitted", e);
                }
            }
        });
    }

    private void fireCloseRequested() {
        SwingUtilities.invokeLater(() -> {
            for (FormFillerListener listener : listeners) {
                try {
                    listener.onCloseRequested();
                } catch (Exception e) {
                    logger.error("Error in listener onCloseRequested", e);
                }
            }
        });
    }

    // ========== Public API ==========

    /**
     * Returns the browser component for the caller to place in their UI.
     */
    public Component getComponent() {
        return component;
    }

    /**
     * Returns a future that resolves when the JS page completes the SMART Web Messaging
     * handshake, or fails with a {@link TimeoutException} after the configured timeout.
     */
    public CompletableFuture<Void> waitForHandshake() {
        return withTimeout(handshakeReceived, config.getHandshakeTimeoutSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Request form submission from the frontend (finalize).
     */
    public void requestSubmit() {
        requestSubmit(null);
    }

    /**
     * Request form submission from the frontend with an explicit intent.
     *
     * @param intent {@code "finalize"} (or {@code null}) finalizes the form;
     *               {@code "save-draft"} persists an in-progress draft. The form
     *               component remains the authority on the resulting status.
     */
    public void requestSubmit(String intent) {
        logger.info("Requesting form submit (intent={})", intent);
        handler.sendFormRequestSubmitAsync(intent, null);
    }

    /**
     * Navigate the browser to a different URL.
     * Resets the handshake state so outbound messages are queued until the new page completes its handshake.
     */
    public void navigate(String url) {
        handshakeReceived = new CompletableFuture<>();
        pageWebSdkVersion = null;
        handler.clearAllResponseListeners();
        browser.loadUrl(url);
    }

    /**
     * Get the underlying message handler for direct access to
     * {@code sendSdcDisplayQuestionnaireAsync(...)} and other methods.
     */
    public H getMessageHandler() {
        return handler;
    }

    /**
     * Get the underlying browser for advanced use cases.
     */
    public EmbeddedBrowser getBrowser() {
        return browser;
    }

    /**
     * Clean up resources. Call this when the viewer is no longer needed.
     */
    @Override
    public void close() {
        tracer.finishSession();
        timeoutScheduler.shutdownNow();
        // Run on a separate thread to avoid deadlocks when called from within
        // a browser callback (e.g., from an onFormSubmitted listener).
        new Thread(browser::close, "formfiller-dispose").start();
    }

    private static String extractMessageType(String json) {
        Matcher m = MESSAGE_TYPE_PATTERN.matcher(json);
        if (m.find()) return m.group(1);
        if (json.contains("\"responseToMessageId\"")) return "response";
        return "unknown";
    }

    /**
     * Java 8 compatible replacement for CompletableFuture.orTimeout().
     */
    private <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, long timeout, TimeUnit unit) {
        CompletableFuture<T> result = new CompletableFuture<>();
        future.whenComplete((value, ex) -> {
            if (ex != null) {
                result.completeExceptionally(ex);
            } else {
                result.complete(value);
            }
        });
        timeoutScheduler.schedule(() -> {
            if (!result.isDone()) {
                result.completeExceptionally(new TimeoutException("Handshake timeout after " + timeout + " " + unit));
            }
        }, timeout, unit);
        return result;
    }
}

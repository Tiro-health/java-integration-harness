package health.tiro.formfiller.swing;

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
public class FormFiller implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FormFiller.class);
    private static final Pattern MESSAGE_TYPE_PATTERN = Pattern.compile(
        "\"messageType\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final FormFillerConfig config;
    private final FormFillerTracer tracer;
    private final EmbeddedBrowser browser;
    private final AbstractSmartMessageHandler handler;
    private final Component component;
    private volatile CompletableFuture<Void> handshakeReceived = new CompletableFuture<>();
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
    public FormFiller(FormFillerConfig config, EmbeddedBrowser browser, AbstractSmartMessageHandler handler) {
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
                handshakeReceived.complete(null);
                fireHandshakeReceived();
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
            url = DefaultPageLoader.createPage(config.getSdcEndpointAddress(), config.getDataEndpointAddress(), config.getSdkUrl());
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
     * Request form submission from the frontend.
     */
    public void requestSubmit() {
        logger.info("Requesting form submit");
        handler.sendFormRequestSubmitAsync(null);
    }

    /**
     * Navigate the browser to a different URL.
     * Resets the handshake state so outbound messages are queued until the new page completes its handshake.
     */
    public void navigate(String url) {
        handshakeReceived = new CompletableFuture<>();
        handler.clearAllResponseListeners();
        browser.loadUrl(url);
    }

    /**
     * Get the underlying message handler for direct access to
     * {@code sendSdcDisplayQuestionnaireAsync(...)} and other methods.
     */
    public AbstractSmartMessageHandler getMessageHandler() {
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

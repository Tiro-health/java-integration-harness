package health.tiro.formfiller.swing;

import com.fasterxml.jackson.databind.JsonNode;
import health.tiro.formfiller.swing.tracing.FormFillerTracer;
import health.tiro.formfiller.swing.tracing.FormFillerTracerFactory;
import health.tiro.swm.AbstractSmartMessageHandler;
import health.tiro.sdc.compat.SdcServerVersionProbe;
import health.tiro.sdc.compat.SdcVersionCheckOutcome;
import health.tiro.sdc.compat.SdcVersionCheckResult;
import health.tiro.swm.events.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.net.URI;
import java.net.URISyntaxException;
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
 * var config = FormFillerConfig.builder().sdcEndpointAddress("https://host/fhir/r5").build();
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
    // Set once, and never cleared except by navigate(): a refused session is terminal, so
    // every later handshake on the same page is answered with the same refusal.
    private volatile WebSdkLoadException webSdkFailure;
    private volatile Thread sdcVersionProbeThread;
    private volatile boolean closed;
    private volatile SdcVersionCheckResult sdcServerVersionCheck;
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

                // Throwing is what turns the ack into an error response: the handler wraps
                // listener dispatch and answers a thrown exception with an error payload, so
                // the page's handshake rejects instead of logging "Connected" over a form that
                // will never render. Nothing else here can report to the page.
                WebSdkLoadException failure = evaluateWebSdkReport(event.getPayload());
                if (failure != null) throw failure;

                // complete() returns false if the future already settled — a repeat handshake,
                // or one racing another. Only the transition fires the callback, so a host
                // never sees onHandshakeReceived twice, or after a refusal.
                if (handshakeReceived.complete(null)) fireHandshakeReceived();
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

        startSdcServerVersionCheck();
    }

    // ========== Listener management ==========

    public void addFormFillerListener(FormFillerListener listener) {
        listeners.add(listener);
    }

    public void removeFormFillerListener(FormFillerListener listener) {
        listeners.remove(listener);
    }

    // ========== SDC server version check (GH-24) ==========

    /**
     * The outcome of this viewer's SDC server version check, or {@code null} until it has
     * answered (and when no {@code sdcEndpointAddress} is configured, in which case there is
     * nothing to check). Exposed because the check's telemetry lands in the <em>customer's</em>
     * logs, not Tiro's — they self-host the SDC server.
     */
    public SdcVersionCheckResult getSdcServerVersionCheck() {
        return sdcServerVersionCheck;
    }

    /**
     * Reads the configured SDC server's version. Overridable so a host can route the probe
     * through its own transport — or a test can supply a verdict without network I/O; the
     * production behaviour is {@link SdcServerVersionProbe#check(URI)}, which is
     * unauthenticated. That costs nothing today: the SDC server holds its own service-account
     * credentials and requires none from the caller. Should that change, a 401 reads as an
     * unknown version and fails open — the check is disarmed, not the launch broken.
     *
     * <p><b>Called from a background thread this class starts during construction</b>, so it
     * can run before an overriding subclass's own field initializers have. An override must
     * depend only on its arguments and on state passed through the constructor arguments —
     * not on the subclass's fields. (The probe would report a resulting exception as an
     * unreadable version and carry on, so the mistake would be quiet.)
     */
    protected SdcVersionCheckResult checkSdcServerVersion(URI sdcBaseAddress) {
        return SdcServerVersionProbe.check(sdcBaseAddress);
    }

    /**
     * Probes the SDC server off the constructing thread and reports the verdict.
     *
     * <p>Nothing here refuses a launch, and nothing waits for it: the page is already loading,
     * and a version check must never be the reason a form is slow to appear or fails to. See
     * {@link health.tiro.sdc.compat.SdcCompatibility#minimumSdcVersion()} for why enforcement
     * is not shipped yet and what to add when the floor is first raised for a real reason.
     */
    private void startSdcServerVersionCheck() {
        String address = config.getSdcEndpointAddress();
        if (address == null || address.trim().isEmpty()) return;

        final URI sdcBase;
        try {
            URI parsed = new URI(address.trim());
            if (!parsed.isAbsolute()) throw new URISyntaxException(address, "not absolute");
            sdcBase = parsed;
        } catch (URISyntaxException e) {
            // Not this check's business to reject the address — the page will fail on it soon
            // enough, with a better message.
            logger.debug("sdcEndpointAddress is not an absolute URI; server version check skipped");
            return;
        }

        Thread probe = new Thread(() -> {
            SdcVersionCheckResult result;
            try {
                result = checkSdcServerVersion(sdcBase);
            } catch (RuntimeException e) {
                // SdcServerVersionProbe's contract is that a server-side or transport problem is
                // a result, not an exception — so reaching here means the check itself broke, or
                // an override did. Fail open: this exists to catch a bad pairing, not to add a
                // new way for a form launch to die.
                result = SdcVersionCheckResult.unavailable(
                        "The SDC server version check itself failed: " + e);
            }
            sdcServerVersionCheck = result;

            // The viewer can be closed while this is still in flight — a host that opens a
            // form against an unreachable server and closes it before the probe's deadline.
            // The transaction is finished by then, so a breadcrumb would attach to nothing and
            // a captured message would report a session the user already abandoned.
            if (closed) {
                logger.debug("Viewer closed before the SDC version check answered: {}", result);
                return;
            }
            tracer.traceSdcServerVersion(result.getOutcome().name(), result.toString());

            if (result.getOutcome() == SdcVersionCheckOutcome.SATISFIED) {
                logger.debug("{}", result);
            } else {
                // TOO_OLD is actionable — upgrade the server — while UNKNOWN is a diagnostic
                // about the check itself. Both are logged; collapsing them into one line would
                // turn the actionable one into noise.
                logger.warn("{}", result);
            }
        }, "sdc-version-check");
        probe.setDaemon(true);
        sdcVersionProbeThread = probe;
        probe.start();
    }

    // ========== Embedded web-sdk check (GH-24) ==========

    /**
     * Decides whether the page may proceed, from the {@code client} report the bridge puts in
     * the handshake payload. Returns the refusal — or {@code null} to proceed — when the page
     * is not running the bundle this harness embeds. The refusal is remembered: once a session
     * is refused it stays refused until {@link #navigate(String)} loads a different page.
     *
     * <p>The decision is made on {@code source}, not on the version. {@code source} is the
     * bridge's own account of what it did (inject ours, stand aside for the page's own copy,
     * or fail), which needs no cooperation from the SDK; the version is the element's
     * self-report, which a foreign or tampered bundle can set to anything. Comparing versions
     * would only add a way to refuse a <em>working</em> session — if a future SDK dropped its
     * static version field, say — so a mismatch is logged and nothing more. It is real
     * information: with {@code source: "collision"} it names the version the page substituted.
     */
    private WebSdkLoadException evaluateWebSdkReport(JsonNode payload) {
        // A refused session is terminal. Answer every later handshake with the same refusal
        // rather than re-deciding: a page that reloads into a working state would otherwise
        // announce a handshake the future has already failed, and the host would be told the
        // session is up while every message it sends still fails.
        WebSdkLoadException standing = webSdkFailure;
        if (standing != null) return standing;

        JsonNode client = payload == null ? null : payload.get("client");
        String source = textOrNull(client, "source");
        String version = textOrNull(client, "version");
        this.pageWebSdkVersion = version;
        tracer.traceWebSdkReported(version, source);

        if ("collision".equals(source) || "error".equals(source)) {
            WebSdkLoadException failure = new WebSdkLoadException(source);
            webSdkFailure = failure;
            logger.error("Refusing the form-filler session: {} (page reported tiro-web-sdk {}, embedded is {})",
                    failure.getMessage(), version == null ? "no version" : version, WebSdkAssets.getVersion());
            // Only the transition fires the callback, so a retried handshake cannot produce a
            // second onWebSdkLoadFailed or a duplicate breadcrumb.
            if (handshakeReceived.completeExceptionally(failure)) fireWebSdkLoadFailed(failure);
            return failure;
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
        return null;
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
     * session safe — see {@link #evaluateWebSdkReport}.
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
     * Resets the handshake state so outbound messages are queued until the new page completes
     * its handshake, and clears a standing {@link WebSdkLoadException} — a refusal describes
     * the page that caused it, and this is the one action that loads a different one.
     */
    public void navigate(String url) {
        handshakeReceived = new CompletableFuture<>();
        pageWebSdkVersion = null;
        // An explicit host action loading a different page: the previous page's verdict says
        // nothing about this one, so the refusal is reset here and only here.
        webSdkFailure = null;
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
        closed = true;
        // Interrupt rather than join: the probe is advisory and bounded by its own socket
        // timeouts, and close() is called from the EDT often enough that waiting on a network
        // read would be the wrong trade. The `closed` flag is what actually keeps a late
        // answer from reporting into a finished session; this just lets it end sooner.
        Thread probe = sdcVersionProbeThread;
        if (probe != null) probe.interrupt();
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

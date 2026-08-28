package health.tiro.formfiller.swing.tracing;

/**
 * Abstraction for tracing FormFiller lifecycle events.
 * <p>
 * When Sentry is on the classpath, a Sentry-backed implementation is used
 * that creates transactions and spans visible in the Sentry dashboard.
 * Otherwise, a no-op implementation is used with zero overhead.
 *
 * @see FormFillerTracerFactory#create()
 */
public interface FormFillerTracer {

    /** Start a session-level transaction. Called from FormFiller constructor. */
    void startSession(String targetUrl, String browserType);

    /** Record bridge script injection into the browser page. */
    void traceBridgeInjected();

    /** Record an outbound message (Java to JS). */
    void traceMessageSent(String messageType, String messageId, String json);

    /** Record an inbound message (JS to Java). */
    void traceMessageReceived(String messageType, String messageId, String json);

    /** Record SMART Web Messaging handshake completion. */
    void traceHandshakeReceived();

    /**
     * Record what tiro-web-sdk the page reported at handshake (GH-24).
     *
     * @param version the element's build-time version, or {@code null} if it reported none
     * @param source  {@code "embedded"}, {@code "collision"}, {@code "error"}, or {@code null}
     *                when the page's handshake carried no client report at all
     */
    default void traceWebSdkReported(String version, String source) {}

    /**
     * Record the SDC server version check's verdict (GH-24).
     *
     * @param outcome {@code SATISFIED}, {@code TOO_OLD} or {@code UNKNOWN}
     * @param summary the one-line verdict, naming both versions
     */
    default void traceSdcServerVersion(String outcome, String summary) {}

    /** Record form submission received from the browser. */
    void traceFormSubmitted();

    /** Finish the session transaction. Called from FormFiller.dispose(). */
    void finishSession();
}

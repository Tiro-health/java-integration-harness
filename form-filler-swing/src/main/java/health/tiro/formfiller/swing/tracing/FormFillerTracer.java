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

    /** Record form submission received from the browser. */
    void traceFormSubmitted();

    /** Finish the session transaction. Called from FormFiller.dispose(). */
    void finishSession();
}

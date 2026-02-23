package health.tiro.formfiller.swing.tracing;

/**
 * No-op tracer used when Sentry is not on the classpath.
 */
final class NoOpFormFillerTracer implements FormFillerTracer {

    static final NoOpFormFillerTracer INSTANCE = new NoOpFormFillerTracer();

    private NoOpFormFillerTracer() {}

    @Override public void startSession(String targetUrl, String browserType) {}
    @Override public void traceBridgeInjected() {}
    @Override public void traceMessageSent(String messageType, String messageId, String json) {}
    @Override public void traceMessageReceived(String messageType, String messageId, String json) {}
    @Override public void traceHandshakeReceived() {}
    @Override public void traceFormSubmitted() {}
    @Override public void finishSession() {}
}

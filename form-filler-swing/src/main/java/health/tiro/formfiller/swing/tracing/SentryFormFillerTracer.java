package health.tiro.formfiller.swing.tracing;

import io.sentry.Breadcrumb;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.SpanStatus;
import io.sentry.TransactionOptions;

/**
 * Sentry-backed tracer that creates a transaction per FormFiller session
 * with child spans for each lifecycle event.
 * <p>
 * The {@link ITransaction} is stored as an instance field and accessed from
 * multiple threads. Since {@code ITransaction.startChild()} is thread-safe,
 * this sidesteps the thread-local scope problem entirely — spans created on
 * the {@code swm-message-handler} thread, the Swing EDT, or any browser
 * engine thread all appear as children of the same transaction.
 */
final class SentryFormFillerTracer implements FormFillerTracer {

    private volatile ITransaction transaction;

    @Override
    public void startSession(String targetUrl, String browserType) {
        if (!Sentry.isEnabled()) return;

        TransactionOptions options = new TransactionOptions();
        options.setBindToScope(false);
        transaction = Sentry.startTransaction("FormFiller Session", "form-filler", options);
        transaction.setTag("target_url", targetUrl);
        transaction.setTag("browser_adapter", browserType);

        Breadcrumb bc = new Breadcrumb("Session started");
        bc.setCategory("formfiller.lifecycle");
        bc.setLevel(SentryLevel.INFO);
        bc.setData("target_url", targetUrl);
        bc.setData("browser_adapter", browserType);
        Sentry.addBreadcrumb(bc);
    }

    @Override
    public void traceBridgeInjected() {
        ITransaction tx = this.transaction;
        if (tx == null) return;

        ISpan span = tx.startChild("browser.bridge_inject", "JS bridge injected");
        span.finish(SpanStatus.OK);

        Breadcrumb bc = new Breadcrumb("Bridge injected");
        bc.setCategory("formfiller.bridge");
        bc.setLevel(SentryLevel.INFO);
        Sentry.addBreadcrumb(bc);
    }

    @Override
    public void traceMessageSent(String messageType, String messageId, String json) {
        ITransaction tx = this.transaction;
        if (tx == null) return;

        ISpan span = tx.startChild("message.send", messageType);
        span.setData("message_id", messageId);
        span.setData("message_type", messageType);
        span.setData("message_json", json);
        span.finish(SpanStatus.OK);

        Breadcrumb bc = new Breadcrumb("Message sent: " + messageType);
        bc.setCategory("formfiller.message.outbound");
        bc.setLevel(SentryLevel.INFO);
        bc.setData("message_id", messageId);
        bc.setData("message_type", messageType);
        bc.setData("message_json", json);
        Sentry.addBreadcrumb(bc);
    }

    @Override
    public void traceMessageReceived(String messageType, String messageId, String json) {
        ITransaction tx = this.transaction;
        if (tx == null) return;

        ISpan span = tx.startChild("message.receive", messageType);
        span.setData("message_id", messageId);
        span.setData("message_type", messageType);
        span.setData("message_json", json);
        span.finish(SpanStatus.OK);

        Breadcrumb bc = new Breadcrumb("Message received: " + messageType);
        bc.setCategory("formfiller.message.inbound");
        bc.setLevel(SentryLevel.INFO);
        bc.setData("message_id", messageId);
        bc.setData("message_type", messageType);
        bc.setData("message_json", json);
        Sentry.addBreadcrumb(bc);
    }

    @Override
    public void traceHandshakeReceived() {
        ITransaction tx = this.transaction;
        if (tx == null) return;

        ISpan span = tx.startChild("handshake.received", "SMART Web Messaging handshake");
        span.finish(SpanStatus.OK);

        Breadcrumb bc = new Breadcrumb("Handshake received");
        bc.setCategory("formfiller.handshake");
        bc.setLevel(SentryLevel.INFO);
        Sentry.addBreadcrumb(bc);
    }

    @Override
    public void traceFormSubmitted() {
        ITransaction tx = this.transaction;
        if (tx == null) return;

        ISpan span = tx.startChild("form.submitted", "Form submitted by user");
        span.finish(SpanStatus.OK);

        Breadcrumb bc = new Breadcrumb("Form submitted");
        bc.setCategory("formfiller.form");
        bc.setLevel(SentryLevel.INFO);
        Sentry.addBreadcrumb(bc);
    }

    @Override
    public void finishSession() {
        ITransaction tx = this.transaction;
        if (tx == null) return;
        tx.finish(SpanStatus.OK);
        this.transaction = null;
    }
}

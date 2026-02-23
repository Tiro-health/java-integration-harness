package health.tiro.formfiller.swing.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that creates the appropriate {@link FormFillerTracer} based on
 * whether Sentry is available on the classpath.
 * <p>
 * If {@code io.sentry:sentry} is present, returns a Sentry-backed tracer.
 * Otherwise, returns a no-op tracer with zero overhead.
 */
public final class FormFillerTracerFactory {

    private static final Logger logger = LoggerFactory.getLogger(FormFillerTracerFactory.class);

    private FormFillerTracerFactory() {}

    /**
     * Creates a tracer. Returns a Sentry-backed tracer if {@code io.sentry:sentry}
     * is on the classpath; otherwise returns a no-op.
     */
    public static FormFillerTracer create() {
        try {
            Class.forName("io.sentry.Sentry");
            logger.info("Sentry SDK detected on classpath, enabling FormFiller tracing");
            return new SentryFormFillerTracer();
        } catch (ClassNotFoundException e) {
            logger.debug("Sentry SDK not on classpath, tracing disabled");
            return NoOpFormFillerTracer.INSTANCE;
        }
    }
}

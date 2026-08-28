package health.tiro.sdc.compat;

import java.io.IOException;
import java.net.URI;

/**
 * How {@link SdcServerVersionProbe} issues its one GET.
 *
 * <p>A seam, not an abstraction for its own sake: the SDC client already owns a configured
 * Apache {@code HttpClient} (TLS, proxy, timeouts) and the probe must travel the same path as
 * the operations it guards, while the form filler owns no HTTP client at all and is better
 * served by the JDK default. One interface lets both be right.
 *
 * <p>Implementations must not throw for a non-success status — that is a
 * {@link SdcHttpResponse} with that status, which the probe reports as
 * {@link SdcVersionCheckOutcome#UNKNOWN}. Throw {@link IOException} only for transport
 * failures.
 */
@FunctionalInterface
public interface SdcHttpFetcher {

    /**
     * @param uri           absolute URI to GET
     * @param accept        value for the {@code Accept} header
     * @param timeoutMillis deadline for the whole exchange, connect and read
     * @param maxBytes      hard cap on the body read; a longer body may be truncated or
     *                      reported as a failure, but must never be buffered whole
     */
    SdcHttpResponse get(URI uri, String accept, int timeoutMillis, int maxBytes) throws IOException;
}

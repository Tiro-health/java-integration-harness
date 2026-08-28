package health.tiro.sdc.compat;

/** One HTTP response as {@link SdcServerVersionProbe} needs it: a status and a capped body. */
public final class SdcHttpResponse {

    private final int status;
    private final byte[] body;
    private final boolean truncated;

    /**
     * @param status    the HTTP status code
     * @param body      the response body, never {@code null} (use an empty array)
     * @param truncated whether the body hit the caller's cap and was cut short
     */
    public SdcHttpResponse(int status, byte[] body, boolean truncated) {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
        this.truncated = truncated;
    }

    public int getStatus() {
        return status;
    }

    public byte[] getBody() {
        return body;
    }

    /** True when the body exceeded the cap; the probe reports that rather than parsing a fragment. */
    public boolean isTruncated() {
        return truncated;
    }
}

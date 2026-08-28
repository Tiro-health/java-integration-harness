package health.tiro.sdc.compat;

/**
 * The outcome of one SDC-server version check, with enough detail to explain itself in a
 * customer's own logs — which is where this lands, since customers self-host the server.
 */
public final class SdcVersionCheckResult {

    // Longest server-reported string echoed into a message, a log line or a telemetry
    // breadcrumb. Without it, a server (or anything that can answer as one) could put its
    // choice of text into a log line on every form launch. A real version is under 20 chars.
    private static final int MAX_ECHOED_LENGTH = 64;

    // Backstop on the whole detail string, applied in both factories so the bound holds for
    // every caller rather than only where someone remembered to clamp an interpolated part.
    // Larger than MAX_ECHOED_LENGTH because a detail legitimately carries a URL and a status
    // alongside any echoed text.
    private static final int MAX_DETAIL_LENGTH = 512;

    private final SdcVersionCheckOutcome outcome;
    private final String reportedVersion;
    private final String detail;

    private SdcVersionCheckResult(SdcVersionCheckOutcome outcome, String reportedVersion, String detail) {
        this.outcome = outcome;
        this.reportedVersion = reportedVersion;
        this.detail = detail;
    }

    /**
     * The verdict for a version the server actually reported, evaluated against
     * {@link SdcCompatibility#minimumSdcVersion()}. A string outside the version grammar (a
     * {@code dev} build, a PR checkpoint id) yields {@link SdcVersionCheckOutcome#UNKNOWN},
     * never {@link SdcVersionCheckOutcome#TOO_OLD}.
     */
    public static SdcVersionCheckResult fromReportedVersion(String reportedVersion) {
        SdcVersionCheckOutcome outcome = SdcCompatibility.evaluate(reportedVersion);
        String clamped = clamp(reportedVersion);
        String detail = outcome == SdcVersionCheckOutcome.UNKNOWN
                ? "The server reported '" + clamped + "', which is not a release version "
                    + "(dev builds report 'dev', PR builds a checkpoint id, a server with no version.json "
                    + "'development')."
                : "";
        return new SdcVersionCheckResult(outcome, clamped, truncate(detail, MAX_DETAIL_LENGTH));
    }

    /**
     * The verdict when no version could be read at all — unreachable server, timeout,
     * non-success status, a body without the version field, or a document that could not be
     * attributed to the SDC server. Always {@link SdcVersionCheckOutcome#UNKNOWN}, i.e. fails
     * open.
     *
     * @param detail why, for the customer's logs
     */
    public static SdcVersionCheckResult unavailable(String detail) {
        return new SdcVersionCheckResult(
                SdcVersionCheckOutcome.UNKNOWN, null,
                truncate(detail == null ? "" : detail, MAX_DETAIL_LENGTH));
    }

    /** The verdict. See {@link SdcVersionCheckOutcome} for the failure semantics. */
    public SdcVersionCheckOutcome getOutcome() {
        return outcome;
    }

    /**
     * The version string the server reported (truncated if absurdly long), or {@code null}
     * when no version could be read at all. Non-null with
     * {@link SdcVersionCheckOutcome#UNKNOWN} means a version was reported but fell outside the
     * grammar (e.g. {@code dev}).
     */
    public String getReportedVersion() {
        return reportedVersion;
    }

    /**
     * Why the outcome is what it is, for logs: the failing status code, the transport error, or
     * the unrecognized version string. Never {@code null}.
     */
    public String getDetail() {
        return detail;
    }

    /** The floor this was evaluated against. */
    public String getMinimumVersion() {
        return SdcCompatibility.minimumSdcVersion();
    }

    /** Truncates a server-supplied string to a length that is safe to put in a log line. */
    static String clamp(String value) {
        return truncate(value, MAX_ECHOED_LENGTH);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;

        // Never cut between the halves of a surrogate pair: a lone surrogate is not a valid
        // string, and this text goes on to be serialized (into a Sentry breadcrumb, among other
        // places) by code entitled to assume it is.
        int length = maxLength;
        if (Character.isHighSurrogate(value.charAt(length - 1))) length--;
        return value.substring(0, length) + "…";
    }

    /** A single line naming the outcome, both versions, and the source. */
    @Override
    public String toString() {
        switch (outcome) {
            case SATISFIED:
                return "SDC server version " + reportedVersion + " satisfies the minimum "
                        + getMinimumVersion() + " (read from CapabilityStatement.software.version).";
            case TOO_OLD:
                return "SDC server version " + reportedVersion + " is older than the minimum "
                        + getMinimumVersion() + " required by this harness "
                        + "(read from CapabilityStatement.software.version).";
            default:
                return "SDC server version could not be established (minimum required: "
                        + getMinimumVersion() + "). " + detail;
        }
    }
}

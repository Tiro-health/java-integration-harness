package health.tiro.sdc.compat;

/** The verdict of an SDC-server version check. */
public enum SdcVersionCheckOutcome {

    /** The server reported a version at or above {@link SdcCompatibility#minimumSdcVersion()}. */
    SATISFIED,

    /**
     * The server reported a version below {@link SdcCompatibility#minimumSdcVersion()}.
     * Reported as an actionable warning — upgrade the server — and nothing is refused; see
     * {@link SdcCompatibility#minimumSdcVersion()} for when that changes.
     */
    TOO_OLD,

    /**
     * The version could not be established — unreachable server, timeout, non-success status,
     * a body without the version field, a document that isn't the SDC server's, or a version
     * string outside the grammar (a {@code dev} build, a PR checkpoint id). A diagnostic about
     * the check rather than about the server, and distinct from {@link #TOO_OLD}, which is
     * actionable.
     */
    UNKNOWN
}

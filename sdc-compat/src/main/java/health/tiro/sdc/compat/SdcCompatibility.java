package health.tiro.sdc.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SDC-server compatibility contract this harness release ships: the minimum server version
 * it supports, and the grammar and comparison rules used to decide whether a server meets it.
 * Pure — no I/O; see {@link SdcServerVersionProbe} for the check that reads a live server.
 *
 * <p>The harness embeds the web-sdk bundle it was validated against (GH-24), so the SDC server
 * is the only component that can still change underneath a frozen release — customers run and
 * upgrade their own instance. This class is the second and last number in the integrator
 * story: <em>pin the harness artifact; run an SDC server at or above
 * {@link #minimumSdcVersion()}</em>.
 */
public final class SdcCompatibility {

    // The floor itself. Deliberately private, read through minimumSdcVersion(): a public
    // static final String initialised with a literal is a compile-time constant, which javac
    // copies into every consuming class at THAT class's compile time — so a host printing it
    // to tell support which floor applies would keep printing the floor it was built against
    // while this artifact enforced a newer one. Two copies of a version number drifting apart
    // is the failure this class exists to prevent.
    private static final String MINIMUM_SDC_VERSION = "v0.9.39";

    /**
     * The oldest SDC server version this harness release supports. A server below it is
     * reported as an actionable warning rather than refused — see "Why this reports and does
     * not refuse" below. Raise it in lockstep
     * with the release notes whenever the harness starts to depend on newer server behaviour.
     *
     * <p>{@code v0.9.39} is the release that first answers {@code {base}/metadata}, which is
     * how the version is read at all — so it is the honest statement of the requirement: an
     * SDC server new enough to declare itself. It also means the gate cannot yet
     * <em>refuse</em> anything: every server able to answer the probe is at or above this
     * floor by construction, and an older one reads as {@link SdcVersionCheckOutcome#UNKNOWN}
     * and is let through.
     *
     * <p><b>Why this reports and does not refuse.</b> Enforcement and the floor live in the
     * same artifact, so they always reach an integrator together: nobody is protected by
     * having a refusal fielded early, because a deployment that has not adopted the release
     * carrying a raised floor has not adopted its enforcement either. Meanwhile no reachable
     * server is below the current floor, so a refusal could only ever fire on a mistake — a
     * mis-read version in a binary that cannot be patched, which is an unrecoverable outage in
     * exchange for protection against nothing. Arm it in the release that raises the floor,
     * next to the reason it was raised.
     */
    public static String minimumSdcVersion() {
        return MINIMUM_SDC_VERSION;
    }

    /**
     * The version string the SDC server reports. It is <b>not</b> plain semver: it comes from
     * {@code APP_VERSION}, which the deploy pipelines set to the git tag, so it is
     * {@code v}-prefixed and routinely carries a prerelease suffix ({@code v0.9.38-rc.0}). Dev
     * builds report {@code dev}, PR builds a checkpoint id, and a server with no
     * {@code version.json} reports {@code development} — none of which match, and all of which
     * are therefore treated as "unknown" rather than "too old".
     *
     * <p>Hand-rolled rather than delegating to a semver library this harness would otherwise
     * not depend on.
     */
    private static final Pattern VERSION_GRAMMAR =
        Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");

    private SdcCompatibility() {}

    /**
     * The numeric triple of a reported version, or {@code null} for anything outside the
     * grammar — {@code dev}, {@code development}, a PR checkpoint id, a two-part version, an
     * out-of-range number. Callers must treat {@code null} as "unknown", never as "too old".
     */
    static int[] parseVersion(String value) {
        if (value == null) return null;
        Matcher match = VERSION_GRAMMAR.matcher(value.trim());
        if (!match.matches()) return null;
        try {
            return new int[] {
                Integer.parseInt(match.group(1)),
                Integer.parseInt(match.group(2)),
                Integer.parseInt(match.group(3)),
            };
        } catch (NumberFormatException overflow) {
            // An absurdly long digit run matches the grammar and fails to parse. Unknown, which
            // is the safe side.
            return null;
        }
    }

    /**
     * Evaluates a reported server version against {@link #minimumSdcVersion()}.
     *
     * <p><b>Prerelease rule (decided, not inherited from a parser):</b> only
     * {@code (major, minor, patch)} is compared — the {@code -rc.N} / {@code +build} suffix is
     * ignored on both sides. So {@code v0.9.38-rc.0} <em>satisfies</em> a minimum of
     * {@code v0.9.38}, which is laxer than semver, where a prerelease sorts below its release.
     * That is deliberate: the production deploy accepts any tag, so an rc can legitimately
     * reach a customer, and failing closed there would brick a deployment that almost
     * certainly does have the feature.
     */
    public static SdcVersionCheckOutcome evaluate(String reportedVersion) {
        int[] reported = parseVersion(reportedVersion);
        if (reported == null) return SdcVersionCheckOutcome.UNKNOWN;

        // A typo in the floor constant would otherwise brick every deployment. Unit-tested to
        // parse, but treated as unreadable rather than failing closed if that test ever stops
        // running.
        int[] floor = parseVersion(MINIMUM_SDC_VERSION);
        if (floor == null) return SdcVersionCheckOutcome.UNKNOWN;

        for (int i = 0; i < 3; i++) {
            if (reported[i] != floor[i]) {
                return reported[i] > floor[i]
                        ? SdcVersionCheckOutcome.SATISFIED
                        : SdcVersionCheckOutcome.TOO_OLD;
            }
        }
        return SdcVersionCheckOutcome.SATISFIED;
    }
}

package health.tiro.sdc.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static health.tiro.sdc.compat.SdcVersionCheckOutcome.SATISFIED;
import static health.tiro.sdc.compat.SdcVersionCheckOutcome.TOO_OLD;
import static health.tiro.sdc.compat.SdcVersionCheckOutcome.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SdcCompatibilityTest {

    /**
     * The floor must parse. If it ever doesn't, {@code evaluate} degrades to UNKNOWN for every
     * server rather than failing closed — safe, but silently useless, so it is worth a test.
     */
    @Test
    void theFloorItselfParses() {
        assertNotNull(SdcCompatibility.parseVersion(SdcCompatibility.minimumSdcVersion()));
        assertEquals(SATISFIED, SdcCompatibility.evaluate(SdcCompatibility.minimumSdcVersion()));
    }

    @Test
    void comparesComponentwise() {
        assertEquals(TOO_OLD, SdcCompatibility.evaluate("v0.9.38"));
        assertEquals(SATISFIED, SdcCompatibility.evaluate("v0.9.39"));
        assertEquals(SATISFIED, SdcCompatibility.evaluate("v0.9.40"));
        assertEquals(SATISFIED, SdcCompatibility.evaluate("v0.10.0"));
        assertEquals(SATISFIED, SdcCompatibility.evaluate("v1.0.0"));
        assertEquals(TOO_OLD, SdcCompatibility.evaluate("v0.8.99"));
    }

    /** The v prefix comes from the git tag; a server could equally report it without one. */
    @Test
    void thePrefixIsOptional() {
        assertEquals(SATISFIED, SdcCompatibility.evaluate("0.9.39"));
        assertEquals(TOO_OLD, SdcCompatibility.evaluate("0.9.38"));
    }

    /**
     * Laxer than semver on purpose: production accepts any tag, so an rc can legitimately
     * reach a customer, and failing it would brick a deployment that almost certainly has the
     * feature.
     */
    @Test
    void aPrereleaseSatisfiesItsOwnRelease() {
        assertEquals(SATISFIED, SdcCompatibility.evaluate("v0.9.39-rc.0"));
        assertEquals(SATISFIED, SdcCompatibility.evaluate("v0.9.40+build.7"));
        assertEquals(TOO_OLD, SdcCompatibility.evaluate("v0.9.38-rc.9"));
    }

    /** Everything outside the grammar is UNKNOWN — never TOO_OLD, which would condemn it. */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "development", "0.9", "v0.9.39.1", "pr-1234-abcdef", "", "   ",
                            "v99999999999999999999.0.0"})
    void anythingOutsideTheGrammarIsUnknown(String reported) {
        assertEquals(UNKNOWN, SdcCompatibility.evaluate(reported));
    }

    @Test
    void aNullVersionIsUnknown() {
        assertEquals(UNKNOWN, SdcCompatibility.evaluate(null));
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        assertEquals(SATISFIED, SdcCompatibility.evaluate("  v0.9.39\n"));
    }
}

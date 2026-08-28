package health.tiro.sdc.compat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdcServerVersionProbeTest {

    private static final String SDC_NAME = "Tiro.health SDC Server";

    private final List<URI> requested = new ArrayList<>();

    @Test
    void readsTheVersionFromAnAttributedCapabilityStatement() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                ok(capabilityStatement(SDC_NAME, "v0.9.39")));

        assertEquals(SdcVersionCheckOutcome.SATISFIED, result.getOutcome());
        assertEquals("v0.9.39", result.getReportedVersion());
        assertEquals(URI.create("https://host/fhir/r5/metadata"), requested.get(0));
    }

    /** A base with a trailing slash must not lose its last segment when `metadata` is appended. */
    @Test
    void appendsToTheBaseRatherThanReplacingItsLastSegment() {
        probe("https://gw.example/tiro-sdc/fhir/r5", ok(capabilityStatement(SDC_NAME, "v0.9.39")));
        assertEquals(URI.create("https://gw.example/tiro-sdc/fhir/r5/metadata"), requested.get(0));

        requested.clear();
        probe("https://gw.example/tiro-sdc/fhir/r5/", ok(capabilityStatement(SDC_NAME, "v0.9.39")));
        assertEquals(URI.create("https://gw.example/tiro-sdc/fhir/r5/metadata"), requested.get(0));
    }

    @Test
    void reportsAServerBelowTheFloorAsTooOld() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                ok(capabilityStatement(SDC_NAME, "v0.9.38")));

        assertEquals(SdcVersionCheckOutcome.TOO_OLD, result.getOutcome());
        assertTrue(result.toString().contains("older than the minimum"), result.toString());
    }

    /**
     * The attribution guard. On a server predating the metadata route the request falls into
     * the data tunnel and the hospital's own FHIR server answers — a document with a real,
     * parseable version that says nothing about the SDC server.
     */
    @Test
    void refusesToUseAVersionItCannotAttribute() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                ok(capabilityStatement("HAPI FHIR Server", "0.1.0")));

        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
        assertNull(result.getReportedVersion());
        assertTrue(result.getDetail().contains("HAPI FHIR Server"), result.getDetail());
    }

    /** software.name is 1..1 when software is present, so an absent one is not a conformant server. */
    @Test
    void refusesADocumentWithNoSoftwareName() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                ok("{\"resourceType\":\"CapabilityStatement\",\"software\":{\"version\":\"v9.9.9\"}}"));

        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
        assertTrue(result.getDetail().contains("absent"), result.getDetail());
    }

    /** A cosmetic capitalization change on the server must not disarm every shipped binary. */
    @Test
    void attributionIsCaseInsensitiveAndTrimmed() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                ok(capabilityStatement("  tiro.HEALTH sdc server  ", "v0.9.39")));

        assertEquals(SdcVersionCheckOutcome.SATISFIED, result.getOutcome());
    }

    @Test
    void aNonSuccessStatusIsUnknown() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                (uri, accept, timeout, max) -> {
                    requested.add(uri);
                    return new SdcHttpResponse(400, "nope".getBytes(StandardCharsets.UTF_8), false);
                });

        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
        assertTrue(result.getDetail().contains("400"), result.getDetail());
    }

    @Test
    void aTransportFailureIsUnknownRatherThanAnException() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                (uri, accept, timeout, max) -> { throw new IOException("connect timed out"); });

        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
        assertTrue(result.getDetail().contains("connect timed out"), result.getDetail());
    }

    /** A defect in a supplied fetcher must not become a new way for a form launch to die. */
    @Test
    void aBrokenFetcherIsUnknownRatherThanAnException() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                (uri, accept, timeout, max) -> { throw new IllegalStateException("bad handler"); });

        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
        assertTrue(result.getDetail().contains("bad handler"), result.getDetail());
    }

    @Test
    void aTruncatedBodyIsUnknownRatherThanParsedAsAFragment() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                (uri, accept, timeout, max) ->
                        new SdcHttpResponse(200, "{\"software\":".getBytes(StandardCharsets.UTF_8), true));

        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
        assertTrue(result.getDetail().contains("exceeded"), result.getDetail());
    }

    @Test
    void aMalformedBodyIsUnknown() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5", ok("not json at all"));
        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
    }

    /** A proxy or hand-written server can prepend a BOM; Jackson would otherwise reject it. */
    @Test
    void toleratesAByteOrderMark() {
        byte[] body = ("﻿" + capabilityStatement(SDC_NAME, "v0.9.39")).getBytes(StandardCharsets.UTF_8);
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                (uri, accept, timeout, max) -> new SdcHttpResponse(200, body, false));

        assertEquals(SdcVersionCheckOutcome.SATISFIED, result.getOutcome());
    }

    /** A dev build reports "dev": unknown, not too old, and allowed through. */
    @Test
    void aDevBuildIsUnknownNotTooOld() {
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                ok(capabilityStatement(SDC_NAME, "dev")));

        assertEquals(SdcVersionCheckOutcome.UNKNOWN, result.getOutcome());
        assertEquals("dev", result.getReportedVersion());
        assertTrue(result.getDetail().contains("not a release version"), result.getDetail());
    }

    /** A server-supplied string reaches a log line, so it is bounded before it gets there. */
    @Test
    void clampsAnAbsurdlyLongReportedVersion() {
        StringBuilder absurd = new StringBuilder();
        for (int i = 0; i < 500; i++) absurd.append('x');
        SdcVersionCheckResult result = probe("https://host/fhir/r5",
                ok(capabilityStatement(SDC_NAME, absurd.toString())));

        assertTrue(result.getReportedVersion().length() <= 65, result.getReportedVersion());
        assertTrue(result.getDetail().length() <= 512);
    }

    @Test
    void rejectsARelativeBaseAsACallerBug() {
        assertThrows(IllegalArgumentException.class,
                () -> SdcServerVersionProbe.check(URI.create("/fhir/r5"), ok("{}")));
    }

    // ---- helpers ----

    private SdcVersionCheckResult probe(String base, SdcHttpFetcher fetcher) {
        return SdcServerVersionProbe.check(URI.create(base), fetcher);
    }

    private SdcHttpFetcher ok(String body) {
        return (uri, accept, timeout, max) -> {
            requested.add(uri);
            assertEquals("application/fhir+json", accept);
            return new SdcHttpResponse(200, body.getBytes(StandardCharsets.UTF_8), false);
        };
    }

    private static String capabilityStatement(String softwareName, String version) {
        return "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\",\"software\":{"
                + "\"name\":\"" + softwareName + "\",\"version\":\"" + version + "\"}}";
    }
}

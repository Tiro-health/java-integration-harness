package health.tiro.formfiller.swing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three guards standing behind the committed {@code @tiro-health/web-sdk} bundle (GH-24).
 * The bundle used to be gitignored and fetched from GitHub Packages, which made npm's
 * {@code integrity} the guarantee that the bytes matched the pin; committing it gave that away,
 * and these checks buy it back. They are the Maven equivalent of the .NET csproj target — no
 * network, so they run on every {@code mvn test} and on a fresh clone.
 */
class WebSdkAssetsTest {

    private static final Pattern PINNED_VERSION = Pattern.compile(
        "\"@tiro-health/web-sdk\"\\s*:\\s*\"([^\"]+)\"");

    /** Guard 1: the bundle is actually in the jar, so `git clone && mvn package` ships an SDK. */
    @Test
    void bundleIsOnTheClasspath() {
        assertTrue(WebSdkAssets.getVersion().matches("\\d+\\.\\d+\\.\\d+.*"),
            "web-sdk.version.json should carry an exact version");
        Path extracted = WebSdkAssets.getFolder().resolve(WebSdkAssets.getBundleFileName());
        assertTrue(Files.isRegularFile(extracted), "bundle should extract to " + extracted);
        assertTrue(WebSdkAssets.getBundleUrl().startsWith("file:"),
            "the bridge loads the bundle over file://, not from a CDN");
    }

    /**
     * Guard 2: the staged manifest matches the pin. Bumping {@code build/web-sdk/package.json}
     * without re-running {@code copy-bundle.mjs} would otherwise ship the previous release's
     * bundle at a URL naming the new version — a stale pairing nothing downstream could catch.
     * This is also why a Dependabot bump arrives red: the bot edits the pin and nothing else.
     */
    @Test
    void stagedManifestMatchesThePin() throws IOException {
        Path pinFile = Paths.get("..", "build", "web-sdk", "package.json");
        assertTrue(Files.isRegularFile(pinFile), "pin file not found at " + pinFile.toAbsolutePath());

        Matcher pin = PINNED_VERSION.matcher(
            new String(Files.readAllBytes(pinFile), StandardCharsets.UTF_8));
        assertTrue(pin.find(), "build/web-sdk/package.json must pin @tiro-health/web-sdk");

        assertEquals(pin.group(1), WebSdkAssets.getVersion(),
            "the staged bundle does not match the pin — run "
                + "`cd build/web-sdk && npm ci --ignore-scripts && node copy-bundle.mjs`");
    }

    /**
     * Guard 3: the manifest's sha256 matches the actual bytes. The manifest and the bundle are
     * separate files, so a merge can take one from each side and leave a self-consistent lie —
     * pin and manifest agree while the bytes are a different release's, or (with the
     * {@code .gitattributes} entry removed) a CRLF-rewritten copy of the right one.
     */
    @Test
    void manifestHashMatchesTheBundleBytes() throws IOException {
        Path staged = Paths.get(
            "src", "main", "resources", "health", "tiro", "formfiller", "swing", "tiro-web-sdk.iife.js");
        assertTrue(Files.isRegularFile(staged), "staged bundle not found at " + staged.toAbsolutePath());

        assertEquals(WebSdkAssets.getSha256(), WebSdkAssets.sha256(Files.readAllBytes(staged)),
            "the staged bundle's bytes do not match web-sdk.version.json — re-run "
                + "build/web-sdk/copy-bundle.mjs, and check that .gitattributes still marks the "
                + "bundle `binary`");
    }
}

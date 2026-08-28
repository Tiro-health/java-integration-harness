package health.tiro.formfiller.swing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The embedded {@code @tiro-health/web-sdk} bundle (GH-24). The harness ships the exact SDK it
 * was validated against — pinned in {@code build/web-sdk/package.json}, staged into this
 * module's resources by {@code build/web-sdk/copy-bundle.mjs} — extracts it to a temp folder
 * and lets the bridge load it from there. The page carries no SDK script tag: the SDK version
 * is not an integrator or deployment choice, so bridge&harr;element skew cannot happen.
 *
 * <p>Everything here is derived from {@code web-sdk.version.json}, which is generated at
 * staging time and never hand-written, so the version in the file name provably describes the
 * bytes served.
 */
public final class WebSdkAssets {

    private static final String BUNDLE_RESOURCE =
        "health/tiro/formfiller/swing/tiro-web-sdk.iife.js";
    private static final String MANIFEST_RESOURCE =
        "health/tiro/formfiller/swing/web-sdk.version.json";

    // Two fields, no dependency on a JSON parser: this module's only compile dependency that
    // could parse it is HAPI's Jackson, and reaching for it here to read 96 bytes written by
    // our own build step would be the wrong trade.
    private static final Pattern VERSION_FIELD =
        Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SHA256_FIELD =
        Pattern.compile("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"");

    private static volatile String cachedVersion;
    private static volatile String cachedSha256;
    private static volatile String cachedBundleUrl;
    private static volatile Path cachedFolder;

    private WebSdkAssets() {}

    /**
     * The embedded bundle's package version, e.g. {@code 0.3.3}. Generated at staging time
     * from the installed package, never hand-written.
     */
    public static String getVersion() {
        readManifest();
        return cachedVersion;
    }

    /** The sha256 the staging step recorded for the embedded bundle's bytes. */
    public static String getSha256() {
        readManifest();
        return cachedSha256;
    }

    /**
     * The file name the bundle is served under. Versioned on purpose: the bytes change with
     * every pin bump, and Chromium caches by URL, so a constant name could let an upgraded
     * harness keep running the previous release's bundle — exactly the skew embedding exists
     * to prevent. Cache-busting by URL prevents that rather than detecting it.
     */
    public static String getBundleFileName() {
        return "tiro-web-sdk." + getVersion() + ".iife.js";
    }

    /**
     * The {@code file://} URL the bridge loads the bundle from, extracting it on first call.
     *
     * @throws IllegalStateException if the bundle is missing from the jar, cannot be written
     *                               to the temp folder, or does not match the manifest hash
     */
    public static String getBundleUrl() {
        if (cachedBundleUrl != null) return cachedBundleUrl;
        synchronized (WebSdkAssets.class) {
            if (cachedBundleUrl == null) {
                cachedBundleUrl = extract().resolve(getBundleFileName()).toUri().toString();
            }
            return cachedBundleUrl;
        }
    }

    /**
     * The folder holding the extracted bundle. {@link DefaultPageLoader} writes the generated
     * page here too, so the page and the SDK it loads are same-directory {@code file://}
     * siblings.
     */
    static Path getFolder() {
        if (cachedFolder != null) return cachedFolder;
        synchronized (WebSdkAssets.class) {
            if (cachedFolder == null) cachedFolder = extract();
            return cachedFolder;
        }
    }

    // Content-addressed folder: version plus a hash prefix. Version alone would be enough for
    // a release, but not during development of the bundle itself, where the version can stay
    // put while the bytes change; keying on the hash makes a stale extraction impossible to
    // reuse rather than something to remember to clear.
    private static Path extract() {
        readManifest();
        Path folder = Paths.get(System.getProperty("java.io.tmpdir"))
                .resolve("tiro-form-filler")
                .resolve("web-sdk-" + cachedVersion + "-" + cachedSha256.substring(0, 12));
        Path target = folder.resolve(getBundleFileName());

        try {
            // Warm path: the folder name carries the hash and the publish below is atomic, so
            // an existing target in this folder is, by construction, the bundle we would write.
            // Existence alone is the check — a warm start never reads or digests 6 MB.
            if (!Files.isRegularFile(target)) {
                byte[] bundle = readResource(BUNDLE_RESOURCE);
                verifyHash(bundle);
                Files.createDirectories(folder);
                // Write to a unique name and move into place, so a second process navigating
                // to `target` never observes a partially written bundle.
                Path temp = folder.resolve(UUID.randomUUID() + ".tmp");
                Files.write(temp, bundle);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailed) {
                    // Lost a race to another writer. Their bytes are ours — the folder name is
                    // the hash — so the existing target is correct.
                    Files.deleteIfExists(temp);
                    if (!Files.isRegularFile(target)) throw moveFailed;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to extract the embedded tiro-web-sdk bundle to " + folder, e);
        }
        return folder;
    }

    // Guards the one thing the staging-time check cannot: bytes that changed after the jar was
    // built (a repacked jar, a truncated download, a corrupt classpath entry). Costs a single
    // digest of 6 MB, and only on a cold extraction.
    private static void verifyHash(byte[] bundle) {
        String actual = sha256(bundle);
        if (!actual.equals(cachedSha256)) {
            throw new IllegalStateException(
                "The embedded tiro-web-sdk bundle does not match web-sdk.version.json: expected sha256 "
                    + cachedSha256 + ", found " + actual
                    + ". The jar's web assets have been modified or repacked.");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform", e);
        }
    }

    private static void readManifest() {
        if (cachedVersion != null) return;
        synchronized (WebSdkAssets.class) {
            if (cachedVersion != null) return;
            String manifest;
            try {
                manifest = new String(readResource(MANIFEST_RESOURCE), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + MANIFEST_RESOURCE, e);
            }
            Matcher version = VERSION_FIELD.matcher(manifest);
            Matcher sha256 = SHA256_FIELD.matcher(manifest);
            if (!version.find() || !sha256.find()) {
                throw new IllegalStateException(
                    "web-sdk.version.json carries no version/sha256 pair — the staged bundle metadata is "
                        + "corrupt; re-run build/web-sdk/copy-bundle.mjs.");
            }
            cachedSha256 = sha256.group(1);
            cachedVersion = version.group(1);
        }
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream is = WebSdkAssets.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException(
                    "The embedded tiro-web-sdk is not on the classpath: " + path
                        + ". A build from source needs `cd build/web-sdk && npm ci --ignore-scripts && node copy-bundle.mjs`.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(is.available(), 8192));
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) > 0) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }
}

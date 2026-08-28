package health.tiro.formfiller.swing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads the SWM bridge JavaScript from the classpath.
 * The script is cached after first load.
 *
 * <p>The returned source is prefixed with a preamble defining {@code window.__tiroSdkUrl} —
 * the {@code file://} URL of the embedded {@code @tiro-health/web-sdk} bundle, which the
 * bridge injects into the page (GH-24). The bridge is a static asset and cannot know the
 * version in that file name, so the host supplies it here: this is the one place both browser
 * adapters go through, so neither can forget it.
 */
public final class BridgeScriptLoader {

    private static final String RESOURCE_PATH =
        "health/tiro/formfiller/swing/tiro-swm-bridge.js";

    private static volatile String cachedScript;

    private BridgeScriptLoader() {}

    /**
     * Returns the bridge JS source code (preceded by the SDK-URL preamble), loading from
     * classpath on first call.
     */
    public static String getScript() {
        if (cachedScript != null) return cachedScript;
        synchronized (BridgeScriptLoader.class) {
            if (cachedScript != null) return cachedScript;
            try (InputStream is = BridgeScriptLoader.class.getClassLoader()
                    .getResourceAsStream(RESOURCE_PATH)) {
                if (is == null) {
                    throw new IllegalStateException(
                        "Bridge script not found on classpath: " + RESOURCE_PATH);
                }
                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                cachedScript = sdkUrlPreamble() + sb;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load bridge script", e);
            }
            return cachedScript;
        }
    }

    // A file:// URL produced by Path.toUri() contains no quote, backslash or newline that
    // needs escaping, but the JSON-ish string literal is built defensively anyway: this text
    // is evaluated as JavaScript in the page.
    private static String sdkUrlPreamble() {
        String url = WebSdkAssets.getBundleUrl()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "")
                .replace("\r", "");
        return "window.__tiroSdkUrl = \"" + url + "\";\n";
    }
}

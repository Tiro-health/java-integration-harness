package health.tiro.formfiller.swing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the default form-filler HTML template from the classpath,
 * replaces placeholders, writes it to a temp file, and returns a {@code file://} URI.
 */
public final class DefaultPageLoader {

    private static final String RESOURCE_PATH =
        "health/tiro/formfiller/swing/default-form-filler.html";

    private static volatile String cachedUri;
    private static volatile String cachedKey;

    private DefaultPageLoader() {}

    /**
     * Creates (or reuses) a temp HTML file with the given configuration baked in.
     *
     * @param sdcEndpointAddress  the SDC FHIR endpoint URL
     * @param dataEndpointAddress the FHIR data endpoint URL (nullable)
     * @param sdkUrl              the Tiro Web SDK script URL
     * @return a {@code file://} URI pointing to the generated HTML file
     */
    public static String createPage(String sdcEndpointAddress, String dataEndpointAddress, String sdkUrl) {
        String key = sdcEndpointAddress + "|" + dataEndpointAddress + "|" + sdkUrl;
        if (key.equals(cachedKey) && cachedUri != null) {
            return cachedUri;
        }
        synchronized (DefaultPageLoader.class) {
            if (key.equals(cachedKey) && cachedUri != null) {
                return cachedUri;
            }
            String html = loadTemplate();
            html = html.replace("{{sdcEndpointAddress}}", sdcEndpointAddress);
            String dataAttr = dataEndpointAddress != null && !dataEndpointAddress.trim().isEmpty()
                    ? " data-endpoint-address=\"" + dataEndpointAddress + "\""
                    : "";
            html = html.replace("{{dataEndpointAddressAttr}}", dataAttr);
            html = html.replace("{{sdkUrl}}", sdkUrl);
            try {
                Path tempFile = Files.createTempFile("tiro-form-filler-", ".html");
                tempFile.toFile().deleteOnExit();
                Files.write(tempFile, html.getBytes(StandardCharsets.UTF_8));
                cachedKey = key;
                cachedUri = tempFile.toUri().toString();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write default form-filler HTML to temp file", e);
            }
            return cachedUri;
        }
    }

    private static String loadTemplate() {
        try (InputStream is = DefaultPageLoader.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Default form-filler HTML not found on classpath: " + RESOURCE_PATH);
            }
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load default form-filler HTML", e);
        }
    }
}

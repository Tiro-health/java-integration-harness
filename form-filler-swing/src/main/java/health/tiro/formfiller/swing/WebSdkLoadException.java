package health.tiro.formfiller.swing;

/**
 * The page is not running the harness's embedded {@code @tiro-health/web-sdk} (GH-24): it
 * loaded its own copy ({@code collision}) or the embedded bundle failed to load
 * ({@code error}).
 *
 * <p>Terminal for this {@link FormFiller}: {@link FormFiller#waitForHandshake()} fails with
 * it, queued outbound messages fail with it, and {@link FormFillerListener#onWebSdkLoadFailed}
 * is called. Fix the page or the environment and create a new viewer.
 */
public class WebSdkLoadException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /** {@code "collision"} or {@code "error"}, as reported by the bridge at handshake. */
    public String getReason() {
        return reason;
    }

    WebSdkLoadException(String reason) {
        super(describe(reason));
        this.reason = reason;
    }

    private static String describe(String reason) {
        if ("collision".equals(reason)) {
            return "The page loads its own tiro-web-sdk copy. Remove the tiro-web-sdk <script> tag "
                + "from your page — the harness embeds and serves the validated copy it was built "
                + "against (GH-24), and running two is how the bridge and the element drift apart.";
        }
        return "The embedded tiro-web-sdk failed to load in the page, so the form cannot render. "
            + "A file:// script only loads into a file:// document, so a targetUrl served over "
            + "http(s) cannot run it — use the generated page (sdcEndpointAddress) or a local "
            + "page. Otherwise check for policy or antivirus software blocking the temp folder.";
    }
}

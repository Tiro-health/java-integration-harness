package health.tiro.formfiller.swing;

import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Listener for {@link FormFiller} events.
 * All methods have default no-op implementations — override only what you need.
 *
 * <p>FHIR resources are returned as {@link IBaseResource} (version-agnostic).
 * Cast to your version's concrete types:
 * <pre>{@code
 * viewer.addFormFillerListener(new FormFillerListener() {
 *     @Override
 *     public void onFormSubmitted(IBaseResource response, IBaseResource outcome) {
 *         QuestionnaireResponse qr = (QuestionnaireResponse) response;
 *         // process
 *     }
 * });
 * }</pre>
 */
public interface FormFillerListener {

    /**
     * Called when the JS page completes the SMART Web Messaging handshake.
     */
    default void onHandshakeReceived() {}

    /**
     * Called when the user submits a form in the browser.
     *
     * @param response the submitted QuestionnaireResponse (as IBaseResource)
     * @param outcome  the OperationOutcome (as IBaseResource), may be null
     */
    default void onFormSubmitted(IBaseResource response, IBaseResource outcome) {}

    /**
     * Called when the browser app requests to close (ui.done message).
     */
    default void onCloseRequested() {}

    /**
     * Called when the page is not running the harness's embedded {@code tiro-web-sdk} — it
     * loaded its own copy, or the embedded bundle failed to load. The session is refused:
     * {@link FormFiller#waitForHandshake()} fails with the same exception, and
     * {@link #onHandshakeReceived()} is not called.
     *
     * <p>Override to surface it in your UI; a host that awaits the handshake already sees it
     * there and needs nothing here.
     *
     * @param error why the page was refused; {@code getReason()} is {@code "collision"} or
     *              {@code "error"}
     */
    default void onWebSdkLoadFailed(WebSdkLoadException error) {}
}

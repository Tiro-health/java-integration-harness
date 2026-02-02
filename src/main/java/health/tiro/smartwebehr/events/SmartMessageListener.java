package health.tiro.smartwebehr.events;

/**
 * Listener interface for SMART Web Messaging events.
 */
public interface SmartMessageListener {

    /**
     * Called when a form is submitted.
     */
    default void onFormSubmitted(FormSubmittedEvent event) {}

    /**
     * Called when the application should be closed (ui.done received).
     */
    default void onCloseApplication(CloseApplicationEvent event) {}

    /**
     * Called when a handshake message is received.
     */
    default void onHandshakeReceived(HandshakeReceivedEvent event) {}
}

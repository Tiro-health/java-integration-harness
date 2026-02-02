package health.tiro.smartwebehr.events;

import com.fasterxml.jackson.databind.JsonNode;
import health.tiro.smartwebehr.message.SmartMessageRequest;

import java.util.EventObject;

/**
 * Event fired when a handshake message is received.
 */
public class HandshakeReceivedEvent extends EventObject {
    
    private final SmartMessageRequest message;
    private final JsonNode payload;

    public HandshakeReceivedEvent(Object source, SmartMessageRequest message, JsonNode payload) {
        super(source);
        this.message = message;
        this.payload = payload;
    }

    public SmartMessageRequest getMessage() {
        return message;
    }

    public JsonNode getPayload() {
        return payload;
    }
}

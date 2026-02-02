package health.tiro.smartwebehr.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import javax.validation.constraints.NotNull;

public class SmartMessageRequest extends SmartMessageBase {
    
    @NotNull(message = "MessagingHandle is mandatory.")
    @JsonProperty("messagingHandle")
    private String messagingHandle;

    @NotNull(message = "MessageType is mandatory.")
    @JsonProperty("messageType")
    private String messageType;

    @NotNull(message = "Payload is mandatory.")
    @JsonProperty("payload")
    private JsonNode payload;

    public SmartMessageRequest() {
        super();
    }

    public SmartMessageRequest(String messageId, String messagingHandle, String messageType, JsonNode payload) {
        super(messageId);
        this.messagingHandle = messagingHandle;
        this.messageType = messageType;
        this.payload = payload;
    }

    public String getMessagingHandle() {
        return messagingHandle;
    }

    public void setMessagingHandle(String messagingHandle) {
        this.messagingHandle = messagingHandle;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }
}

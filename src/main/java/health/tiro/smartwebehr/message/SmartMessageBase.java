package health.tiro.smartwebehr.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import javax.validation.constraints.NotNull;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
    @JsonSubTypes.Type(value = SmartMessageRequest.class),
    @JsonSubTypes.Type(value = SmartMessageResponse.class)
})
public abstract class SmartMessageBase {
    
    @NotNull(message = "MessageId is mandatory.")
    @JsonProperty("messageId")
    private String messageId;

    protected SmartMessageBase() {
    }

    protected SmartMessageBase(String messageId) {
        this.messageId = messageId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
}

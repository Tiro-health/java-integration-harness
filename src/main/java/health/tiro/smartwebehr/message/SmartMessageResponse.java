package health.tiro.smartwebehr.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import health.tiro.smartwebehr.message.payload.ErrorResponse;
import health.tiro.smartwebehr.message.payload.ResponsePayload;

import java.util.UUID;

public class SmartMessageResponse extends SmartMessageBase {
    
    @JsonProperty("responseToMessageId")
    private String responseToMessageId;

    @JsonProperty("additionalResponsesExpected")
    private boolean additionalResponsesExpected;

    @JsonProperty("payload")
    private ResponsePayload payload;

    public SmartMessageResponse() {
        super();
    }

    public SmartMessageResponse(String messageId, String responseToMessageId, boolean additionalResponsesExpected, ResponsePayload payload) {
        super(messageId);
        this.responseToMessageId = responseToMessageId;
        this.additionalResponsesExpected = additionalResponsesExpected;
        this.payload = payload;
    }

    public SmartMessageResponse(String responseToMessageId, boolean additionalResponsesExpected, ResponsePayload payload) {
        this(UUID.randomUUID().toString(), responseToMessageId, additionalResponsesExpected, payload);
    }

    public SmartMessageResponse(String responseToMessageId, ResponsePayload payload) {
        this(UUID.randomUUID().toString(), responseToMessageId, false, payload);
    }

    public static SmartMessageResponse createErrorResponse(String responseToMessageId, ErrorResponse errorPayload) {
        return new SmartMessageResponse(UUID.randomUUID().toString(), responseToMessageId, false, errorPayload);
    }

    public String getResponseToMessageId() {
        return responseToMessageId;
    }

    public void setResponseToMessageId(String responseToMessageId) {
        this.responseToMessageId = responseToMessageId;
    }

    public boolean isAdditionalResponsesExpected() {
        return additionalResponsesExpected;
    }

    public void setAdditionalResponsesExpected(boolean additionalResponsesExpected) {
        this.additionalResponsesExpected = additionalResponsesExpected;
    }

    public ResponsePayload getPayload() {
        return payload;
    }

    public void setPayload(ResponsePayload payload) {
        this.payload = payload;
    }
}

package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for response payloads.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = ResponsePayload.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ErrorResponse.class)
})
public class ResponsePayload {
    
    private Map<String, JsonNode> extraFields = new HashMap<>();

    public ResponsePayload() {
    }

    @JsonAnyGetter
    public Map<String, JsonNode> getExtraFields() {
        return extraFields;
    }

    @JsonAnySetter
    public void setExtraField(String key, JsonNode value) {
        extraFields.put(key, value);
    }
}

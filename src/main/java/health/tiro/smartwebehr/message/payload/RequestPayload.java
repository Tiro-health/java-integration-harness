package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for request payloads.
 */
public class RequestPayload {
    
    private Map<String, JsonNode> extraFields = new HashMap<>();

    public RequestPayload() {
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

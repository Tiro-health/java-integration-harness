package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for scratchpad.read requests.
 */
public class ScratchpadRead extends RequestPayload {
    
    @JsonProperty("location")
    private String location;

    public ScratchpadRead() {
        super();
    }

    public ScratchpadRead(String location) {
        this.location = location;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}

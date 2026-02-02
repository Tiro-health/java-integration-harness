package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotNull;

/**
 * Payload for scratchpad.delete requests.
 */
public class ScratchpadDelete extends RequestPayload {
    
    @NotNull(message = "Location is mandatory.")
    @JsonProperty("location")
    private String location;

    public ScratchpadDelete() {
        super();
    }

    public ScratchpadDelete(String location) {
        this.location = location;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}

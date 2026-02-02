package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotNull;
import org.hl7.fhir.r4.model.Resource;

/**
 * Payload for scratchpad.update requests.
 */
public class ScratchpadUpdate extends RequestPayload {
    
    @NotNull(message = "Resource is mandatory.")
    @JsonProperty("resource")
    private Resource resource;

    public ScratchpadUpdate() {
        super();
    }

    public ScratchpadUpdate(Resource resource) {
        this.resource = resource;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }
}

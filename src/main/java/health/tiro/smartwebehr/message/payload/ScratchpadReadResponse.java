package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;

import java.util.List;

/**
 * Response payload for scratchpad.read.
 */
public class ScratchpadReadResponse extends ResponsePayload {
    
    @JsonIgnore // handled by HAPI FHIR
    private Resource resource;

    @JsonIgnore  // handled by HAPI FHIR
    private List<Resource> scratchpad;

    @JsonProperty("operationOutcome")
    private OperationOutcome operationOutcome;

    public ScratchpadReadResponse() {
        super();
    }

    public ScratchpadReadResponse(Resource resource, List<Resource> scratchpad, OperationOutcome operationOutcome) {
        this.resource = resource;
        this.scratchpad = scratchpad;
        this.operationOutcome = operationOutcome;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public List<Resource> getScratchpad() {
        return scratchpad;
    }

    public void setScratchpad(List<Resource> scratchpad) {
        this.scratchpad = scratchpad;
    }

    public OperationOutcome getOperationOutcome() {
        return operationOutcome;
    }

    public void setOperationOutcome(OperationOutcome operationOutcome) {
        this.operationOutcome = operationOutcome;
    }
}

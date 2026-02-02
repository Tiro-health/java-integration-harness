package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * Response payload for scratchpad.create.
 */
public class ScratchpadCreateResponse extends ResponsePayload {
    
    @JsonProperty("status")
    private String status;

    @JsonProperty("location")
    private String location;

    @JsonProperty("operationOutcome")
    private OperationOutcome operationOutcome;

    public ScratchpadCreateResponse() {
        super();
    }

    public ScratchpadCreateResponse(String status, String location, OperationOutcome operationOutcome) {
        this.status = status;
        this.location = location;
        this.operationOutcome = operationOutcome;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public OperationOutcome getOperationOutcome() {
        return operationOutcome;
    }

    public void setOperationOutcome(OperationOutcome operationOutcome) {
        this.operationOutcome = operationOutcome;
    }
}

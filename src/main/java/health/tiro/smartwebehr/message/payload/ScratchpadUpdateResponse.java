package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * Response payload for scratchpad.update.
 */
public class ScratchpadUpdateResponse extends ResponsePayload {
    
    @JsonProperty("status")
    private String status;

    @JsonProperty("operationOutcome")
    private OperationOutcome operationOutcome;

    public ScratchpadUpdateResponse() {
        super();
    }

    public ScratchpadUpdateResponse(String status, OperationOutcome operationOutcome) {
        this.status = status;
        this.operationOutcome = operationOutcome;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OperationOutcome getOperationOutcome() {
        return operationOutcome;
    }

    public void setOperationOutcome(OperationOutcome operationOutcome) {
        this.operationOutcome = operationOutcome;
    }
}

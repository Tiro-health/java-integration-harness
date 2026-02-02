package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotNull;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.QuestionnaireResponse;

/**
 * Payload for form.submitted requests.
 */
public class FormSubmit extends RequestPayload {
    
    @NotNull(message = "Outcome is mandatory.")
    @JsonProperty("outcome")
    private OperationOutcome outcome;

    @NotNull(message = "Response is mandatory.")
    @JsonProperty("response")
    private QuestionnaireResponse response;

    public FormSubmit() {
        super();
    }

    public FormSubmit(OperationOutcome outcome, QuestionnaireResponse response) {
        this.outcome = outcome;
        this.response = response;
    }

    public OperationOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(OperationOutcome outcome) {
        this.outcome = outcome;
    }

    public QuestionnaireResponse getResponse() {
        return response;
    }

    public void setResponse(QuestionnaireResponse response) {
        this.response = response;
    }
}

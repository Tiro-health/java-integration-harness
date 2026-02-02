package health.tiro.smartwebehr.events;

import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.QuestionnaireResponse;

import java.util.EventObject;

/**
 * Event fired when a form is submitted with validation outcome.
 */
public class FormSubmittedEvent extends EventObject {
    
    private final QuestionnaireResponse response;
    private final OperationOutcome outcome;

    public FormSubmittedEvent(Object source, QuestionnaireResponse response, OperationOutcome outcome) {
        super(source);
        this.response = response;
        this.outcome = outcome;
    }

    public QuestionnaireResponse getResponse() {
        return response;
    }

    public OperationOutcome getOutcome() {
        return outcome;
    }
}

package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotNull;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;

import java.util.ArrayList;
import java.util.List;

/**
 * Payload for sdc.displayQuestionnaire requests.
 */
public class SdcDisplayQuestionnaire extends RequestPayload {
    
    @NotNull(message = "Questionnaire is mandatory.")
    @JsonProperty("questionnaire")
    private Object questionnaire; // Can be String (canonical URL), Reference, or Questionnaire

    @JsonProperty("questionnaireResponse")
    private QuestionnaireResponse questionnaireResponse;

    @JsonProperty("context")
    private SdcDisplayQuestionnaireContext context;

    public SdcDisplayQuestionnaire() {
        super();
    }

    public SdcDisplayQuestionnaire(Object questionnaire, QuestionnaireResponse questionnaireResponse, SdcDisplayQuestionnaireContext context) {
        this.questionnaire = questionnaire;
        this.questionnaireResponse = questionnaireResponse;
        this.context = context;
    }

    public Object getQuestionnaire() {
        return questionnaire;
    }

    public void setQuestionnaire(Object questionnaire) {
        this.questionnaire = questionnaire;
    }

    public QuestionnaireResponse getQuestionnaireResponse() {
        return questionnaireResponse;
    }

    public void setQuestionnaireResponse(QuestionnaireResponse questionnaireResponse) {
        this.questionnaireResponse = questionnaireResponse;
    }

    public SdcDisplayQuestionnaireContext getContext() {
        return context;
    }

    public void setContext(SdcDisplayQuestionnaireContext context) {
        this.context = context;
    }

    /**
     * Context for sdc.displayQuestionnaire.
     */
    public static class SdcDisplayQuestionnaireContext {
        
        @JsonProperty("subject")
        private Reference subject;

        @JsonProperty("author")
        private Reference author;

        @JsonProperty("encounter")
        private Reference encounter;

        @JsonProperty("launchContext")
        private List<LaunchContext> launchContext;

        public SdcDisplayQuestionnaireContext() {
            this.launchContext = new ArrayList<>();
        }

        public SdcDisplayQuestionnaireContext(Reference subject, Reference author, Reference encounter, List<LaunchContext> launchContext) {
            this.subject = subject;
            this.author = author;
            this.encounter = encounter;
            this.launchContext = launchContext != null ? launchContext : new ArrayList<>();
        }

        public Reference getSubject() {
            return subject;
        }

        public void setSubject(Reference subject) {
            this.subject = subject;
        }

        public Reference getAuthor() {
            return author;
        }

        public void setAuthor(Reference author) {
            this.author = author;
        }

        public Reference getEncounter() {
            return encounter;
        }

        public void setEncounter(Reference encounter) {
            this.encounter = encounter;
        }

        public List<LaunchContext> getLaunchContext() {
            return launchContext;
        }

        public void setLaunchContext(List<LaunchContext> launchContext) {
            this.launchContext = launchContext;
        }
    }
}

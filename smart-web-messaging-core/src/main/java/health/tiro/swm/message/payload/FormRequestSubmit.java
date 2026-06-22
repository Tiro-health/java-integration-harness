package health.tiro.swm.message.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for {@code ui.form.requestSubmit} when the host wants to express which
 * user-facing action triggered the submit. The form component remains the authority
 * on the resulting {@code QuestionnaireResponse.status} (it derives {@code amended}
 * from originate provenance and skips required-field validation for drafts) — the
 * host only states the intent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormRequestSubmit extends RequestPayload {

    /**
     * "finalize" (default) — validate and write {@code status = "completed"}
     * (or "amended" when prior originate provenance exists).
     * "save-draft" — skip required-field validation and write {@code status = "in-progress"}.
     * A missing value is treated as "finalize" by the form.
     */
    @JsonProperty("intent")
    private String intent;

    public FormRequestSubmit() {
        super();
    }

    public FormRequestSubmit(String intent) {
        this.intent = intent;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }
}

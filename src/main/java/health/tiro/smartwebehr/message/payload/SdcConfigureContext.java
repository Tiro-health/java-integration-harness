package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.hl7.fhir.r4.model.Reference;

import java.util.ArrayList;
import java.util.List;

/**
 * Payload for sdc.configureContext requests.
 */
public class SdcConfigureContext extends RequestPayload {
    
    @JsonProperty("subject")
    private Reference subject;

    @JsonProperty("author")
    private Reference author;

    @JsonProperty("encounter")
    private Reference encounter;

    @JsonProperty("launchContext")
    private List<LaunchContext> launchContext;

    public SdcConfigureContext() {
        super();
        this.launchContext = new ArrayList<>();
    }

    public SdcConfigureContext(Reference subject, Reference author, Reference encounter, List<LaunchContext> launchContext) {
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

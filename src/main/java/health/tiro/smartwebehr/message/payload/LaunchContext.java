package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotNull;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

/**
 * Launch context for SDC operations.
 */
public class LaunchContext {
    
    @NotNull
    @JsonProperty("name")
    private String name;

    @JsonProperty("contentReference")
    private Reference contentReference;

    @JsonProperty("contentResource")
    private Resource contentResource;

    public LaunchContext() {
    }

    public LaunchContext(String name) {
        this(name, null, null);
    }

    public LaunchContext(String name, Reference contentReference, Resource contentResource) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("LaunchContext name is required");
        }
        this.name = name;
        this.contentReference = contentReference;
        this.contentResource = contentResource;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Reference getContentReference() {
        return contentReference;
    }

    public void setContentReference(Reference contentReference) {
        this.contentReference = contentReference;
    }

    public Resource getContentResource() {
        return contentResource;
    }

    public void setContentResource(Resource contentResource) {
        this.contentResource = contentResource;
    }
}

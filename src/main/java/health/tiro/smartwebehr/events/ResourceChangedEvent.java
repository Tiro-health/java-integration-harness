package health.tiro.smartwebehr.events;

import org.hl7.fhir.r4.model.Resource;

import java.util.EventObject;

/**
 * Event fired when a FHIR resource is changed (created or updated).
 */
public class ResourceChangedEvent extends EventObject {
    
    private final Resource resource;

    public ResourceChangedEvent(Object source, Resource resource) {
        super(source);
        this.resource = resource;
    }

    public Resource getResource() {
        return resource;
    }
}

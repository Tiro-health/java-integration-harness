package health.tiro.smartwebehr;

import org.hl7.fhir.r4.model.Resource;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scratchpad containing a working memory of resources received via scratchpad messages.
 */
public class Scratchpad {
    
    private final Map<String, Resource> resources = new ConcurrentHashMap<>();

    private String createResourceLocation(Resource resource) {
        String resourceType = resource.fhirType();
        if (resource.getId() != null && !resource.getId().isEmpty()) {
            return resourceType + "/" + resource.getIdElement().getIdPart();
        } else {
            return resourceType + "/" + ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
        }
    }

    /**
     * Adds a new resource to the scratchpad. If the resource already exists, it will be updated.
     * 
     * @param resource The resource to add.
     * @return The location of the resource that was added.
     */
    public String createResource(Resource resource) {
        String location = createResourceLocation(resource);
        String id = location.substring(location.lastIndexOf('/') + 1);
        resource.setId(id);
        resources.put(location, resource);
        return location;
    }

    /**
     * Updates an existing resource in the scratchpad. If the resource does not exist, it will be created.
     * 
     * @param resource The resource to update.
     */
    public void updateResource(Resource resource) {
        String location = createResourceLocation(resource);
        resources.put(location, resource);
    }

    /**
     * Removes a resource from the scratchpad.
     * 
     * @param location The location of the resource to delete.
     */
    public void deleteResource(String location) {
        resources.remove(location);
    }

    /**
     * Retrieves a resource from the scratchpad by its location.
     * 
     * @param location The location of the resource to retrieve.
     * @return The resource with the specified location or null if not found.
     */
    public Resource getResource(String location) {
        return resources.get(location);
    }

    /**
     * Retrieves all resources from the scratchpad.
     * 
     * @return A collection containing all resources in the scratchpad.
     */
    public Collection<Resource> getAllResources() {
        return new ArrayList<>(resources.values());
    }

    /**
     * Clears all resources from the scratchpad.
     */
    public void clear() {
        resources.clear();
    }

    /**
     * Returns the number of resources in the scratchpad.
     */
    public int size() {
        return resources.size();
    }
}

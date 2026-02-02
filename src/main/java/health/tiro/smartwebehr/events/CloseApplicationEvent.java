package health.tiro.smartwebehr.events;

import java.util.EventObject;

/**
 * Event fired when the application should be closed.
 */
public class CloseApplicationEvent extends EventObject {
    
    public CloseApplicationEvent(Object source) {
        super(source);
    }
}

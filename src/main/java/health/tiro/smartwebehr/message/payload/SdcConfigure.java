package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload for sdc.configure requests.
 */
public class SdcConfigure extends RequestPayload {
    
    @JsonProperty("terminologyServer")
    private String terminologyServer;

    @JsonProperty("dataServer")
    private String dataServer;

    @JsonProperty("configuration")
    private Object configuration;

    public SdcConfigure() {
        super();
    }

    public SdcConfigure(String terminologyServer, String dataServer, Object configuration) {
        this.terminologyServer = terminologyServer;
        this.dataServer = dataServer;
        this.configuration = configuration;
    }

    public String getTerminologyServer() {
        return terminologyServer;
    }

    public void setTerminologyServer(String terminologyServer) {
        this.terminologyServer = terminologyServer;
    }

    public String getDataServer() {
        return dataServer;
    }

    public void setDataServer(String dataServer) {
        this.dataServer = dataServer;
    }

    public Object getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Object configuration) {
        this.configuration = configuration;
    }
}

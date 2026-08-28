package health.tiro.swm.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents an error response payload.
 */
public class ErrorResponse extends ResponsePayload {

    /**
     * The discriminator the bridge keys on: {@code handleMessage} rejects a pending request
     * only when {@code payload.$type === "error"}. Without it every error this class describes
     * was delivered to the page as an ordinary success payload, so a request that failed
     * resolved as though it had worked — and a refused handshake read as a connected one.
     */
    @JsonProperty("$type")
    public String getType() {
        return "error";
    }

    
    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("errorType")
    private String errorType;

    public ErrorResponse() {
        super();
    }

    public ErrorResponse(String errorMessage, String errorType) {
        this.errorMessage = errorMessage;
        this.errorType = errorType;
    }

    public ErrorResponse(Exception error) {
        this.errorMessage = "An internal error occurred";
        this.errorType = "InternalError";
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }
}

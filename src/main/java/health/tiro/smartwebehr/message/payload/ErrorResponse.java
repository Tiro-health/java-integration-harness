package health.tiro.smartwebehr.message.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents an error response payload.
 */
public class ErrorResponse extends ResponsePayload {
    
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
        this.errorMessage = error.getMessage();
        this.errorType = error.getClass().getSimpleName();
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

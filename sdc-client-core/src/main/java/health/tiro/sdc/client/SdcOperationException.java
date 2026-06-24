package health.tiro.sdc.client;

import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;

/**
 * Thrown when an SDC server operation fails at the transport/server level (a non-success HTTP
 * status). Carries the operation, the HTTP status, and — when the server returned one — the parsed
 * {@link IBaseOperationOutcome} describing the failure.
 *
 * <p>A {@code $validate} call that merely reports validation issues is NOT an error: it returns a
 * normal 200 with an {@code OperationOutcome}, which the client returns directly. This exception is
 * reserved for actual operation failures (4xx/5xx, unreadable bodies, etc.).
 */
public class SdcOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operation;
    private final int statusCode;
    private final transient IBaseOperationOutcome outcome;

    public SdcOperationException(String operation, int statusCode, IBaseOperationOutcome outcome, String message) {
        this(operation, statusCode, outcome, message, null);
    }

    public SdcOperationException(String operation, int statusCode, IBaseOperationOutcome outcome, String message, Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.statusCode = statusCode;
        this.outcome = outcome;
    }

    /** The operation that failed, e.g. {@code QuestionnaireResponse/$validate}. */
    public String getOperation() {
        return operation;
    }

    /** The HTTP status code returned by the SDC server. */
    public int getStatusCode() {
        return statusCode;
    }

    /** The server's OperationOutcome, if the error body contained one; otherwise {@code null}. */
    public IBaseOperationOutcome getOutcome() {
        return outcome;
    }
}

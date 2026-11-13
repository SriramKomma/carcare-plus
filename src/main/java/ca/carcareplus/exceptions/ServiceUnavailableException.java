package ca.carcareplus.exceptions;

/**
 * An outside service (Google Places) is not set up or not reachable. The web
 * page shows the message instead of results (HTTP 503).
 */
public class ServiceUnavailableException extends CarCareException {

    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

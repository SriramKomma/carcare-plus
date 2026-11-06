package ca.carcareplus.exceptions;

/**
 * Base class for recoverable CarCare+ failures that the caller must handle
 * (for example: a website could not be crawled, a data file could not be read).
 *
 * It is a checked exception, so the compiler forces callers to decide what to do:
 * skip the page, show a message, or fall back to an empty result.
 */
public class CarCareException extends Exception {

    private static final long serialVersionUID = 1L;

    public CarCareException(String message) {
        super(message);
    }

    public CarCareException(String message, Throwable cause) {
        super(message, cause);
    }
}

package ca.carcareplus.exceptions;

/**
 * The request needs the demo link (on a hosted server, the live dispatch demo asks
 * for the secret demo key). The web server answers it with HTTP 403 and the message.
 */
public class AccessDeniedException extends CarCareException {

    private static final long serialVersionUID = 1L;

    public AccessDeniedException(String message) {
        super(message);
    }
}

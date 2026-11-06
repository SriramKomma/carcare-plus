package ca.carcareplus.exceptions;

/**
 * Saved data could not be written or read (missing folder, no permission, corrupt file).
 */
public class StorageException extends CarCareException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

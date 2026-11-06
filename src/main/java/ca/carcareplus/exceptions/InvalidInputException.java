package ca.carcareplus.exceptions;

/**
 * A value given to a method is not acceptable (null, blank, out of range,
 * wrong format). The message is written so it can be shown directly to the user.
 *
 * It extends IllegalArgumentException (unchecked) because bad input is usually
 * fixed by asking again, not by a recovery path in every caller.
 */
public class InvalidInputException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message) {
        super(message);
    }
}

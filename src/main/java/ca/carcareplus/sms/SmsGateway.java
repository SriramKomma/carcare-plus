package ca.carcareplus.sms;

import ca.carcareplus.exceptions.ServiceUnavailableException;

/**
 * Sends one text message. Two versions:
 * <ul>
 *   <li>{@link TwilioSmsGateway}: real SMS to real phones (needs a Twilio account)</li>
 *   <li>{@link SimulatedSmsGateway}: keeps the messages in memory and shows them on
 *       pretend phones in the browser (tests, and a demo without an account)</li>
 * </ul>
 * The dispatch rules (SmsDispatcher) only see this interface, so they are the same
 * code in both cases.
 */
public interface SmsGateway {

    /**
     * @param toPhone a phone number in any Canadian format, e.g. "(519) 555-0100"
     * @param text    the message
     * @throws ServiceUnavailableException if the message could not be sent
     */
    void send(String toPhone, String text) throws ServiceUnavailableException;

    /** True for the pretend phones. */
    boolean isSimulated();

    /** For the page and the console, e.g. "Twilio, from +1 519 555 0199". */
    String description();
}

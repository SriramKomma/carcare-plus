package ca.carcareplus.sms;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.validation.DataValidator;

/**
 * Pretend phones: every message CarCare+ "sends", and every reply typed on a pretend
 * phone in the browser (/live-demo?phone=sriram), is kept here in memory.
 *
 * Used when no Twilio account is set up, and in every test, so tests never send a
 * real text. Thread-safe (synchronized): the SMS thread adds messages while page
 * requests read them.
 */
public final class SimulatedSmsGateway implements SmsGateway {

    /** Oldest messages beyond this many are forgotten. */
    static final int MAX_KEPT = 500;

    /**
     * @param phone       the repairer's phone, normalized to "(XXX) XXX-XXXX"
     * @param fromCarCare true = CarCare+ sent it to the phone; false = the phone's owner replied
     * @param at          epoch milliseconds
     */
    public record Message(String phone, boolean fromCarCare, String text, long at) {
    }

    private final Clock clock;
    private final List<Message> messages = new ArrayList<>();
    private long version = 1;

    public SimulatedSmsGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void send(String toPhone, String text) {
        add(toPhone, true, text);
    }

    /** A reply typed on a pretend phone. */
    public synchronized void received(String fromPhone, String text) {
        add(fromPhone, false, text);
    }

    /** One phone's messages, oldest first. */
    public synchronized List<Message> conversation(String phone) {
        String number = normalize(phone);
        return messages.stream().filter(m -> m.phone().equals(number)).toList();
    }

    /** Goes up with every message, so a pretend phone only redraws when something arrived. */
    public synchronized long version() {
        return version;
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public String description() {
        return "simulated phones (no Twilio account set up)";
    }

    private void add(String phone, boolean fromCarCare, String text) {
        messages.add(new Message(normalize(phone), fromCarCare, text == null ? "" : text, clock.millis()));
        if (messages.size() > MAX_KEPT) {
            messages.remove(0);
        }
        version++;
    }

    private static String normalize(String phone) {
        String number = DataValidator.normalizePhone(phone == null ? "" : phone.trim());
        if (number == null) {
            throw new InvalidInputException("Not a phone number: " + phone);
        }
        return number;
    }
}

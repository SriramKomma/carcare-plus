package ca.carcareplus.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.sms.SimulatedSmsGateway;
import ca.carcareplus.sms.SmsGateway;

/**
 * Dispatch by SMS with pretend phones. The executor runs each send at once
 * (Runnable::run), so the test can read the texts straight after each step.
 */
class SmsDispatcherTest {

    static final String SRIRAM = "(519) 555-0101";
    static final String SARVESH = "(519) 555-0102";
    static final String SAIKRISHNA = "(519) 555-0103";
    static final String DHARANISH = "(519) 555-0104";

    private MutableClock clock;
    private JobBoard board;
    private SimulatedSmsGateway phones;
    private SmsDispatcher sms;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-11-26T15:00:00Z"));
        board = new JobBoard(clock);
        phones = new SimulatedSmsGateway(clock);
        sms = new SmsDispatcher(phones, RepairerDirectory.load(Path.of("config", "repairers.example.txt")), board, Runnable::run);
    }

    private RepairJob post(List<String> invited) {
        return board.post(new JobBoard.JobRequest("Alex Morgan", "(519) 555-0100", "Windsor", "Gas Vehicle",
                "dead battery - at the mall", "Battery problem", invited));
    }

    private RepairJob postToTeam() {
        return post(List.of("sriram", "sarvesh", "saikrishna", "dharanish"));
    }

    /** The last text CarCare+ sent to this phone. */
    private String lastTextTo(String phone) {
        List<SimulatedSmsGateway.Message> texts = phones.conversation(phone).stream().filter(SimulatedSmsGateway.Message::fromCarCare).toList();
        return texts.isEmpty() ? "" : texts.get(texts.size() - 1).text();
    }

    private int textsTo(String phone) {
        return (int) phones.conversation(phone).stream().filter(SimulatedSmsGateway.Message::fromCarCare).count();
    }

    @Test
    void everyInvitedRepairerIsTextedTheRequest() {
        RepairJob job = postToTeam();
        for (String phone : List.of(SRIRAM, SARVESH, SAIKRISHNA, DHARANISH)) {
            String text = lastTextTo(phone);
            assertTrue(text.startsWith("CarCare+ repair request JOB-1: Battery problem (dead battery - at the mall)"), text);
            assertTrue(text.contains("Customer: Alex."), "first name only before accepting: " + text);
            assertTrue(!text.contains("555-0100"), "no customer phone before accepting");
            assertTrue(text.contains("Reply ACCEPTED"), text);
            assertEquals(SmsDispatcher.Delivery.SENT, sms.delivery(job.id(), idOf(phone)));
        }
    }

    private static String idOf(String phone) {
        return switch (phone) {
            case SRIRAM -> "sriram";
            case SARVESH -> "sarvesh";
            case SAIKRISHNA -> "saikrishna";
            default -> "dharanish";
        };
    }

    @Test
    void firstAcceptedReplyWinsAndEveryoneIsTold() {
        postToTeam();
        // Twilio writes the sender as +1 and 10 digits; it must still be recognized as Sarvesh
        assertEquals("", sms.received("+15195550102", "Accepted"), "the winner is told by the 'You got' text");
        assertEquals("sarvesh", board.snapshot().job("JOB-1").assignedTo());

        String won = lastTextTo(SARVESH);
        assertTrue(won.startsWith("You got JOB-1. Customer: Alex Morgan, (519) 555-0100, Windsor."), won);
        for (String other : List.of(SRIRAM, SAIKRISHNA, DHARANISH)) {
            assertEquals("CarCare+ JOB-1 has been assigned to another repairer. No need to reply.", lastTextTo(other));
        }

        String late = sms.received("+15195550101", "ACCEPTED");
        assertTrue(late.startsWith("Sorry, JOB-1: Too late"), late);
        assertEquals(late, lastTextTo(SRIRAM), "the late repairer is texted the answer");
        assertEquals("sarvesh", board.snapshot().job("JOB-1").assignedTo(), "the first acceptance stands");
        assertEquals("CarCare+: JOB-1 is already yours.", sms.received(SARVESH, "accepted"), "winner pressing again");
    }

    @Test
    void acceptingOnTheWebPageSendsTheSameTexts() {
        postToTeam();
        board.accept("JOB-1", "dharanish");
        assertTrue(lastTextTo(DHARANISH).startsWith("You got JOB-1"));
        assertTrue(lastTextTo(SRIRAM).contains("assigned to another repairer"));
    }

    @Test
    void noPassesAndTheOthersCanStillAccept() {
        postToTeam();
        assertEquals("CarCare+: OK, you passed on JOB-1.", sms.received(SRIRAM, "no thanks"));
        assertEquals("", sms.received(SAIKRISHNA, "yes"));
        assertEquals("saikrishna", board.snapshot().job("JOB-1").assignedTo());
        assertEquals("CarCare+: OK, you passed on JOB-1.", lastTextTo(SRIRAM), "no 'assigned' text after passing");
    }

    @Test
    void severalOpenRequestsNeedTheNumber() {
        postToTeam();
        postToTeam();
        String ask = sms.received(SRIRAM, "ACCEPTED");
        assertTrue(ask.contains("2 open requests (JOB-1, JOB-2)"), ask);
        assertTrue(ask.contains("ACCEPTED 1"), ask);
        assertEquals("", sms.received(SRIRAM, "accepted job-2"));
        assertEquals("sriram", board.snapshot().job("JOB-2").assignedTo());
        assertEquals("", sms.received(SARVESH, "ACCEPTED"), "now only JOB-1 is open for Sarvesh");
        assertEquals("sarvesh", board.snapshot().job("JOB-1").assignedTo());
    }

    @Test
    void onlyInvitedRepairersAndKnownNumbersCount() {
        post(List.of("sriram"));
        assertEquals("CarCare+: you have no open requests right now.", sms.received(SARVESH, "ACCEPTED"));
        assertTrue(sms.received(SARVESH, "ACCEPTED 1").contains("not sent to you"));
        assertEquals("This number is not registered as a CarCare+ repairer.", sms.received("519 555 0123", "ACCEPTED"));
        assertEquals("OPEN", board.snapshot().job("JOB-1").status().name());
        assertTrue(sms.received(SRIRAM, "ACCEPTED 99").contains("no request JOB-99"));
    }

    @Test
    void otherTextsGetHelpButStopIsLeftToTheProvider() {
        postToTeam();
        assertTrue(sms.received(SRIRAM, "where is it?").contains("reply ACCEPTED"));
        int before = textsTo(SRIRAM);
        assertEquals("", sms.received(SRIRAM, "STOP"));
        assertEquals(before, textsTo(SRIRAM), "we never answer STOP");
    }

    @Test
    void cancelAndExpiryAreTexted() {
        postToTeam();
        sms.received(SRIRAM, "NO");
        board.cancel("JOB-1");
        assertEquals("CarCare+ JOB-1 was cancelled by the customer. No need to reply.", lastTextTo(SARVESH));
        assertEquals("CarCare+: OK, you passed on JOB-1.", lastTextTo(SRIRAM), "who passed is not bothered again");

        postToTeam();
        clock.advance(JobBoard.ANSWER_TIME);
        board.snapshot(); // any call notices the time limit
        assertTrue(lastTextTo(DHARANISH).startsWith("CarCare+ JOB-2 expired"), lastTextTo(DHARANISH));
    }

    @Test
    void aFailedTextIsShownAsFailed() throws Exception {
        SmsGateway broken = new SmsGateway() {
            @Override
            public void send(String toPhone, String text) throws ServiceUnavailableException {
                throw new ServiceUnavailableException("The number is unverified.");
            }

            @Override
            public boolean isSimulated() {
                return true;
            }

            @Override
            public String description() {
                return "broken";
            }
        };
        JobBoard other = new JobBoard(clock);
        SmsDispatcher failing = new SmsDispatcher(broken, RepairerDirectory.load(Path.of("config", "repairers.example.txt")), other, Runnable::run);
        other.post(new JobBoard.JobRequest("Alex", "(519) 555-0100", "", "Gas Vehicle", "flat", "", List.of("sriram")));
        assertEquals(SmsDispatcher.Delivery.FAILED, failing.delivery("JOB-1", "sriram"));
        assertEquals("The number is unverified.", failing.deliveryError("JOB-1", "sriram"));
    }
}

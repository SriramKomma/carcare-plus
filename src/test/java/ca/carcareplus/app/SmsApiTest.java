package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ca.carcareplus.dispatch.JobBoard;
import ca.carcareplus.dispatch.MutableClock;
import ca.carcareplus.dispatch.RepairerDirectory;
import ca.carcareplus.dispatch.SmsDispatcher;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.sms.SimulatedSmsGateway;
import ca.carcareplus.sms.TwilioSmsGateway;

class SmsApiTest {

    static final String WEBHOOK = "https://abc.ngrok-free.app/api/sms/incoming";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-26T15:00:00Z"));

    private static RepairerDirectory team() throws Exception {
        return RepairerDirectory.load(Path.of("config", "repairers.txt"));
    }

    private static void post(JobBoard board) {
        board.post(new JobBoard.JobRequest("Alex Morgan", "(519) 555-0100", "Windsor", "Gas Vehicle", "flat tire",
                "Flat tire / puncture", List.of("sriram", "sarvesh", "saikrishna", "dharanish")));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("messages");
    }

    @Test
    void pretendPhoneShowsTheTextsAndItsReplyIsHandledLikeARealSms() throws Exception {
        JobBoard board = new JobBoard(clock);
        SmsDispatcher sms = new SmsDispatcher(new SimulatedSmsGateway(clock), team(), board, Runnable::run);
        SmsApi api = new SmsApi(sms, team());
        post(board);

        Map<String, Object> phone = api.phone(Map.of("repairer", "sarvesh"));
        assertEquals(1, messages(phone).size());
        assertEquals(true, messages(phone).get(0).get("fromCarCare"));
        long version = (Long) phone.get("version");
        assertEquals(false, api.phone(Map.of("repairer", "sarvesh", "since", String.valueOf(version))).get("changed"));

        api.reply(Map.of("repairer", "sarvesh", "text", "ACCEPTED"));
        assertEquals("sarvesh", board.snapshot().job("JOB-1").assignedTo());
        List<Map<String, Object>> thread = messages(api.phone(Map.of("repairer", "sarvesh")));
        assertEquals(3, thread.size(), "request, my ACCEPTED, 'You got JOB-1'");
        assertEquals(false, thread.get(1).get("fromCarCare"));
        assertTrue(((String) thread.get(2).get("text")).startsWith("You got JOB-1"));

        assertThrows(InvalidInputException.class, () -> api.reply(Map.of("repairer", "sarvesh", "text", " ")));
        assertThrows(InvalidInputException.class, () -> api.phone(Map.of("repairer", "nobody")));
        assertFalse(api.incoming("anything", Map.of("From", "+15195550102", "Body", "ACCEPTED")),
                "without Twilio, nothing real can arrive");
    }

    @Test
    void realRepliesAreBelievedOnlyWithTwilioSignature() throws Exception {
        JobBoard board = new JobBoard(clock);
        // Port 9 on this computer refuses connections: texts fail quietly, no real SMS is sent
        TwilioSmsGateway twilio = new TwilioSmsGateway("AC123", "test-token", "519 555 0199", WEBHOOK, "http://127.0.0.1:9");
        SmsApi api = new SmsApi(new SmsDispatcher(twilio, team(), board, Runnable::run), team());
        post(board);

        Map<String, String> forged = new HashMap<>(Map.of("From", "+15195550101", "Body", "ACCEPTED"));
        assertFalse(api.incoming("bad-signature", forged));
        assertNull(board.snapshot().job("JOB-1").assignedTo(), "a forged ACCEPTED changes nothing");

        Map<String, String> genuine = new HashMap<>(Map.of("From", "+15195550102", "Body", "ACCEPTED", "MessageSid", "SM1"));
        String signature = signed(genuine);
        assertTrue(api.incoming(signature, genuine));
        assertEquals("sarvesh", board.snapshot().job("JOB-1").assignedTo());
        assertThrows(InvalidInputException.class, () -> api.phone(Map.of("repairer", "sarvesh")),
                "no pretend phones while real SMS is on");
    }

    /** What Twilio would put in X-Twilio-Signature (TwilioSmsGatewayTest checks the rule itself). */
    private static String signed(Map<String, String> form) throws Exception {
        java.lang.reflect.Method sign = TwilioSmsGateway.class.getDeclaredMethod("sign", String.class, String.class, Map.class);
        sign.setAccessible(true);
        return (String) sign.invoke(null, "test-token", WEBHOOK, form);
    }
}

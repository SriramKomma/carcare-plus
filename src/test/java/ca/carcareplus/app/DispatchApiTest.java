package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.carcareplus.dispatch.JobBoard;
import ca.carcareplus.dispatch.MutableClock;
import ca.carcareplus.dispatch.RepairerDirectory;
import ca.carcareplus.exceptions.InvalidInputException;

/** The live dispatch demo with the team list (config/repairers.example.txt, made-up numbers) and a hand-moved clock. */
class DispatchApiTest {

    private MutableClock clock;
    private DispatchApi api;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-11-26T15:00:00Z"));
        RepairerDirectory team = RepairerDirectory.load(Path.of("config", "repairers.example.txt"));
        // The service finder is WebApi's in the real server; here a stand-in keeps the test small
        api = new DispatchApi(team, new JobBoard(clock),
                (vehicle, problem) -> problem.contains("flat") ? "Flat tire / puncture" : "");
    }

    private Map<String, String> form(String... pairs) {
        Map<String, String> map = new HashMap<>();
        map.put("name", "Sam Lee");
        map.put("phone", "519 555 0100");
        map.put("area", "Windsor");
        map.put("vehicle", "gas");
        map.put("q", "flat tire");
        map.put("repairers", "sriram,sarvesh,saikrishna,dharanish");
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void postingSendsTheRequestToEveryChosenRepairer() {
        Map<String, Object> job = api.post(form("details", "front left, on the highway"));
        assertEquals("JOB-1", job.get("id"));
        assertEquals("OPEN", job.get("status"));
        assertEquals("Flat tire / puncture", job.get("service"));
        assertEquals("flat tire - front left, on the highway", job.get("problem"));
        assertEquals("(519) 555-0100", job.get("customerPhone"));
        assertEquals("Waiting for a repairer to accept…", job.get("headline"));
        List<Map<String, Object>> invited = list(job.get("invited"));
        assertEquals(4, invited.size());
        invited.forEach(r -> assertEquals("waiting", r.get("answer")));

        for (String repairer : List.of("sriram", "sarvesh", "saikrishna", "dharanish")) {
            List<Map<String, Object>> inbox = list(api.live(params("repairer", repairer)).get("jobs"));
            assertEquals(1, inbox.size(), repairer + " received it");
            assertEquals("NEW", inbox.get(0).get("mine"));
        }
    }

    @Test
    void repairersSeeOnlyTheFirstNameUntilTheyWin() {
        api.post(form());
        Map<String, Object> before = list(api.live(params("repairer", "sriram")).get("jobs")).get(0);
        assertEquals("Sam", before.get("customerName"));
        assertFalse(before.containsKey("customerPhone"), "no phone before accepting");

        Map<String, Object> accepted = api.accept(params("id", "JOB-1", "repairer", "sriram"));
        assertEquals(true, accepted.get("accepted"));
        Map<String, Object> mine = map(accepted.get("job"));
        assertEquals("WON", mine.get("mine"));
        assertEquals("Sam Lee", mine.get("customerName"));
        assertEquals("(519) 555-0100", mine.get("customerPhone"));
    }

    @Test
    void firstAcceptIsAssignedAndEveryWindowSeesIt() {
        api.post(form());
        clock.advance(Duration.ofMillis(3400));
        assertEquals(true, api.accept(params("id", "JOB-1", "repairer", "sarvesh")).get("accepted"));

        Map<String, Object> late = api.accept(params("id", "JOB-1", "repairer", "sriram"));
        assertEquals(false, late.get("accepted"));
        assertEquals("TAKEN", map(late.get("job")).get("mine"));
        assertTrue(((String) late.get("message")).contains("already accepted"));

        // The customer's window: who won, how fast, and how to reach them
        Map<String, Object> customer = map(api.live(params("job", "JOB-1")).get("job"));
        assertEquals("ASSIGNED", customer.get("status"));
        assertEquals("Sarvesh accepted your request", customer.get("headline"));
        Map<String, Object> winner = map(customer.get("assigned"));
        assertEquals("sarvesh", winner.get("id"));
        assertEquals("(519) 555-0102", winner.get("phone"));
        assertEquals("London", winner.get("area"));
        assertEquals(3.4, winner.get("acceptedInSeconds"));
        List<Map<String, Object>> invited = list(customer.get("invited"));
        assertEquals("no answer", invited.get(0).get("answer"), "sriram");
        assertEquals("accepted", invited.get(1).get("answer"), "sarvesh");

        // The other repairers' windows
        Map<String, Object> other = list(api.live(params("repairer", "dharanish")).get("jobs")).get(0);
        assertEquals("TAKEN", other.get("mine"));
        assertEquals("Already assigned to another repairer", other.get("headline"));
    }

    @Test
    void liveAnswersNothingChangedWhenTheVersionIsTheSame() {
        Map<String, Object> first = api.live(params("job", ""));
        assertEquals(true, first.get("changed"));
        long version = (Long) first.get("version");
        Map<String, Object> again = api.live(params("job", "", "since", String.valueOf(version)));
        assertEquals(false, again.get("changed"));
        assertFalse(again.containsKey("repairers"), "the small answer");

        api.post(form());
        assertEquals(true, api.live(params("job", "", "since", String.valueOf(version))).get("changed"));
    }

    @Test
    void customerSeesWhichRepairersAreOnline() {
        api.live(params("repairer", "saikrishna")); // SaiKrishna's window is open
        List<Map<String, Object>> repairers = list(api.live(params()).get("repairers"));
        assertEquals(4, repairers.size());
        for (Map<String, Object> r : repairers) {
            assertEquals(r.get("id").equals("saikrishna"), r.get("online"), r.get("id") + " online?");
        }
        clock.advance(Duration.ofSeconds(10));
        assertEquals(false, list(api.repairers()).get(2).get("online"), "window closed");
    }

    @Test
    void onlyTheChosenRepairersReceiveTheRequest() {
        api.post(form("repairers", "sriram, dharanish"));
        assertEquals(1, list(api.live(params("repairer", "sriram")).get("jobs")).size());
        assertEquals(0, list(api.live(params("repairer", "sarvesh")).get("jobs")).size());
        assertEquals(false, api.accept(params("id", "JOB-1", "repairer", "sarvesh")).get("accepted"));
    }

    @Test
    void everyoneDecliningIsShownToTheCustomer() {
        api.post(form("repairers", "sriram,sarvesh"));
        api.reject(params("id", "JOB-1", "repairer", "sriram"));
        api.reject(params("id", "JOB-1", "repairer", "sarvesh"));
        Map<String, Object> customer = map(api.live(params("job", "JOB-1")).get("job"));
        assertEquals("NO_TAKERS", customer.get("status"));
        assertTrue(((String) customer.get("headline")).startsWith("Every repairer declined"));
        assertNull(customer.get("assigned"));
    }

    @Test
    void cancelledAndExpiredRequestsAreShownToRepairers() {
        api.post(form());
        api.cancel(params("id", "JOB-1"));
        assertEquals("CANCELLED", list(api.live(params("repairer", "sriram")).get("jobs")).get(0).get("mine"));

        api.post(form());
        clock.advance(JobBoard.ANSWER_TIME);
        Map<String, Object> newest = list(api.live(params("repairer", "sriram")).get("jobs")).get(0);
        assertEquals("JOB-2", newest.get("id"), "newest first");
        assertEquals("EXPIRED", newest.get("mine"));
    }

    @Test
    void winnerMarksTheJobDone() {
        api.post(form());
        api.accept(params("id", "JOB-1", "repairer", "sriram"));
        assertEquals(true, api.done(params("id", "JOB-1", "repairer", "sriram")).get("done"));
        assertEquals("Sriram Komma finished the repair", map(api.live(params("job", "JOB-1")).get("job")).get("headline"));
    }

    @Test
    void theVehicleIsOptional() {
        Map<String, Object> job = api.post(form("vehicle", ""));
        assertEquals("", job.get("vehicle"));
        assertEquals("Flat tire / puncture", job.get("service"));
    }

    @Test
    void badFormsAreRefusedWithAClearMessage() {
        assertThrows(InvalidInputException.class, () -> api.post(form("name", " ")));
        assertThrows(InvalidInputException.class, () -> api.post(form("phone", "12345")));
        assertThrows(InvalidInputException.class, () -> api.post(form("q", "", "details", "")));
        assertThrows(InvalidInputException.class, () -> api.post(form("repairers", "")));
        assertThrows(InvalidInputException.class, () -> api.post(form("repairers", "sriram,nobody")));
        assertThrows(InvalidInputException.class, () -> api.post(form("vehicle", "boat")));
        assertThrows(InvalidInputException.class, () -> api.accept(params("id", "JOB-1", "repairer", "nobody")));
        assertThrows(InvalidInputException.class, () -> api.live(params("since", "abc")));
    }

    @Test
    void unknownJobInTheCustomerWindowSaysTheServerRestarted() {
        Map<String, Object> answer = api.live(params("job", "JOB-7"));
        assertNull(answer.get("job"));
        assertTrue(((String) answer.get("message")).contains("restarted"));
    }
}

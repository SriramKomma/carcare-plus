package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.osm.OsmImporter;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.storage.RawPageStore;
import ca.carcareplus.visits.VisitBook;

/**
 * Non-priority "Request a visit", on the INVENTED shops of src/test/resources/osm-priority.
 * The clock is Monday 2026-11-16, 10:30 Toronto time.
 */
class WebApiVisitTest {

    @TempDir
    Path dir;

    private WebApi api;
    private SearchSystem system;
    private Path visitsFile;

    @BeforeEach
    void setUp() throws CarCareException {
        ServiceCategorizer categorizer = ServiceCategorizer.loadFile(Path.of("config", "categories.txt"));
        Path parsed = dir.resolve("parsed");
        new ParsePipeline(new RawPageStore(dir.resolve("raw")), new HTMLParser(categorizer), parsed).parseSources(
                List.of(), new OsmImporter(categorizer).load(Path.of("src", "test", "resources", "osm-priority")));
        system = SearchSystem.load(parsed, dir.resolve("history.txt"));
        visitsFile = dir.resolve("visits.json");
        api = new WebApi(system, null, WebApiRealDataTest.MONDAY_MORNING, VisitBook.open(visitsFile));
    }

    private String url(String title) {
        return system.documents().stream().filter(d -> d.getTitle().equals(title))
                .map(ServiceDocument::getUrl).findFirst().orElseThrow();
    }

    private Map<String, String> form(String... pairs) {
        Map<String, String> map = new HashMap<>();
        map.put("url", url("Open Near Garage"));
        map.put("slot", "2026-11-17T14:00");
        map.put("name", "Sam Lee");
        map.put("phone", "519 555 0100");
        map.put("vehicle", "gas");
        map.put("q", "my brakes are squealing");
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @SuppressWarnings("unchecked")
    void slotsFollowTheShopsHours() {
        Map<String, Object> answer = api.visitSlots(Map.of("url", url("Open Near Garage")));
        assertEquals(true, answer.get("hoursKnown"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) answer.get("days");
        assertEquals(List.of("Today", "Tomorrow", "Wed 18 Nov"), days.stream().map(d -> d.get("label")).toList());
        Map<String, String> first = ((List<Map<String, String>>) days.get(0).get("slots")).get(0);
        assertEquals(Map.of("value", "2026-11-16T10:30", "label", "10:30 AM"), first);
        assertEquals("(519) 555-0101", ((Map<String, Object>) answer.get("shop")).get("phone"));
    }

    @Test
    void unknownHoursAndClosedShops() {
        assertEquals(false, api.visitSlots(Map.of("url", url("Unknown Hours Garage"))).get("hoursKnown"));
        // "Closest But Closed" opens only on Saturday: nothing in the next 2 days
        Map<String, Object> closed = api.visitSlots(Map.of("url", url("Closest But Closed")));
        assertEquals(List.of(), closed.get("days"));
        assertTrue(((String) closed.get("message")).contains("not open in the next 2 days"));
        assertThrows(InvalidInputException.class, () -> api.visitSlots(Map.of("url", "https://nowhere.invalid/")));
        assertThrows(InvalidInputException.class, () -> api.visitSlots(Map.of()));
    }

    @Test
    void aRequestIsCheckedSavedAndGivesTheMessage() throws Exception {
        Map<String, Object> saved = api.requestVisit(form("email", " Sam@Example.COM "));

        assertEquals("CC-1001", saved.get("id"));
        assertEquals("REQUESTED", saved.get("status"));
        assertEquals("Tomorrow, 2:00 PM", saved.get("slotLabel"));
        assertEquals("(519) 555-0100", saved.get("phone"), "phone written the standard way");
        assertEquals("sam@example.com", saved.get("email"));
        assertEquals("Brake problem", saved.get("service"));
        assertEquals("(519) 555-0101", saved.get("shopPhone"));
        assertEquals(false, saved.get("past"));
        String message = (String) saved.get("message");
        assertTrue(message.contains("Reference: CC-1001"), message);
        assertTrue(message.contains("Vehicle: Gas Vehicle"), message);
        assertTrue(Files.readString(visitsFile).contains("CC-1001"), "saved to the file straight away");
    }

    @Test
    void badFormsAreRejectedWithAClearReason() {
        Map<String, String> expectations = Map.of(
                "slot", "Please choose a time.",
                "name", "Please enter your name");
        expectations.forEach((field, reason) -> {
            InvalidInputException e = assertThrows(InvalidInputException.class, () -> api.requestVisit(form(field, "")));
            assertTrue(e.getMessage().startsWith(reason), e.getMessage());
        });
        assertMessage("no longer available", form("slot", "2026-11-17T18:00"));   // after closing
        assertMessage("no longer available", form("slot", "2026-11-16T09:00"));   // already past
        assertMessage("no longer available", form("slot", "2026-11-23T10:00"));   // more than 2 days away
        assertMessage("Please enter your name", form("name", "<b>Sam</b>"));
        assertMessage("phone number or an email", form("phone", ""));
        assertMessage("10-digit phone", form("phone", "12345"));
        assertMessage("valid email", form("phone", "", "email", "sam@"));
        assertMessage("too long", form("note", "x".repeat(WebApi.MAX_NOTE + 1)));
        assertMessage("not in CarCare+", form("url", "https://nowhere.invalid/"));
    }

    private void assertMessage(String part, Map<String, String> form) {
        InvalidInputException e = assertThrows(InvalidInputException.class, () -> api.requestVisit(form));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void unknownHoursAcceptAPartOfTheDay() throws Exception {
        Map<String, Object> saved = api.requestVisit(form("url", url("Unknown Hours Garage"), "slot", "2026-11-17T09:00"));
        assertEquals("Tomorrow, Morning (9 AM – 12 PM)", saved.get("slotLabel"));
        assertEquals(false, saved.get("exactTime"));
        assertMessage("no longer available", form("url", url("Unknown Hours Garage"), "slot", "2026-11-17T14:00"));
    }

    @Test
    void driverSeesAndCancelsTheirRequests() throws Exception {
        api.requestVisit(form());
        api.requestVisit(form("slot", "2026-11-16T15:00", "name", "Ana Diaz"));

        List<Map<String, Object>> mine = api.visits(Map.of("ids", "CC-1002, CC-1001,CC-4040"));
        assertEquals(List.of("CC-1002", "CC-1001"), mine.stream().map(v -> v.get("id")).toList());
        assertEquals(List.of(), api.visits(Map.of()));

        assertEquals("CANCELLED", api.cancelVisit(Map.of("id", "CC-1001")).get("status"));
        assertEquals("CANCELLED", api.visits(Map.of("ids", "CC-1001")).get(0).get("status"));
        assertThrows(InvalidInputException.class, () -> api.cancelVisit(Map.of("id", "CC-4040")));
        assertThrows(InvalidInputException.class, () -> api.cancelVisit(Map.of()));

        // the requests survive a server restart
        WebApi restarted = new WebApi(system, null, WebApiRealDataTest.MONDAY_MORNING, VisitBook.open(visitsFile));
        assertEquals("Ana Diaz", restarted.visits(Map.of("ids", "CC-1002")).get(0).get("name"));
    }
}

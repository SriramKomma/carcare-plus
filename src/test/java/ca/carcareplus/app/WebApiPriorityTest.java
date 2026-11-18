package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.osm.OsmImporter;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.storage.RawPageStore;

/**
 * Priority and non-priority help, on INVENTED shops at known distances from a
 * point in Windsor (src/test/resources/osm-priority). The clock is Monday 10:30 Toronto time.
 */
class WebApiPriorityTest {

    private static final String LAT = "42.3149";
    private static final String LON = "-83.0364";

    @TempDir
    Path dir;

    private WebApi api;

    @BeforeEach
    void setUp() throws CarCareException {
        ServiceCategorizer categorizer = ServiceCategorizer.loadFile(Path.of("config", "categories.txt"));
        RawPageStore raw = new RawPageStore(dir.resolve("raw"));
        Path parsed = dir.resolve("parsed");
        new ParsePipeline(raw, new HTMLParser(categorizer), parsed).parseSources(List.of(),
                new OsmImporter(categorizer).load(Path.of("src", "test", "resources", "osm-priority")));
        api = new WebApi(SearchSystem.load(parsed, dir.resolve("history.txt")), null,
                WebApiRealDataTest.MONDAY_MORNING);
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> shops(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("shops");
    }

    private static List<Object> titles(Map<String, Object> answer) {
        return shops(answer).stream().map(shop -> shop.get("title")).toList();
    }

    // ------------------------------------------------------------- priority

    @Test
    void priorityOffersTheNearestShopsThatCanBeCalledNow() {
        Map<String, Object> answer = api.priority(params("vehicle", "gas", "lat", LAT, "lon", LON));

        // Not offered: "Closest But Closed" (closed on Monday), "No Phone Garage" (cannot be called),
        // "Far Away Garage" (80 km), and the two chargers (not repair shops)
        assertEquals(List.of("Open Near Garage", "Unknown Hours Garage", "Brake Specialist"), titles(answer));
        Map<String, Object> first = shops(answer).get(0);
        assertEquals("(519) 555-0101", first.get("phone"));
        assertEquals(0.5, first.get("distanceKm"));
        assertEquals("911", answer.get("emergencyNumber"));
        assertEquals("", answer.get("message"));
    }

    @Test
    void unknownHoursAreAllowedBecauseTheDriverCanStillCall() {
        Map<String, Object> second = shops(api.priority(params("vehicle", "gas", "lat", LAT, "lon", LON))).get(1);
        assertEquals("Hours unknown – call ahead", second.get("availability"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void describedProblemIsReportedAndMatchingShopsAreMarked() {
        Map<String, Object> answer = api.priority(params("vehicle", "gas", "lat", LAT, "lon", LON, "q", "brakes are squealing"));

        assertEquals("BRAKES", ((Map<String, Object>) answer.get("problem")).get("category"));
        assertEquals(List.of("Open Near Garage", "Unknown Hours Garage", "Brake Specialist"), titles(answer),
                "still nearest first: in an emergency distance matters most");
        assertEquals(true, shops(answer).get(2).get("matchesProblem"));
        assertEquals(false, shops(answer).get(0).get("matchesProblem"));
    }

    @Test
    void anEvDriverWhoNeedsToChargeGetsChargersNotGarages() {
        assertEquals(List.of("Test Charger", "Second Charger"),
                titles(api.priority(params("vehicle", "ev", "lat", LAT, "lon", LON, "q", "I need to charge my car"))));
        // the same words from a gas car are not a charging problem
        assertFalse(titles(api.priority(params("vehicle", "gas", "lat", LAT, "lon", LON, "q", "I need to charge my car")))
                .contains("Test Charger"));
    }

    @Test
    void nothingNearbyGivesASafeMessage() {
        Map<String, Object> answer = api.priority(params("vehicle", "gas", "lat", "45.5", "lon", "-75.7")); // Ottawa
        assertTrue(shops(answer).isEmpty());
        assertTrue(((String) answer.get("message")).contains("call 911"), (String) answer.get("message"));
    }

    @Test
    void priorityNeedsALocationAndAVehicle() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> api.priority(params("vehicle", "gas")));
        assertTrue(e.getMessage().contains("911"), e.getMessage());
        assertThrows(InvalidInputException.class, () -> api.priority(params("lat", LAT, "lon", LON)));
    }

    @Test
    void noProblemDescribedMeansNoProblemField() {
        assertNull(api.priority(params("vehicle", "gas", "lat", LAT, "lon", LON)).get("problem"));
    }

    // ------------------------------------------------------------- non-priority: next 2 days

    @Test
    @SuppressWarnings("unchecked")
    void searchResultsListWhenEachShopIsOpenInTheNextTwoDays() throws Exception {
        Map<String, Object> answer = api.search(params("vehicle", "gas", "q", "my brakes are squealing",
                "lat", LAT, "lon", LON, "radius", "25"));
        Map<String, Object> open = ((List<Map<String, Object>>) answer.get("results")).stream()
                .filter(r -> r.get("title").equals("Open Near Garage")).findFirst().orElseThrow();

        // Monday 10:30 -> rest of today, all of Tuesday, Wednesday until 10:30
        assertEquals(List.of("Today 10:30 AM – 5:00 PM", "Tomorrow 8:00 AM – 5:00 PM", "Wed 8:00 AM – 10:30 AM"),
                open.get("next2Days"));
        Map<String, Object> unknown = ((List<Map<String, Object>>) answer.get("results")).stream()
                .filter(r -> r.get("title").equals("Unknown Hours Garage")).findFirst().orElseThrow();
        assertEquals(List.of(), unknown.get("next2Days"), "unknown hours: the page says 'call to book'");
    }
}

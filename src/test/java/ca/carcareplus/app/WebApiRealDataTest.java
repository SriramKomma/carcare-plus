package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.osm.OsmImporter;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.storage.RawPageStore;

/**
 * The web API once "real" places exist (the OSM-format test sample stands in for
 * the downloaded data): test pages are hidden, "near me" filters by distance, and
 * coordinates survive saving and loading.
 */
class WebApiRealDataTest {

    /** Windsor city centre, where "Test Brake Garage" (node 1001) is. */
    static final String LAT = "42.3149";
    static final String LON = "-83.0364";

    @TempDir
    Path dir;

    private WebApi api;
    private SearchSystem system;

    /** 15:30 UTC on Monday 2026-11-16 = 10:30 in Toronto; Sunday 2026-11-22 likewise. */
    static final java.time.Clock MONDAY_MORNING = java.time.Clock.fixed(
            java.time.Instant.parse("2026-11-16T15:30:00Z"), java.time.ZoneOffset.UTC);
    static final java.time.Clock SUNDAY_MORNING = java.time.Clock.fixed(
            java.time.Instant.parse("2026-11-22T15:30:00Z"), java.time.ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws CarCareException {
        ServiceCategorizer categorizer = ServiceCategorizer.loadFile(Path.of("config", "categories.txt"));
        RawPageStore raw = new RawPageStore(dir.resolve("raw"));
        FixtureLoader.load(Path.of("fixtures", "html"), raw);
        Path parsed = dir.resolve("parsed");
        new ParsePipeline(raw, new HTMLParser(categorizer), parsed).parseSources(List.of(FixtureLoader.SOURCE_NAME),
                new OsmImporter(categorizer).load(Path.of("src", "test", "resources", "osm")));
        // Loaded back from disk, so this also checks that coordinates are saved
        system = SearchSystem.load(parsed, dir.resolve("history.txt"));
        api = new WebApi(system);
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("results");
    }

    @Test
    void testPagesAreHiddenOnceRealPlacesExist() throws Exception {
        Map<String, Object> answer = api.search(params("vehicle", "gas", "q", "brake repair"));
        assertFalse(results(answer).isEmpty());
        assertTrue(results(answer).stream().noneMatch(r -> (Boolean) r.get("testFixture")),
                "the invented Calgary brake shop is not shown");
        assertTrue(api.pages().stream().noneMatch(p -> (Boolean) p.get("testFixture")));
        assertEquals(6L, api.stats().get("realPages"));
        assertEquals(false, api.stats().get("liveEnabled"));
    }

    @Test
    void nearMeKeepsOnlyPlacesInsideTheRadiusWithDistances() throws Exception {
        Map<String, Object> answer = api.search(params("vehicle", "gas", "q", "repair", "lat", LAT, "lon", LON, "radius", "5"));

        List<Map<String, Object>> found = results(answer);
        assertEquals(List.of("Test Brake Garage", "Test General Auto"),
                found.stream().map(r -> (String) r.get("title")).sorted().toList());
        for (Map<String, Object> r : found) {
            assertTrue((Double) r.get("distanceKm") <= 5, r.toString());
            assertTrue(r.containsKey("lat") && r.containsKey("lon"), "coordinates survived save + load");
        }
        assertEquals(Map.of("lat", 42.3149, "lon", -83.0364, "radiusKm", 5.0), answer.get("near"));
    }

    @Test
    void sortByDistancePutsTheNearestFirst() throws Exception {
        Map<String, Object> answer = api.search(params("vehicle", "gas", "q", "repair", "lat", LAT, "lon", LON,
                "radius", "25", "sort", "distance"));
        List<Map<String, Object>> found = results(answer);
        assertEquals("distance", answer.get("sort"));
        assertEquals("Test Brake Garage", found.get(0).get("title"));
        for (int i = 1; i < found.size(); i++) {
            assertTrue((Double) found.get(i - 1).get("distanceKm") <= (Double) found.get(i).get("distanceKm"));
        }
        assertEquals(1, found.get(0).get("rank"));
    }

    @Test
    void tinyRadiusAndEmptyAreaExplainThemselves() throws Exception {
        assertEquals(1, results(api.search(params("vehicle", "gas", "q", "repair", "lat", LAT, "lon", LON, "radius", "0.5"))).size());

        Map<String, Object> ottawa = api.search(params("vehicle", "gas", "q", "repair", "lat", "45.4215", "lon", "-75.6972"));
        assertTrue(results(ottawa).isEmpty());
        assertTrue(((String) ottawa.get("noResultsMessage")).startsWith("No places found within 10 km of your location."),
                (String) ottawa.get("noResultsMessage"));
    }

    @Test
    void resultsCarryOpeningHoursAndCategories() throws Exception {
        Map<String, Object> top = results(api.search(params("vehicle", "gas", "q", "brake", "location", "Windsor"))).get(0);
        assertEquals("Test Brake Garage", top.get("title"));
        assertEquals("Mo-Fr 08:00-17:00", top.get("openingHours"));
        assertTrue(((List<?>) top.get("categories")).contains("Brake problem"));
        assertFalse(top.containsKey("distanceKm"), "no distance without a position");
    }

    @Test
    void badCoordinatesAndRadiusAreRejected() {
        assertThrows(InvalidInputException.class, () -> api.search(params("vehicle", "gas", "q", "x", "lat", "abc", "lon", "1")));
        assertThrows(InvalidInputException.class, () -> api.search(params("vehicle", "gas", "q", "x", "lat", LAT, "lon", LON, "radius", "far")));
        assertThrows(InvalidInputException.class, () -> api.search(params("vehicle", "gas", "q", "x", "lat", LAT, "lon", LON, "radius", "500")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachResultShowsItsAvailability() throws Exception {
        WebApi monday = new WebApi(system, null, MONDAY_MORNING);
        List<Map<String, Object>> found = results(monday.search(params("vehicle", "gas", "q", "repair", "location", "Windsor")));

        Map<String, Object> garage = found.stream().filter(r -> r.get("title").equals("Test Brake Garage")).findFirst().orElseThrow();
        assertEquals(Map.of("state", "OPEN", "label", "Open now · closes 5:00 PM"), garage.get("availability"));
        assertEquals("Mon: 8:00 AM – 5:00 PM", ((List<String>) garage.get("weeklyHours")).get(0));

        Map<String, Object> general = found.stream().filter(r -> r.get("title").equals("Test General Auto")).findFirst().orElseThrow();
        assertEquals(Map.of("state", "UNKNOWN", "label", "Hours unknown – call ahead"), general.get("availability"));
        assertEquals(List.of(), general.get("weeklyHours"));

        Map<String, Object> sundayGarage = results(new WebApi(system, null, SUNDAY_MORNING)
                .search(params("vehicle", "gas", "q", "brake", "location", "Windsor"))).get(0);
        assertEquals(Map.of("state", "CLOSED", "label", "Closed · opens tomorrow 8:00 AM"), sundayGarage.get("availability"));
    }

    @Test
    void openNowFilterHidesClosedAndUnknown() throws Exception {
        Map<String, Object> monday = new WebApi(system, null, MONDAY_MORNING)
                .search(params("vehicle", "gas", "q", "repair", "location", "Windsor", "open", "1"));
        assertEquals(List.of("Test Brake Garage"), results(monday).stream().map(r -> r.get("title")).toList());
        assertEquals(1, monday.get("closedHidden"), "the shop with unknown hours");
        assertEquals(true, monday.get("openNowOnly"));

        Map<String, Object> sunday = new WebApi(system, null, SUNDAY_MORNING)
                .search(params("vehicle", "gas", "q", "repair", "location", "Windsor", "open", "1"));
        assertTrue(results(sunday).isEmpty());
        assertEquals("2 matching place(s) are closed right now or have unknown hours. Turn off \"Open now\" to see them.",
                sunday.get("noResultsMessage"));
    }

    @Test
    void liveSearchWithoutKeyExplainsHowToTurnItOn() {
        ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class,
                () -> api.live(params("q", "car repair", "location", "Windsor")));
        assertTrue(e.getMessage().contains("GOOGLE_MAPS_API_KEY"), e.getMessage());
    }

    @Test
    void citiesListOnlyRealPlaces() {
        List<String> cities = api.cities().stream().map(c -> (String) c.get("city")).toList();
        assertEquals(List.of("Tecumseh", "Toronto", "Windsor"), cities);
    }
}

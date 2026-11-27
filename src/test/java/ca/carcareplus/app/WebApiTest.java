package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.storage.RawPageStore;

/** The web page's answers, checked without HTTP on the invented fixture pages. */
class WebApiTest {

    private static final String CALGARY = "https://fixtures.carcareplus.invalid/brake-shop-calgary";

    @TempDir
    Path dir;

    private WebApi api;
    private Path historyFile;

    @BeforeEach
    void setUp() throws CarCareException {
        RawPageStore rawPages = new RawPageStore(dir.resolve("raw-pages"));
        Path parsedDir = dir.resolve("parsed-pages");
        historyFile = dir.resolve("search-history.txt");
        HTMLParser parser = new HTMLParser(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
        FixtureLoader.load(Path.of("fixtures", "html"), rawPages);
        new ParsePipeline(rawPages, parser, parsedDir).parseSources(List.of(FixtureLoader.SOURCE_NAME));
        api = new WebApi(SearchSystem.load(parsedDir, historyFile));
    }

    @Test
    void statsReportFixturePages() {
        Map<String, Object> stats = api.stats();
        assertEquals(6, stats.get("pages"));
        assertTrue((Integer) stats.get("words") > 0);
        assertEquals(true, stats.get("fixturesOnly"));
    }

    @Test
    void problemsDropDownFollowsTheVehicle() {
        List<Map<String, Object>> ev = api.problems(Map.of("vehicle", "ev"));
        List<Map<String, Object>> gas = api.problems(Map.of("vehicle", "gas"));
        assertEquals(Map.of("id", "wont-start", "group", "Starting & battery", "label", "Car won't start",
                "text", "won't start", "category", "BATTERY", "commonRank", 0), ev.get(0));
        assertTrue(ev.stream().anyMatch(p -> p.get("id").equals("wont-charge")));
        assertTrue(ev.stream().noneMatch(p -> p.get("id").equals("check-engine")));
        assertTrue(gas.stream().anyMatch(p -> p.get("id").equals("check-engine")));
        assertTrue(gas.stream().noneMatch(p -> p.get("id").equals("wont-charge")));
        assertThrows(InvalidInputException.class, () -> api.problems(Map.of("vehicle", "boat")));
    }

    @Test
    void aDropDownPhraseIsUnderstoodBySearch() throws Exception {
        Map<String, Object> answer = api.search(Map.of("vehicle", "gas", "q", "headlight out"));
        assertEquals("Electrical problem", answer.get("category"));
    }

    @Test
    void categoriesFollowTheVehicle() {
        List<Map<String, Object>> ev = api.categories(Map.of("vehicle", "ev"));
        List<Map<String, Object>> gas = api.categories(Map.of("vehicle", "gas"));
        assertEquals("Find EV charging stations", ev.get(ev.size() - 1).get("label"));
        assertEquals("ev charging station", ev.get(ev.size() - 1).get("defaultQuery"));
        assertFalse(gas.stream().anyMatch(c -> c.get("name").equals("EV_CHARGING")));
        assertThrows(InvalidInputException.class, () -> api.categories(Map.of("vehicle", "boat")));
        assertThrows(InvalidInputException.class, () -> api.categories(Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchReturnsRankedResultsWithBreakdownAndContacts() throws Exception {
        Map<String, Object> answer = api.search(Map.of("vehicle", "gas", "category", "BRAKES",
                "q", "brake pads", "location", "Alberta"));

        assertEquals("brake pads", answer.get("query"));
        assertEquals("Alberta", answer.get("location"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) answer.get("results");
        Map<String, Object> top = results.get(0);
        assertEquals(1, top.get("rank"));
        assertEquals(CALGARY, top.get("url"));
        assertEquals(true, top.get("testFixture"));
        assertTrue(((String) top.get("breakdown")).contains("location +3"), (String) top.get("breakdown"));
        assertFalse(((List<String>) top.get("phones")).isEmpty(), "regex-extracted phone included");
        assertEquals("", answer.get("correctedQuery"));
    }

    @Test
    void emptyWordsUseTheCategoryName() throws Exception {
        assertEquals("brake problem repair", api.search(Map.of("vehicle", "gas", "category", "BRAKES")).get("query"));
    }

    @Test
    void misspelledSearchOffersCorrectedQuery() throws Exception {
        Map<String, Object> answer = api.search(Map.of("vehicle", "gas", "q", "breke"));
        assertEquals(Map.of("breke", List.of("brake", "broken")), answer.get("suggestions"));
        assertEquals("brake", answer.get("correctedQuery"));
    }

    @Test
    void searchIsSavedToHistoryImmediately() throws Exception {
        api.search(Map.of("vehicle", "ev", "q", "battery"));
        assertTrue(Files.readString(historyFile).contains("battery"));
        assertEquals("battery", api.history().get(0).get("term"));
        assertEquals(1, api.history().get(0).get("count"));
    }

    @Test
    void badSearchInputIsRejected() {
        assertThrows(InvalidInputException.class, () -> api.search(Map.of("vehicle", "gas", "q", "<script>")));
        assertThrows(InvalidInputException.class, () -> api.search(Map.of("vehicle", "gas", "q", "x", "location", "N9B 3P")));
        assertThrows(InvalidInputException.class, () -> api.search(Map.of("vehicle", "gas", "category", "NOPE")));
        assertThrows(InvalidInputException.class, () -> api.search(Map.of("vehicle", "gas", "category", "EV_CHARGING")),
                "EV-only category for a gas car");
        assertThrows(InvalidInputException.class, () -> api.search(Map.of("vehicle", "gas", "category", "OTHER")),
                "OTHER has no default words");
    }

    @Test
    void parseCategoryAcceptsAnyCaseAndBlank() {
        assertEquals(ServiceCategory.BATTERY, WebApi.parseCategory(" battery "));
        assertNull(WebApi.parseCategory(""));
        assertNull(WebApi.parseCategory(null));
    }

    @Test
    void completionAndSpellCheck() {
        assertTrue(api.complete(Map.of("prefix", "bat")).contains("battery"));
        assertEquals(List.of(), api.complete(Map.of()));

        Map<String, Object> known = api.spell(Map.of("word", "brake"));
        assertEquals(true, known.get("known"));
        Map<String, Object> typo = api.spell(Map.of("word", "battrey"));
        assertEquals(false, typo.get("known"));
        assertEquals(2, typo.get("maxDistance"));
        assertEquals(Map.of("word", "battery", "distance", 2, "frequency", 8),
                ((List<?>) typo.get("suggestions")).get(0));
        assertThrows(InvalidInputException.class, () -> api.spell(Map.of("word", "two words")));
        assertThrows(InvalidInputException.class, () -> api.spell(Map.of()));
    }

    @Test
    void matrixForCatToCut() {
        Map<String, Object> answer = api.matrix(Map.of("a", "Cat", "b", "cut"));
        assertEquals(1, answer.get("distance"));
        int[][] table = (int[][]) answer.get("matrix");
        assertEquals(4, table.length);
        assertEquals(3, table[3][0]);
        assertThrows(InvalidInputException.class, () -> api.matrix(Map.of("a", "cat")));
        assertThrows(InvalidInputException.class, () -> api.matrix(Map.of("a", "a".repeat(21), "b", "a")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagesAndFrequency() {
        assertEquals(6, api.pages().size());
        assertEquals(9, api.frequency(Map.of("url", CALGARY, "word", "brake")).get("count"));
        Map<String, Integer> top = (Map<String, Integer>) api.frequency(Map.of("url", CALGARY)).get("topTerms");
        assertEquals(9, top.get("brake"));
        assertThrows(InvalidInputException.class, () -> api.frequency(Map.of()));
        assertThrows(InvalidInputException.class, () -> api.frequency(Map.of("url", "https://nowhere.invalid/", "word", "x")));
    }

    @Test
    void constructorRejectsNull() {
        assertThrows(InvalidInputException.class, () -> new WebApi(null));
    }

    // ------------------------------------------------------------- problem descriptions

    @Test
    @SuppressWarnings("unchecked")
    void problemDescriptionIsTurnedIntoTheService() throws Exception {
        // "squealing when I brake" never appears on a page; the Calgary brake shop is still found
        Map<String, Object> answer = api.search(Map.of("vehicle", "gas", "q", "my brakes are squealing"));

        Map<String, Object> problem = (Map<String, Object>) answer.get("problem");
        assertEquals("my brakes are squealing", problem.get("text"));
        assertEquals("BRAKES", problem.get("category"));
        assertEquals("Brake problem", problem.get("label"));
        assertEquals(List.of("squealing"), problem.get("symptoms"));
        assertEquals("brake problem repair", answer.get("query"), "searches the service words");
        assertEquals("Brake problem", answer.get("category"));
        List<Map<String, Object>> results = (List<Map<String, Object>>) answer.get("results");
        assertEquals(CALGARY, results.get(0).get("url"));
        assertTrue(((Map<String, List<String>>) answer.get("suggestions")).isEmpty(),
                "no 'did you mean' for the user's own symptom words");
    }

    @Test
    void chosenCategoryWinsOverTheDescription() throws Exception {
        Map<String, Object> answer = api.search(Map.of("vehicle", "gas", "category", "BATTERY",
                "q", "brakes squealing"));
        assertNull(answer.get("problem"));
        assertEquals("brakes squealing", answer.get("query"));
    }

    @Test
    void plainServiceWordsAreSearchedAsTyped() throws Exception {
        // "brake pads" is not a symptom, so the normal word search runs
        Map<String, Object> answer = api.search(Map.of("vehicle", "gas", "q", "brake pads"));
        assertNull(answer.get("problem"));
        assertEquals("brake pads", answer.get("query"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void gasCarIsNeverSentToAnEvCharger() throws Exception {
        // "need to charge my car" is EV_CHARGING, which does not apply to a gas car
        Map<String, Object> gas = api.search(Map.of("vehicle", "gas", "q", "I need to charge my car"));
        Map<String, Object> ev = api.search(Map.of("vehicle", "ev", "q", "I need to charge my car"));
        assertNull(gas.get("problem"));
        assertEquals("EV_CHARGING", ((Map<String, Object>) ev.get("problem")).get("category"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherPossibleProblemsAreListed() throws Exception {
        Map<String, Object> answer = api.search(Map.of("vehicle", "gas",
                "q", "grinding noise when braking and the engine is overheating"));
        Map<String, Object> problem = (Map<String, Object>) answer.get("problem");
        assertEquals("BRAKES", problem.get("category"));
        List<Map<String, Object>> alternatives = (List<Map<String, Object>>) problem.get("alternatives");
        assertEquals("OVERHEATING_COOLING", alternatives.get(0).get("category"));
    }
}

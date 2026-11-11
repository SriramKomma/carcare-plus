package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;

class SearchEngineTest {

    @TempDir
    Path dir;

    private SearchFrequency frequency;
    private SearchEngine engine;

    private static ServiceDocument doc(int id, String path, String title, String body, VehicleType... vehicles) {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/" + path, "Example");
        doc.setId(id);
        doc.setTitle(title);
        doc.setBodyText(body);
        doc.setVehicleTypes(vehicles.length == 0 ? EnumSet.noneOf(VehicleType.class) : EnumSet.of(vehicles[0], vehicles));
        return doc;
    }

    @BeforeEach
    void setUp() {
        // invented test documents
        InvertedIndex index = InvertedIndex.of(List.of(
                doc(0, "battery", "Battery replacement", "Car batteries tested free.", VehicleType.EV, VehicleType.GAS),
                doc(1, "charger", "EV charging station", "350 kW chargers. EV battery friendly.", VehicleType.EV),
                doc(2, "oil", "Oil change", "Engine oil and filters.", VehicleType.GAS),
                doc(3, "about", "About us", "Family shop with battery service.")));
        frequency = new SearchFrequency(dir.resolve("history.txt"));
        // a fake suggester so the engine can be tested before the real spell checker exists
        SpellingSuggester fake = (word, max) -> word.equals("battrey") ? List.of("battery") : List.of();
        engine = new SearchEngine(index, frequency, fake);
    }

    private SearchOutcome search(String query, VehicleType vehicle) {
        return engine.search(SearchRequest.of(query, vehicle));
    }

    private static List<String> paths(SearchOutcome outcome) {
        return outcome.results().stream()
                .map(r -> r.document().getUrl().replace("https://www.example.ca/", "")).toList();
    }

    @Test
    void findsAndRanksMatchingPages() {
        SearchOutcome outcome = search("Battery", VehicleType.GAS);
        assertEquals(List.of("battery"), outcome.terms());
        assertEquals(List.of("battery", "about"), paths(outcome));
        assertTrue(outcome.hasResults());
    }

    @Test
    void vehicleTypeFiltersPages() {
        // "about" and "charger" both score 1 with no title hits -> URL order decides
        assertEquals(List.of("battery", "about", "charger"), paths(search("battery", VehicleType.EV)));
        assertEquals(List.of("oil"), paths(search("oil", VehicleType.GAS)));
        assertEquals(List.of(), paths(search("oil", VehicleType.EV)), "oil change page is gas-only");
    }

    @Test
    void candidateCountIsCountedAfterVehicleFilter() {
        assertEquals(2, search("battery", VehicleType.GAS).candidateCount());
        assertEquals(3, search("battery", VehicleType.EV).candidateCount());
    }

    @Test
    void pluralQueryFindsSingularPagesAndTheOtherWayRound() {
        assertEquals(paths(search("battery", VehicleType.GAS)), paths(search("Batteries", VehicleType.GAS)));
        assertEquals(List.of("charger"), paths(search("charger", VehicleType.EV)));
    }

    @Test
    void maxResultsIsRespected() {
        SearchOutcome outcome = engine.search(new SearchRequest("battery", VehicleType.EV, null, "", 1));
        assertEquals(1, outcome.results().size());
        assertEquals(3, outcome.candidateCount());
    }

    @Test
    void searchedTermsAreCounted() {
        search("battery replacement", VehicleType.GAS);
        search("Batteries", VehicleType.GAS);
        assertEquals(2, frequency.count("battery"));
        assertEquals(1, frequency.count("replacement"));
    }

    @Test
    void unknownWordGetsSpellingSuggestion() {
        SearchOutcome outcome = search("battrey", VehicleType.GAS);
        assertFalse(outcome.hasResults());
        assertEquals(Map.of("battrey", List.of("battery")), outcome.suggestions());
    }

    @Test
    void knownWordsGetNoSuggestionsEvenWhenOtherWordsAreUnknown() {
        SearchOutcome outcome = search("battery battrey xyzzy", VehicleType.GAS);
        assertTrue(outcome.hasResults());
        assertEquals(Map.of("battrey", List.of("battery")), outcome.suggestions(), "xyzzy has no suggestion");
    }

    @Test
    void queryOfOnlyCommonWordsSearchesNothing() {
        SearchOutcome outcome = search("the and of", VehicleType.GAS);
        assertTrue(outcome.hadNoSearchableTerms());
        assertFalse(outcome.hasResults());
        assertEquals(0, frequency.distinctTerms(), "nothing recorded");
    }

    @Test
    void queryIsValidatedAndCleaned() {
        assertEquals("battery replacement", search("  battery    replacement ", VehicleType.GAS).query());
        assertThrows(InvalidInputException.class, () -> search("<script>", VehicleType.GAS));
        assertThrows(InvalidInputException.class, () -> engine.search(null));
    }

    @Test
    void worksWithoutFrequencyAndSuggester() {
        SearchEngine bare = new SearchEngine(InvertedIndex.of(List.of(doc(0, "x", "Battery", ""))), null, null);
        SearchOutcome outcome = bare.search(SearchRequest.of("battrey", VehicleType.GAS));
        assertEquals(Map.of(), outcome.suggestions());
        List<SearchResult> results = bare.search(SearchRequest.of("battery", VehicleType.GAS)).results();
        assertEquals(1, results.size());
    }

    @Test
    void outcomeListsAreReadOnly() {
        SearchOutcome outcome = search("battery", VehicleType.GAS);
        assertThrows(UnsupportedOperationException.class, () -> outcome.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> outcome.terms().clear());
    }
}

package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.search.SearchOutcome;

class ResultFormatterTest {

    private static final String NL = System.lineSeparator();

    /** Invented test document. */
    private static ServiceDocument doc(boolean fixture) {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/brakes", "Example");
        doc.setTitle("Brake Repair");
        doc.setPhones(List.of("(403) 555-0177"));
        doc.setPostalCodes(List.of("T2P 1A1"));
        doc.setProvince("AB");
        doc.setTestFixture(fixture);
        return doc;
    }

    @Test
    void resultShowsTitleScoreUrlWhyCountsAndContact() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("brake", 9);
        counts.put("pad", 2);
        String text = ResultFormatter.formatResult(1, new SearchResult(doc(false), 33, counts, "title 1x5 = 33"));

        assertEquals("1. Brake Repair  (score 33)" + NL
                + "   https://www.example.ca/brakes" + NL
                + "   Why: title 1x5 = 33" + NL
                + "   Word counts on this page: brake=9, pad=2" + NL
                + "   Phone (403) 555-0177  |  T2P 1A1 AB" + NL, text);
    }

    @Test
    void fixturesAreLabelled() {
        String text = ResultFormatter.formatResult(2, new SearchResult(doc(true), 5, Map.of(), ""));
        assertTrue(text.startsWith("2. [TEST FIXTURE] Brake Repair"), text);
    }

    @Test
    void noResultsAndSuggestions() {
        SearchRequest request = new SearchRequest("battrey", VehicleType.EV, null, "Windsor", 10);
        SearchOutcome outcome = new SearchOutcome("battrey", List.of("battrey"), 0, List.of(),
                Map.of("battrey", List.of("battery", "batter")));

        String text = ResultFormatter.format(request, outcome);

        assertTrue(text.startsWith("Search \"battrey\" for Electric Vehicle (EV) near Windsor"), text);
        assertTrue(text.contains("No results found in Windsor."), text);
        assertTrue(text.contains("Did you mean: battrey -> battery, batter"), text);
    }

    @Test
    void onlyCommonWords() {
        SearchRequest request = SearchRequest.of("the and", VehicleType.GAS);
        String text = ResultFormatter.format(request, new SearchOutcome("the and", List.of(), 0, List.of(), Map.of()));
        assertTrue(text.contains("only has very common words"), text);
        assertFalse(text.contains("No results found."));
    }

    @Test
    void scoresAreShownWithoutPointZero() {
        assertEquals("28", ResultFormatter.formatScore(28.0));
        assertEquals("2.50", ResultFormatter.formatScore(2.5));
    }
}

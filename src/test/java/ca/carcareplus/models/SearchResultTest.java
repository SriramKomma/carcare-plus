package ca.carcareplus.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;

class SearchResultTest {

    private final ServiceDocument doc = new ServiceDocument("https://www.example.ca/battery", "Example");

    @Test
    void keepsTermOrderAndTotals() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("battery", 5);
        counts.put("repair", 3);

        SearchResult result = new SearchResult(doc, 22.0, counts, " title 1x5 + body 7x1 ");

        assertEquals(List.of("battery", "repair"), List.copyOf(result.termCounts().keySet()));
        assertEquals(8, result.totalOccurrences());
        assertEquals("title 1x5 + body 7x1", result.scoreBreakdown());
    }

    @Test
    void termCountsAreCopiedAndReadOnly() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("tire", 2);
        SearchResult result = new SearchResult(doc, 2.0, counts, "");

        counts.put("brake", 9);
        assertEquals(1, result.termCounts().size());
        assertThrows(UnsupportedOperationException.class, () -> result.termCounts().put("x", 1));
    }

    @Test
    void nullMapAndBreakdownBecomeEmpty() {
        SearchResult result = new SearchResult(doc, 0.0, null, null);
        assertTrue(result.termCounts().isEmpty());
        assertEquals(0, result.totalOccurrences());
        assertEquals("", result.scoreBreakdown());
    }

    @Test
    void rejectsMissingDocument() {
        assertThrows(InvalidInputException.class, () -> new SearchResult(null, 1.0, Map.of(), ""));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, Double.NaN})
    void rejectsInvalidScore(double score) {
        assertThrows(InvalidInputException.class, () -> new SearchResult(doc, score, Map.of(), ""));
    }
}

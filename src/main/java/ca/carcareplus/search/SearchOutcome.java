package ca.carcareplus.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.carcareplus.models.SearchResult;

/**
 * Everything one search produced, ready for the console to display.
 *
 * @param query          the query after validation (trimmed, single spaces)
 * @param terms          the search terms after tokenizing (stop words removed)
 * @param candidateCount pages that contain at least one term and suit the vehicle type
 * @param results        best pages first (at most the number requested)
 * @param suggestions    for each term that no page contains: spelling suggestions,
 *                       e.g. "battrey" -> ["battery"]; terms without suggestions are left out
 * @param outsideLocation pages that matched the words but are not in the typed location
 *                       (so the console can say "none in Windsor, 12 elsewhere")
 */
public record SearchOutcome(
        String query,
        List<String> terms,
        int candidateCount,
        List<SearchResult> results,
        Map<String, List<String>> suggestions,
        int outsideLocation) {

    /** Outcome of a search without a location. */
    public SearchOutcome(String query, List<String> terms, int candidateCount,
                         List<SearchResult> results, Map<String, List<String>> suggestions) {
        this(query, terms, candidateCount, results, suggestions, 0);
    }

    public SearchOutcome {
        terms = terms == null ? List.of() : List.copyOf(terms);
        results = results == null ? List.of() : List.copyOf(results);
        suggestions = suggestions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(suggestions));
    }

    public boolean hasResults() {
        return !results.isEmpty();
    }

    /** True if the query had only common words ("the", "and", ...), so nothing could be searched. */
    public boolean hadNoSearchableTerms() {
        return terms.isEmpty();
    }
}

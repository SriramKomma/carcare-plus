package ca.carcareplus.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * One ranked answer to a search, produced by PageRanker.
 *
 * @param document       the matching service document
 * @param score          relevance score (higher = better); never negative
 * @param termCounts     how often each query term occurs in this document's URL,
 *                       in the order the user typed the terms (frequency count feature)
 * @param scoreBreakdown human-readable explanation, e.g. "title 1x5 + body 7x1 = 12"
 */
public record SearchResult(
        ServiceDocument document,
        double score,
        Map<String, Integer> termCounts,
        String scoreBreakdown) {

    public SearchResult {
        if (document == null) {
            throw new InvalidInputException("SearchResult needs a document.");
        }
        if (Double.isNaN(score) || score < 0) {
            throw new InvalidInputException("Score must be a number 0 or greater: " + score);
        }
        // LinkedHashMap keeps the user's term order; the read-only wrapper keeps the record immutable
        termCounts = termCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(termCounts));
        scoreBreakdown = scoreBreakdown == null ? "" : scoreBreakdown.trim();
    }

    /** Sum of all query-term occurrences in this document. */
    public int totalOccurrences() {
        int total = 0;
        for (Integer count : termCounts.values()) {
            if (count != null) {
                total += count;
            }
        }
        return total;
    }
}

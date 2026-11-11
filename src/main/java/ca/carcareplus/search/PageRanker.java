package ca.carcareplus.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.search.InvertedIndex.Field;

/**
 * Page ranking feature: orders matching pages by how relevant they are.
 *
 * Score of a page (small whole numbers, so it can be checked by hand):
 * <pre>
 *   for every query word:  5 x (times in title) + 3 x (times in headings)
 *                        + 2 x (times in description) + 1 x (times in body)
 *   + 10 if the whole query appears as an exact phrase (2 or more words)
 *   +  5 if the page has the category the user chose
 *   +  3 if the page is in the location the user typed
 * </pre>
 * Why these weights: a word in the title says what the page is ABOUT; a word in
 * the body may be a side remark. Each result carries a breakdown such as
 * "title 1x5 + body 7x1 + phrase +10 = 22".
 *
 * This is keyword relevance, NOT Google's PageRank (which scores pages by the
 * links between them).
 *
 * Choosing the best k of n pages uses the TopK min-heap: O(n log k).
 * Ties: more title hits first, then URL alphabetically, so the order never changes
 * between runs.
 */
public class PageRanker {

    public static final int TITLE_WEIGHT = 5;
    public static final int HEADING_WEIGHT = 3;
    public static final int DESCRIPTION_WEIGHT = 2;
    public static final int BODY_WEIGHT = 1;
    public static final int PHRASE_BONUS = 10;
    public static final int CATEGORY_BONUS = 5;
    public static final int LOCATION_BONUS = 3;

    /** Score of one page plus what is needed to explain and order it. */
    private record Scored(ServiceDocument doc, int score, int titleHits,
                          Map<String, Integer> termCounts, String breakdown) {
    }

    /** Best first: higher score, then more title hits, then URL A-Z. */
    private static final Comparator<Scored> BEST_FIRST =
            Comparator.comparingInt(Scored::score).reversed()
                    .thenComparing(Comparator.comparingInt(Scored::titleHits).reversed())
                    .thenComparing(s -> s.doc().getUrl());

    private final InvertedIndex index;

    public PageRanker(InvertedIndex index) {
        if (index == null) {
            throw new InvalidInputException("PageRanker needs an index.");
        }
        this.index = index;
    }

    /**
     * @param candidates ids of pages that contain at least one query term
     * @param terms      the query, already tokenized, in the user's order
     * @param request    used for the category and location bonuses
     * @param k          how many results to return
     * @return at most k results, best first
     */
    public List<SearchResult> rank(Collection<Integer> candidates, List<String> terms, SearchRequest request, int k) {
        return toResults(TopK.best(scoreAll(candidates, terms, request), k, BEST_FIRST));
    }

    /**
     * Same answer as rank, but sorts ALL candidates (O(n log n)). Kept only to
     * compare speed with the heap in the performance experiments and to check
     * in tests that both give identical results.
     */
    public List<SearchResult> rankWithFullSort(Collection<Integer> candidates, List<String> terms,
                                               SearchRequest request, int k) {
        List<Scored> all = scoreAll(candidates, terms, request);
        all.sort(BEST_FIRST);
        return toResults(all.subList(0, Math.min(Math.max(k, 0), all.size())));
    }

    private List<Scored> scoreAll(Collection<Integer> candidates, List<String> terms, SearchRequest request) {
        if (candidates == null || terms == null || request == null) {
            throw new InvalidInputException("Candidates, terms and request are required.");
        }
        List<Scored> scored = new ArrayList<>();
        for (int id : candidates) {
            ServiceDocument doc = index.document(id);
            if (doc != null) {
                scored.add(score(id, doc, terms, request));
            }
        }
        return scored;
    }

    /** Computes the score of one page, with a readable breakdown. */
    private Scored score(int id, ServiceDocument doc, List<String> terms, SearchRequest request) {
        int title = 0;
        int headings = 0;
        int description = 0;
        int body = 0;
        Map<String, Integer> termCounts = new LinkedHashMap<>();
        // LinkedHashSet: a word typed twice ("brake brake") is only counted once
        for (String term : new LinkedHashSet<>(terms)) {
            title += index.termFrequency(term, id, Field.TITLE);
            headings += index.termFrequency(term, id, Field.HEADINGS);
            description += index.termFrequency(term, id, Field.DESCRIPTION);
            body += index.termFrequency(term, id, Field.BODY);
            termCounts.put(term, index.termFrequency(term, id));
        }

        List<String> parts = new ArrayList<>();
        int score = 0;
        score += part(parts, "title", title, TITLE_WEIGHT);
        score += part(parts, "headings", headings, HEADING_WEIGHT);
        score += part(parts, "description", description, DESCRIPTION_WEIGHT);
        score += part(parts, "body", body, BODY_WEIGHT);
        if (terms.size() >= 2 && index.containsPhrase(id, terms)) {
            score += bonus(parts, "phrase", PHRASE_BONUS);
        }
        if (request.hasCategory() && doc.hasCategory(request.category())) {
            score += bonus(parts, "category", CATEGORY_BONUS);
        }
        if (request.hasLocation() && LocationMatcher.matches(doc, request.location(), index)) {
            score += bonus(parts, "location", LOCATION_BONUS);
        }
        String breakdown = (parts.isEmpty() ? "0" : String.join(" + ", parts)) + " = " + score;
        return new Scored(doc, score, title, termCounts, breakdown);
    }

    private static int part(List<String> parts, String name, int hits, int weight) {
        if (hits > 0) {
            parts.add(name + " " + hits + "x" + weight);
        }
        return hits * weight;
    }

    private static int bonus(List<String> parts, String name, int points) {
        parts.add(name + " +" + points);
        return points;
    }

    private static List<SearchResult> toResults(List<Scored> scored) {
        List<SearchResult> results = new ArrayList<>();
        for (Scored s : scored) {
            results.add(new SearchResult(s.doc(), s.score(), s.termCounts(), s.breakdown()));
        }
        return results;
    }
}

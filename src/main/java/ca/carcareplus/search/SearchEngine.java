package ca.carcareplus.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.validation.DataValidator;

/**
 * Runs one search from start to finish:
 * <pre>
 * 1. validate     DataValidator.validateQuery  (bad characters, too long -> InvalidInputException)
 * 2. tokenize     same Tokenizer as the index  ("Brake Pads" -> brake, pad)
 * 3. record       SearchFrequency counts each term   (search frequency feature)
 * 4. look up      InvertedIndex.lookupAny          (pages with at least one term)
 * 5. filter       keep pages that suit the vehicle type (EV / Gas) and, if a
 *                 location was typed, are in that place (LocationMatcher); with
 *                 "near me", only places within the radius (GeoPoint.distanceKm)
 * 6. rank         PageRanker, best k                  (page ranking feature)
 * 7. suggest      for terms no page contains, ask the SpellingSuggester
 * </pre>
 * Step 4 uses OR (any term) rather than AND (all terms): a page matching only
 * "battery" of "battery alternator" is still useful, and the ranker puts pages
 * matching more of the words first.
 */
public class SearchEngine {

    /** Spelling suggestions shown per unknown word. */
    static final int SUGGESTIONS_PER_WORD = 3;

    private final InvertedIndex index;
    private final PageRanker ranker;
    private final SearchFrequency searchFrequency;
    private final SpellingSuggester suggester;

    /**
     * @param searchFrequency where searches are counted; null means "do not count"
     * @param suggester       spelling suggestions; null means none
     */
    public SearchEngine(InvertedIndex index, SearchFrequency searchFrequency, SpellingSuggester suggester) {
        if (index == null) {
            throw new InvalidInputException("SearchEngine needs an index.");
        }
        this.index = index;
        this.ranker = new PageRanker(index);
        this.searchFrequency = searchFrequency;
        this.suggester = suggester == null ? SpellingSuggester.NONE : suggester;
    }

    /**
     * @throws InvalidInputException if the request is null or the query has
     *         characters that are not allowed (message can be shown to the user)
     */
    public SearchOutcome search(SearchRequest request) {
        if (request == null) {
            throw new InvalidInputException("Search request is required.");
        }
        String query = DataValidator.validateQuery(request.query());
        List<String> terms = Tokenizer.tokenize(query);
        if (terms.isEmpty()) {
            return new SearchOutcome(query, terms, 0, List.of(), Map.of());
        }
        if (searchFrequency != null) {
            searchFrequency.recordAll(terms);
        }

        Set<Integer> candidates = new TreeSet<>();
        int outsideLocation = 0;
        for (int id : index.lookupAny(terms)) {
            ServiceDocument doc = index.document(id);
            if (!doc.appliesTo(request.vehicleType())) {
                continue;
            }
            // A typed location is a filter: only pages in that place are results
            if (request.hasLocation() && !LocationMatcher.matches(doc, request.location(), index)) {
                outsideLocation++;
                continue;
            }
            // "Use my location": only places with a known position inside the radius
            if (request.hasNear() && !(doc.hasLocation()
                    && doc.getLocation().distanceKm(request.near()) <= request.radiusKm())) {
                outsideLocation++;
                continue;
            }
            candidates.add(id);
        }
        List<SearchResult> results = ranker.rank(candidates, terms, request, request.maxResults());
        return new SearchOutcome(query, terms, candidates.size(), results, suggestionsFor(terms), outsideLocation);
    }

    /** Suggestions for every term that no indexed page contains. */
    private Map<String, List<String>> suggestionsFor(List<String> terms) {
        Map<String, List<String>> suggestions = new LinkedHashMap<>();
        for (String term : terms) {
            if (index.documentFrequency(term) == 0 && !suggestions.containsKey(term)) {
                List<String> words = suggester.suggest(term, SUGGESTIONS_PER_WORD);
                if (words != null && !words.isEmpty()) {
                    suggestions.put(term, List.copyOf(words));
                }
            }
        }
        return suggestions;
    }

    public InvertedIndex index() {
        return index;
    }
}

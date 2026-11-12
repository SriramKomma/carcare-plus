package ca.carcareplus.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.search.SpellingSuggester;
import ca.carcareplus.search.TopK;
import ca.carcareplus.search.Tokenizer;

/**
 * Spell checking feature: for a word that is not in the vocabulary (built from
 * the crawled text files), suggests the closest real words.
 *
 * For every vocabulary word, the edit distance to the typed word is computed
 * (with the early-exit version, so most comparisons stop after a step or two).
 * Suggestions are ordered by: smaller distance first, then more frequent in the
 * pages, then alphabetically. Cost: O(W * m * n) worst case for W words, but the
 * length filter skips most words immediately.
 *
 * Plugs into SearchEngine through the SpellingSuggester interface.
 */
public class SpellChecker implements SpellingSuggester {

    /** Words of up to this length allow 1 edit; longer words allow 2. */
    static final int SHORT_WORD_LENGTH = 4;

    /** A possible correction. */
    public record Suggestion(String word, int distance, int frequency) {
    }

    private static final Comparator<Suggestion> BEST_FIRST =
            Comparator.comparingInt(Suggestion::distance)
                    .thenComparing(Comparator.comparingInt(Suggestion::frequency).reversed())
                    .thenComparing(Suggestion::word);

    private final Map<String, Integer> vocabulary;

    /** @param vocabulary word -> frequency, from Vocabulary */
    public SpellChecker(Map<String, Integer> vocabulary) {
        if (vocabulary == null) {
            throw new InvalidInputException("SpellChecker needs a vocabulary.");
        }
        this.vocabulary = new HashMap<>(vocabulary);
    }

    /** True if the word (normalized like page text) occurs in the pages. */
    public boolean isKnown(String word) {
        String term = Tokenizer.normalizeTerm(word);
        return term != null && vocabulary.containsKey(term);
    }

    /**
     * Allowed edits for a word: 1 for short words, 2 for longer ones. With 2 edits,
     * a 3-letter word like "car" would match "bar", "cap", "jar", ... -- too many
     * unrelated suggestions.
     */
    public static int defaultMaxDistance(String word) {
        return word == null || word.length() <= SHORT_WORD_LENGTH ? 1 : 2; // null counts as ""
    }

    /**
     * Suggestions for the SearchEngine, using the default number of allowed edits.
     */
    @Override
    public List<String> suggest(String word, int max) {
        String term = Tokenizer.normalizeTerm(word);
        if (term == null) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (Suggestion suggestion : suggestions(term, defaultMaxDistance(term), max)) {
            words.add(suggestion.word());
        }
        return words;
    }

    /**
     * @param word        the typed word
     * @param maxDistance largest edit distance allowed
     * @param k           most suggestions wanted
     * @return closest vocabulary words, best first; empty if the word is empty,
     *         already correct, or nothing is close enough
     */
    public List<Suggestion> suggestions(String word, int maxDistance, int k) {
        String term = Tokenizer.normalizeTerm(word);
        if (term == null || vocabulary.containsKey(term) || k <= 0) {
            return List.of();
        }
        List<Suggestion> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : vocabulary.entrySet()) {
            String candidate = entry.getKey();
            if (isNumber(candidate)) {
                continue; // "350" is not a useful correction for a misspelled word
            }
            int distance = LevenshteinDistance.distanceWithin(term, candidate, maxDistance);
            if (distance <= maxDistance) {
                candidates.add(new Suggestion(candidate, distance, entry.getValue()));
            }
        }
        return TopK.best(candidates, k, BEST_FIRST);
    }

    public int vocabularySize() {
        return vocabulary.size();
    }

    private static boolean isNumber(String word) {
        return word.chars().allMatch(Character::isDigit);
    }
}

package ca.carcareplus.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Splits text into search terms. The SAME rules are used for page text (when
 * indexing) and for the user's query (when searching); otherwise "Tyres" on a page
 * and "tire" in a query would never meet.
 *
 * Rules, in order:
 * 1. lower case:                        "Brake-Pads"  -> "brake-pads"
 * 2. accents removed:                   "Montréal"    -> "montreal"
 *    apostrophes removed:               "driver's"    -> "drivers"
 * 3. split on anything not a-z or 0-9:  "brake-pads"  -> "brake", "pads"
 * 4. drop very common "stop words" (the, and, for, ...) that match every page
 * 5. drop single letters (but keep single digits such as the 2 in "level 2")
 *    and anything longer than {@value #MAX_TERM_LENGTH} characters (tracking ids, hashes)
 * 6. spelling synonyms:                 "tyre" -> "tire", "centre" -> "center"
 *
 * Not done on purpose: stemming ("brakes" -> "brake"). Simple suffix rules break
 * words like "chademo" or "gas", and a real stemmer (Porter) is a project of its own.
 * Word completion and spell checking help the user find the right form instead.
 *
 * Cost: one pass over the text, O(n).
 */
public final class Tokenizer {

    static final int MAX_TERM_LENGTH = 30;

    /** Words that appear on almost every page and say nothing about the service. */
    static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "for", "from",
            "has", "have", "if", "in", "into", "is", "it", "its", "of", "on", "or", "our",
            "so", "than", "that", "the", "their", "then", "there", "these", "this", "to",
            "us", "was", "we", "were", "will", "with", "you", "your");

    /** British/Canadian spellings mapped to one form so both find the same pages. */
    static final Map<String, String> SYNONYMS = Map.of(
            "tyre", "tire",
            "tyres", "tires",
            "centre", "center",
            "centres", "centers",
            "kilowatt", "kw",
            "kilowatts", "kw");

    private Tokenizer() {
        // static helper methods only
    }

    /**
     * @return the terms of the text in order; empty list for null or empty text
     */
    public static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        // NFD splits "é" into "e" + an accent mark; \p{M} then removes the marks.
        // ’ is the curly apostrophe many web pages use.
        String prepared = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("['\\u2019]", "");
        for (String raw : prepared.split("[^a-z0-9]+")) {
            String term = keepOrDrop(raw);
            if (term != null) {
                terms.add(term);
            }
        }
        return terms;
    }

    /**
     * Normalizes one word the way tokenize would.
     *
     * @return the term, or null if tokenize would drop it (stop word, too short, ...)
     */
    public static String normalizeTerm(String word) {
        List<String> terms = tokenize(word);
        return terms.size() == 1 ? terms.get(0) : null;
    }

    private static String keepOrDrop(String raw) {
        if (raw.isEmpty() || raw.length() > MAX_TERM_LENGTH || STOP_WORDS.contains(raw)) {
            return null;
        }
        if (raw.length() == 1 && !Character.isDigit(raw.charAt(0))) {
            return null;
        }
        return SYNONYMS.getOrDefault(raw, raw);
    }
}

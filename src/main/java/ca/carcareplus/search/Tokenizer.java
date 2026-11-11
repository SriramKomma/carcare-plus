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
 * 6. one form per word (WORD_FORMS):    "tyres" -> "tire", "batteries" -> "battery"
 *
 * Why a fixed list instead of a stemmer? Without it, "brakes" and "brake" would be
 * different terms and a search for one would miss pages using the other. A general
 * rule like "remove a final s" breaks words such as "gas", "bus" or "chademos", and
 * a full stemmer (Porter) is a project of its own. A short list of the plurals that
 * matter for car services is predictable, easy to check and easy to extend.
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

    /**
     * Word -> the one form we index: plurals -> singular, British/Canadian
     * spellings -> one spelling, unit names -> unit symbol. Only words in this
     * list are changed. To add a word, add both its plural and any spelling variant.
     */
    static final Map<String, String> WORD_FORMS = Map.ofEntries(
            // spelling variants
            Map.entry("tyre", "tire"), Map.entry("tyres", "tire"),
            Map.entry("centre", "center"), Map.entry("centres", "center"),
            Map.entry("kilowatt", "kw"), Map.entry("kilowatts", "kw"),
            // irregular plurals
            Map.entry("batteries", "battery"),
            // regular plurals of car-service words
            Map.entry("tires", "tire"), Map.entry("brakes", "brake"), Map.entry("pads", "pad"),
            Map.entry("rotors", "rotor"), Map.entry("calipers", "caliper"), Map.entry("wipers", "wiper"),
            Map.entry("blades", "blade"), Map.entry("chargers", "charger"), Map.entry("stations", "station"),
            Map.entry("connectors", "connector"), Map.entry("plugs", "plug"), Map.entry("services", "service"),
            Map.entry("repairs", "repair"), Map.entry("headlights", "headlight"), Map.entry("lights", "light"),
            Map.entry("fuses", "fuse"), Map.entry("filters", "filter"), Map.entry("belts", "belt"),
            Map.entry("hoses", "hose"), Map.entry("alternators", "alternator"), Map.entry("starters", "starter"),
            Map.entry("engines", "engine"), Map.entry("radiators", "radiator"), Map.entry("pumps", "pump"),
            Map.entry("mufflers", "muffler"), Map.entry("wheels", "wheel"), Map.entry("rims", "rim"),
            Map.entry("vehicles", "vehicle"), Map.entry("cars", "car"), Map.entry("trucks", "truck"),
            Map.entry("stores", "store"), Map.entry("locations", "location"), Map.entry("centers", "center"));

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
        for (String raw : simplify(text).split("[^a-z0-9]+")) {
            String term = keepOrDrop(raw);
            if (term != null) {
                terms.add(term);
            }
        }
        return terms;
    }

    /**
     * Rules 1 and 2 only: lower case, accents and apostrophes removed.
     * "Driver’s Montréal" -> "drivers montreal". Also used by word completion,
     * which must clean a half-typed word without dropping or changing it.
     */
    public static String simplify(String text) {
        if (text == null) {
            return "";
        }
        // NFD splits "é" into "e" + an accent mark; \p{M} then removes the marks.
        // \u2019 is the curly apostrophe many web pages use.
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("['\\u2019]", "");
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
        return WORD_FORMS.getOrDefault(raw, raw);
    }
}

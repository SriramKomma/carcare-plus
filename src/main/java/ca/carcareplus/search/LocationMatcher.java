package ca.carcareplus.search;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.validation.DataValidator;

/**
 * Decides whether a page is in the place the user typed: a postal code, a
 * province, or a city. SearchEngine uses it to keep only pages in that place;
 * PageRanker shows it as "location +3" in the score breakdown.
 *
 * Rules, tried in this order:
 * 1. Postal code ("N9B 3P4"): the page has a postal code in the same area,
 *    i.e. with the same first three characters ("N9B", the forward sortation area).
 * 2. Province code or name ("ON", "Ontario", "Québec"): the page's province is the same.
 * 3. "City, Province" ("Windsor, ON"): rule 4 for the city AND the province must match.
 * 4. Anything else is a city. If the page has a city field (OpenStreetMap shops
 *    always do), it must be the same city, ignoring case and accents
 *    ("Windsor" never matches a shop on "Windsor Street" in Toronto). Pages
 *    without a city field (web pages) match if every word of the place name
 *    occurs on the page.
 */
public final class LocationMatcher {

    /** Province and territory names (as tokenized) and codes -> two-letter code. */
    static final Map<String, String> PROVINCES = Map.ofEntries(
            Map.entry("newfoundland labrador", "NL"), Map.entry("newfoundland", "NL"), Map.entry("nl", "NL"),
            Map.entry("nova scotia", "NS"), Map.entry("ns", "NS"),
            Map.entry("prince edward island", "PE"), Map.entry("pei", "PE"), Map.entry("pe", "PE"),
            Map.entry("new brunswick", "NB"), Map.entry("nb", "NB"),
            Map.entry("quebec", "QC"), Map.entry("qc", "QC"),
            Map.entry("ontario", "ON"), Map.entry("on", "ON"),
            Map.entry("manitoba", "MB"), Map.entry("mb", "MB"),
            Map.entry("saskatchewan", "SK"), Map.entry("sk", "SK"),
            Map.entry("alberta", "AB"), Map.entry("ab", "AB"),
            Map.entry("british columbia", "BC"), Map.entry("bc", "BC"),
            Map.entry("northwest territories", "NT"), Map.entry("nt", "NT"),
            Map.entry("nunavut", "NU"), Map.entry("nu", "NU"),
            Map.entry("yukon", "YT"), Map.entry("yt", "YT"));

    private LocationMatcher() {
        // static helper methods only
    }

    /**
     * @param index used for rule 3 (are the city's words on the page?)
     * @return true if the document is in the given location; false for an empty location
     */
    public static boolean matches(ServiceDocument doc, String location, InvertedIndex index) {
        if (doc == null || location == null || location.isBlank()) {
            return false;
        }
        String postalCode = DataValidator.normalizePostalCode(location);
        if (postalCode != null) {
            String area = postalCode.substring(0, 3);
            return doc.getPostalCodes().stream().anyMatch(code -> code.startsWith(area));
        }
        String province = provinceCode(location);
        if (province != null) {
            return province.equals(doc.getProvince());
        }
        int comma = location.lastIndexOf(',');
        if (comma > 0) {
            String provincePart = provinceCode(location.substring(comma + 1));
            if (provincePart != null) {
                boolean sameProvince = doc.getProvince().isEmpty() || provincePart.equals(doc.getProvince());
                return sameProvince && isInCity(doc, location.substring(0, comma), index);
            }
        }
        return isInCity(doc, location, index);
    }

    /**
     * @return two-letter code for a province name or code, or null if it is not one.
     *         The code is checked before tokenizing, because "on" is a stop word.
     */
    static String provinceCode(String location) {
        String trimmed = location.trim().toLowerCase(Locale.ROOT);
        if (PROVINCES.containsKey(trimmed)) {
            return PROVINCES.get(trimmed);
        }
        return PROVINCES.get(String.join(" ", Tokenizer.tokenize(location)));
    }

    private static boolean isInCity(ServiceDocument doc, String city, InvertedIndex index) {
        if (!doc.getCity().isEmpty()) {
            return sameName(doc.getCity(), city);
        }
        List<String> words = Tokenizer.tokenize(city);
        return !words.isEmpty() && doc.hasId() && index.lookupAll(words).contains(doc.getId());
    }

    /** "Sault Ste. Marie" equals "sault ste marie"; "Gatineau" equals "Gatineau ". */
    static boolean sameName(String a, String b) {
        return Tokenizer.simplify(a).replaceAll("[^a-z0-9]+", " ").trim()
                .equals(Tokenizer.simplify(b).replaceAll("[^a-z0-9]+", " ").trim());
    }
}

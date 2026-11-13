package ca.carcareplus.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.hours.Availability;
import ca.carcareplus.osm.OsmImporter;
import ca.carcareplus.search.SearchOutcome;

/**
 * Turns search results into text for the console. Kept separate from Main (and
 * later ConsoleMenu) so the wording can be unit tested and reused.
 */
public final class ResultFormatter {

    static final String FIXTURE_LABEL = "[TEST FIXTURE] ";

    private ResultFormatter() {
        // static helper methods only
    }

    /** Full text for one search: header, numbered results, suggestions. */
    public static String format(SearchRequest request, SearchOutcome outcome) {
        StringBuilder out = new StringBuilder();
        out.append("Search \"").append(outcome.query()).append("\" for ")
                .append(request.vehicleType().displayName());
        if (request.hasLocation()) {
            out.append(" near ").append(request.location());
        }
        out.append(System.lineSeparator());

        if (outcome.hadNoSearchableTerms()) {
            out.append("Your search only has very common words (like \"the\" or \"and\"). Try more specific words.")
                    .append(System.lineSeparator());
            return out.toString();
        }
        out.append("Search terms: ").append(String.join(", ", outcome.terms()))
                .append("  |  matching pages: ").append(outcome.candidateCount())
                .append(System.lineSeparator());

        if (!outcome.hasResults()) {
            out.append(noResultsMessage(request, outcome)).append(System.lineSeparator());
        }
        int rank = 1;
        for (SearchResult result : outcome.results()) {
            out.append(formatResult(rank++, result));
        }
        if (!outcome.suggestions().isEmpty()) {
            out.append(formatSuggestions(outcome.suggestions()));
        }
        if (outcome.results().stream().anyMatch(r -> OsmImporter.SOURCE_NAME.equals(r.document().getSourceName()))) {
            out.append(OsmImporter.ATTRIBUTION).append(System.lineSeparator());
        }
        return out.toString();
    }

    /** "No results found in Windsor (12 matching pages are elsewhere; try a province such as ON ...)". */
    static String noResultsMessage(SearchRequest request, SearchOutcome outcome) {
        if (!request.hasLocation()) {
            return "No results found.";
        }
        String message = "No results found in " + request.location() + ".";
        if (outcome.outsideLocation() > 0) {
            message += " " + outcome.outsideLocation() + " matching page(s) are in other places"
                    + " - try a nearby city, a province (e.g. ON), or no location.";
        }
        return message;
    }

    /** One result, e.g. "1. Title  (score 28)" followed by details lines. */
    static String formatResult(int rank, SearchResult result) {
        ServiceDocument doc = result.document();
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder();
        out.append(rank).append(". ").append(doc.isTestFixture() ? FIXTURE_LABEL : "")
                .append(doc.getTitle()).append("  (score ").append(formatScore(result.score())).append(')').append(nl);
        out.append("   ").append(doc.getUrl()).append(nl);
        out.append("   Why: ").append(result.scoreBreakdown()).append(nl);
        out.append("   Word counts on this page: ").append(formatCounts(result.termCounts())).append(nl);
        if (!doc.getOpeningHours().isEmpty()) {
            Availability availability = Availability.of(doc.getOpeningHours(), java.time.Clock.systemUTC());
            out.append("   ").append(availability.state() == Availability.State.UNKNOWN
                    ? "Hours: " + doc.getOpeningHours()
                    : availability.label() + "  (hours: " + doc.getOpeningHours() + ")").append(nl);
        }
        String place = String.join(", ", nonEmpty(doc.getAddress(), doc.getCity()));
        if (!place.isEmpty()) {
            out.append("   Address: ").append(place).append(nl);
        }
        List<String> contact = new ArrayList<>();
        if (!doc.getPhones().isEmpty()) {
            contact.add("Phone " + String.join(", ", doc.getPhones()));
        }
        if (!doc.getEmails().isEmpty()) {
            contact.add("Email " + String.join(", ", doc.getEmails()));
        }
        if (!doc.getPostalCodes().isEmpty()) {
            contact.add(String.join(", ", doc.getPostalCodes()) + (doc.getProvince().isEmpty() ? "" : " " + doc.getProvince()));
        }
        if (!contact.isEmpty()) {
            out.append("   ").append(String.join("  |  ", contact)).append(nl);
        }
        return out.toString();
    }

    /** "Did you mean: battrey -> battery, batter" (one line per unknown word). */
    static String formatSuggestions(Map<String, List<String>> suggestions) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : suggestions.entrySet()) {
            out.append("Did you mean: ").append(entry.getKey()).append(" -> ")
                    .append(String.join(", ", entry.getValue())).append(System.lineSeparator());
        }
        return out.toString();
    }

    private static List<String> nonEmpty(String... parts) {
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    static String formatCounts(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((term, count) -> parts.add(term + "=" + count));
        return String.join(", ", parts);
    }

    /** 28.0 -> "28"; scores are whole numbers today but SearchResult stores a double. */
    static String formatScore(double score) {
        return score == Math.rint(score) ? String.valueOf((long) score) : String.format(java.util.Locale.ROOT, "%.2f", score);
    }
}

package ca.carcareplus.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.hours.Availability;
import ca.carcareplus.hours.OpeningHours;
import ca.carcareplus.live.GooglePlacesClient;
import ca.carcareplus.models.GeoPoint;
import ca.carcareplus.osm.OsmImporter;
import ca.carcareplus.query.LevenshteinDistance;
import ca.carcareplus.query.SpellChecker;
import ca.carcareplus.search.SearchFrequency.TermCount;
import ca.carcareplus.search.SearchOutcome;
import ca.carcareplus.validation.DataValidator;

/**
 * The answers behind the web page (see WebServer). Each method takes the
 * request's parameters as a map and returns plain Maps and Lists, which
 * WebServer turns into JSON.
 *
 * Like ConsoleMenu, this is presentation only: every feature is done by the same
 * classes the console uses (SearchSystem, SearchEngine, WordCompleter, ...).
 * Keeping HTTP out of this class means it can be unit tested without a network.
 *
 * Bad input throws InvalidInputException; WebServer answers it with HTTP 400 and
 * the message, so the page can show it.
 */
public class WebApi {

    static final int COMPLETIONS_SHOWN = 8;
    static final int SPELLING_SUGGESTIONS_SHOWN = 5;
    static final int TOP_ITEMS_SHOWN = 10;

    /** Longest word for the edit-distance table (a bigger table is unreadable anyway). */
    static final int MAX_MATRIX_WORD = 20;

    /** Most results the page shows at once. */
    static final int PAGE_RESULTS = 20;

    private final SearchSystem system;
    private final GooglePlacesClient live;
    /** "Now" for open/closed; a fixed clock in tests. */
    private final java.time.Clock clock;
    /** True once real places are loaded: the invented test pages are then hidden. */
    private final boolean hideFixtures;

    public WebApi(SearchSystem system) {
        this(system, null);
    }

    /** @param live Google Places client for "Live nearby", or null if no API key is set */
    public WebApi(SearchSystem system, GooglePlacesClient live) {
        this(system, live, java.time.Clock.systemUTC());
    }

    WebApi(SearchSystem system, GooglePlacesClient live, java.time.Clock clock) {
        this.clock = clock;
        if (system == null) {
            throw new InvalidInputException("WebApi needs a search system.");
        }
        this.system = system;
        this.live = live;
        this.hideFixtures = system.documents().stream().anyMatch(d -> !d.isTestFixture());
    }

    private boolean shown(ServiceDocument doc) {
        return !(hideFixtures && doc.isTestFixture());
    }

    /** GET /api/stats */
    public Map<String, Object> stats() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("pages", system.documents().size());
        answer.put("words", system.spellChecker().vocabularySize());
        answer.put("fixturesOnly", system.documents().stream().allMatch(ServiceDocument::isTestFixture));
        answer.put("realPages", system.documents().stream().filter(d -> !d.isTestFixture()).count());
        answer.put("liveEnabled", live != null);
        answer.put("osmPages", system.documents().stream()
                .filter(d -> OsmImporter.SOURCE_NAME.equals(d.getSourceName())).count());
        answer.put("attribution", OsmImporter.ATTRIBUTION);
        return answer;
    }

    /** GET /api/categories?vehicle=ev -- the categories the menu offers for that vehicle. */
    public List<Map<String, Object>> categories(Map<String, String> params) {
        VehicleType vehicle = VehicleType.fromUserInput(params.get("vehicle"));
        List<Map<String, Object>> answer = new ArrayList<>();
        for (ServiceCategory category : ConsoleMenu.categoryOptions(vehicle)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", category.name());
            item.put("label", category == ServiceCategory.EV_CHARGING
                    ? "Find EV charging stations" : category.displayName());
            item.put("defaultQuery", ConsoleMenu.defaultQueryFor(category));
            answer.add(item);
        }
        return answer;
    }

    /**
     * GET /api/search?vehicle=ev&category=BATTERY&q=battery&location=V2S1A1
     *                &lat=42.31&lon=-83.03&radius=10&sort=distance
     * An empty q uses the category name, like pressing Enter in the console.
     * lat/lon ("use my location") keep only places within radius km (default 10);
     * sort=distance lists the nearest first instead of the best score first.
     */
    public Map<String, Object> search(Map<String, String> params) throws StorageException {
        VehicleType vehicle = VehicleType.fromUserInput(params.get("vehicle"));
        ServiceCategory category = parseCategory(params.get("category"));
        String query = params.getOrDefault("q", "").trim();
        if (query.isEmpty()) {
            query = ConsoleMenu.defaultQueryFor(category);
        }
        String location = DataValidator.validateLocation(params.get("location"));
        GeoPoint near = GeoPoint.parse(params.get("lat"), params.get("lon"));
        double radius = parseRadius(params.get("radius"));
        boolean byDistance = "distance".equals(params.get("sort")) && near != null;
        boolean openNowOnly = "1".equals(params.get("open")) || "true".equals(params.get("open"));

        // Ask for the maximum, then hide test pages / re-sort, then keep one page of results
        SearchRequest request = new SearchRequest(query, vehicle, category, location,
                SearchRequest.MAX_RESULTS_LIMIT, near, radius);
        SearchOutcome outcome = system.engine().search(request);
        system.saveHistory(); // the console saves on exit; a web page has no "exit"

        List<SearchResult> shown = new ArrayList<>();
        int closedHidden = 0;
        for (SearchResult result : outcome.results()) {
            if (!shown(result.document())) {
                continue;
            }
            if (openNowOnly && !Availability.of(result.document().getOpeningHours(), clock).isOpen()) {
                closedHidden++; // closed, or hours unknown
                continue;
            }
            shown.add(result);
        }
        if (byDistance) {
            shown.sort(java.util.Comparator.comparingDouble(r -> r.document().getLocation().distanceKm(near)));
        }
        if (shown.size() > PAGE_RESULTS) {
            shown = new ArrayList<>(shown.subList(0, PAGE_RESULTS));
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("query", outcome.query());
        answer.put("vehicle", vehicle.displayName());
        answer.put("category", category == null ? "" : category.displayName());
        answer.put("location", location);
        answer.put("near", near == null ? null : Map.of("lat", near.latitude(), "lon", near.longitude(), "radiusKm", radius));
        answer.put("sort", byDistance ? "distance" : "best");
        answer.put("terms", outcome.terms());
        answer.put("candidateCount", outcome.candidateCount());
        List<Map<String, Object>> results = new ArrayList<>();
        int rank = 1;
        for (SearchResult result : shown) {
            results.add(resultToMap(rank++, result, near, clock));
        }
        answer.put("results", results);
        answer.put("moreResults", outcome.candidateCount() > shown.size() + closedHidden);
        answer.put("openNowOnly", openNowOnly);
        answer.put("closedHidden", closedHidden);
        answer.put("suggestions", outcome.suggestions());
        answer.put("correctedQuery", outcome.suggestions().isEmpty()
                ? "" : ConsoleMenu.correctedQuery(outcome.terms(), outcome.suggestions()));
        answer.put("outsideLocation", outcome.outsideLocation());
        answer.put("noResultsMessage", !results.isEmpty() ? ""
                : closedHidden > 0 ? closedHidden + " matching place(s) are closed right now or have unknown hours."
                  + " Turn off \"Open now\" to see them."
                : noResultsMessage(request, outcome));
        return answer;
    }

    private static String noResultsMessage(SearchRequest request, SearchOutcome outcome) {
        if (request.hasNear()) {
            String message = "No places found within " + formatKm(request.radiusKm()) + " of your location.";
            return outcome.outsideLocation() > 0
                    ? message + " " + outcome.outsideLocation() + " match(es) are farther away - try a bigger radius."
                    : message;
        }
        return ResultFormatter.noResultsMessage(request, outcome);
    }

    static double parseRadius(String text) {
        if (text == null || text.isBlank()) {
            return SearchRequest.DEFAULT_RADIUS_KM;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new InvalidInputException("Radius must be a number of kilometres.");
        }
    }

    private static String formatKm(double km) {
        return (km == Math.rint(km) ? String.valueOf((long) km) : String.format(Locale.ROOT, "%.1f", km)) + " km";
    }

    private static Map<String, Object> resultToMap(int rank, SearchResult result, GeoPoint near, java.time.Clock clock) {
        ServiceDocument doc = result.document();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rank", rank);
        item.put("title", doc.getTitle());
        item.put("url", doc.getUrl());
        item.put("source", doc.getSourceName());
        item.put("testFixture", doc.isTestFixture());
        item.put("score", ResultFormatter.formatScore(result.score()));
        item.put("breakdown", result.scoreBreakdown());
        item.put("termCounts", result.termCounts());
        item.put("phones", doc.getPhones());
        item.put("emails", doc.getEmails());
        item.put("postalCodes", doc.getPostalCodes());
        item.put("province", doc.getProvince());
        item.put("address", doc.getAddress());
        item.put("city", doc.getCity());
        item.put("openingHours", doc.getOpeningHours());
        Availability availability = Availability.of(doc.getOpeningHours(), clock);
        item.put("availability", Map.of("state", availability.state().name(), "label", availability.label()));
        OpeningHours hours = OpeningHours.parse(doc.getOpeningHours());
        item.put("weeklyHours", hours == null ? List.of() : hours.weeklySchedule());
        item.put("website", doc.getWebsite());
        List<String> categories = new ArrayList<>();
        doc.getCategories().forEach(c -> categories.add(c.displayName()));
        item.put("categories", categories);
        if (doc.hasLocation()) {
            item.put("lat", doc.getLocation().latitude());
            item.put("lon", doc.getLocation().longitude());
            if (near != null) {
                item.put("distanceKm", Math.round(doc.getLocation().distanceKm(near) * 10) / 10.0);
            }
        }
        return item;
    }

    /** Category name such as "BATTERY"; blank means any category. */
    static ServiceCategory parseCategory(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return ServiceCategory.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("Unknown category '" + name.trim() + "'.");
        }
    }

    /** GET /api/complete?prefix=bat */
    public List<String> complete(Map<String, String> params) {
        return system.completer().complete(params.getOrDefault("prefix", ""), COMPLETIONS_SHOWN);
    }

    /** GET /api/spell?word=battrey */
    public Map<String, Object> spell(Map<String, String> params) {
        String word = params.getOrDefault("word", "").trim();
        if (word.isEmpty() || word.contains(" ")) {
            throw new InvalidInputException("Please type exactly one word.");
        }
        SpellChecker checker = system.spellChecker();
        int maxDistance = SpellChecker.defaultMaxDistance(word);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("word", word);
        answer.put("known", checker.isKnown(word));
        answer.put("maxDistance", maxDistance);
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (SpellChecker.Suggestion s : checker.suggestions(word, maxDistance, SPELLING_SUGGESTIONS_SHOWN)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("word", s.word());
            item.put("distance", s.distance());
            item.put("frequency", s.frequency());
            suggestions.add(item);
        }
        answer.put("suggestions", suggestions);
        return answer;
    }

    /** GET /api/matrix?a=cat&b=cut -- the dynamic-programming table, for explaining edit distance. */
    public Map<String, Object> matrix(Map<String, String> params) {
        String a = params.getOrDefault("a", "").trim().toLowerCase(Locale.ROOT);
        String b = params.getOrDefault("b", "").trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            throw new InvalidInputException("Please type two words.");
        }
        if (a.length() > MAX_MATRIX_WORD || b.length() > MAX_MATRIX_WORD) {
            throw new InvalidInputException("Words for the table may have at most " + MAX_MATRIX_WORD + " letters.");
        }
        int[][] table = LevenshteinDistance.matrix(a, b);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("a", a);
        answer.put("b", b);
        answer.put("matrix", table);
        answer.put("distance", table[a.length()][b.length()]);
        return answer;
    }

    /** GET /api/pages -- every indexed page, for the word-frequency tab. */
    public List<Map<String, Object>> pages() {
        List<Map<String, Object>> answer = new ArrayList<>();
        for (ServiceDocument doc : system.documents()) {
            if (!shown(doc)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", doc.getTitle());
            item.put("url", doc.getUrl());
            item.put("testFixture", doc.isTestFixture());
            answer.add(item);
        }
        return answer;
    }

    /**
     * GET /api/frequency?url=...&word=brake -> count of one word on that page;
     * without a word -> the page's most frequent words.
     */
    public Map<String, Object> frequency(Map<String, String> params) {
        String url = params.getOrDefault("url", "").trim();
        if (url.isEmpty()) {
            throw new InvalidInputException("Please choose a page.");
        }
        String word = params.getOrDefault("word", "").trim();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("url", url);
        if (word.isEmpty()) {
            answer.put("topTerms", system.frequencyCounter().topTerms(url, TOP_ITEMS_SHOWN));
        } else {
            answer.put("word", word);
            answer.put("count", system.frequencyCounter().countInDocument(word, url));
        }
        return answer;
    }

    /**
     * GET /api/cities -- every city that has at least one page, with the number
     * of pages, alphabetical (for the location box's suggestions).
     */
    public List<Map<String, Object>> cities() {
        java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ServiceDocument doc : system.documents()) {
            if (shown(doc) && !doc.getCity().isBlank()) {
                counts.merge(doc.getCity().trim(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> answer = new ArrayList<>();
        counts.forEach((city, count) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("city", city);
            item.put("pages", count);
            answer.add(item);
        });
        return answer;
    }

    /**
     * GET /api/live?q=brake+repair&category=BRAKES&location=Windsor&lat=..&lon=..&radius=10
     * Google Places results (ratings, reviews, open now). Not indexed or stored.
     *
     * @throws ServiceUnavailableException if no API key is set or Google fails
     */
    public Map<String, Object> live(Map<String, String> params) throws ServiceUnavailableException {
        if (live == null) {
            throw new ServiceUnavailableException("Live search is off: set the "
                    + GooglePlacesClient.API_KEY_VARIABLE + " environment variable to a Google Places API key"
                    + " and restart the web server.");
        }
        String query = params.getOrDefault("q", "").trim();
        if (query.isEmpty()) {
            query = ConsoleMenu.defaultQueryFor(parseCategory(params.get("category")));
        }
        if (query.isEmpty()) {
            query = "car repair";
        }
        GeoPoint near = GeoPoint.parse(params.get("lat"), params.get("lon"));
        double radius = parseRadius(params.get("radius"));
        List<Map<String, Object>> places = new ArrayList<>();
        for (GooglePlacesClient.Place place : live.search(query, params.get("location"), near, radius)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("place", place);
            if (near != null && place.latitude() != null && place.longitude() != null) {
                item.put("distanceKm", Math.round(new GeoPoint(place.latitude(), place.longitude())
                        .distanceKm(near) * 10) / 10.0);
            }
            places.add(item);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("query", query);
        answer.put("places", places);
        return answer;
    }

    /** GET /api/history -- most searched words. */
    public List<Map<String, Object>> history() {
        List<Map<String, Object>> answer = new ArrayList<>();
        for (TermCount entry : system.searchFrequency().topSearches(TOP_ITEMS_SHOWN)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("term", entry.term());
            item.put("count", entry.count());
            answer.add(item);
        }
        return answer;
    }
}

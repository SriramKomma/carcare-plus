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
import ca.carcareplus.query.CommonProblems;
import ca.carcareplus.query.LevenshteinDistance;
import ca.carcareplus.query.SpellChecker;
import ca.carcareplus.query.SymptomMatcher;
import ca.carcareplus.search.SearchFrequency.TermCount;
import ca.carcareplus.search.SearchOutcome;
import ca.carcareplus.validation.DataValidator;
import ca.carcareplus.visits.VisitBook;
import ca.carcareplus.visits.VisitRequest;
import ca.carcareplus.visits.VisitSlots;

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

    /** Information pages shown under the shops ("Helpful guides"). */
    static final int GUIDES_SHOWN = 2;

    /** Priority: how far to look for a shop, and how many to offer (the first is called). */
    static final double PRIORITY_MAX_KM = 50;
    static final int PRIORITY_SHOPS = 3;
    /** "Not urgent": the repair should happen within this many days. */
    static final int LATER_DAYS = 2;

    /** Most results the page shows at once. */
    static final int PAGE_RESULTS = 20;

    private final SearchSystem system;
    private final GooglePlacesClient live;
    /** "Now" for open/closed; a fixed clock in tests. */
    private final java.time.Clock clock;
    /** True once real places are loaded: the invented test pages are then hidden. */
    private final boolean hideFixtures;
    /** Saved non-priority visit requests. */
    private final VisitBook visits;

    public WebApi(SearchSystem system) {
        this(system, null);
    }

    /** @param live Google Places client for "Live nearby", or null if no API key is set */
    public WebApi(SearchSystem system, GooglePlacesClient live) {
        this(system, live, java.time.Clock.systemUTC());
    }

    WebApi(SearchSystem system, GooglePlacesClient live, java.time.Clock clock) {
        this(system, live, clock, new VisitBook());
    }

    /** @param visits where visit requests are saved (in memory in tests) */
    WebApi(SearchSystem system, GooglePlacesClient live, java.time.Clock clock, VisitBook visits) {
        this.clock = clock;
        this.visits = visits == null ? new VisitBook() : visits;
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
     * GET /api/problems?vehicle=ev
     * The "What's wrong with your car?" drop-down: everyday problems for this vehicle,
     * each with the phrase the page searches for (see CommonProblems). commonRank >= 0
     * puts a problem in the "Common problems" group at the top as well (0 = first).
     */
    public List<Map<String, Object>> problems(Map<String, String> params) {
        VehicleType vehicle = optionalVehicle(params);
        List<Map<String, Object>> answer = new ArrayList<>();
        for (CommonProblems.Problem problem : vehicle == null ? CommonProblems.all() : CommonProblems.forVehicle(vehicle)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", problem.id());
            item.put("group", problem.group());
            item.put("label", problem.label());
            item.put("text", problem.phrase());
            item.put("category", problem.category().name());
            item.put("commonRank", CommonProblems.commonRank(problem)); // -1 = not in "Common problems"
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
     * With no category, q may describe a problem ("my car won't start"): SymptomMatcher
     * picks the service (BATTERY) and the search runs for that service's words;
     * the answer's "problem" field says what was understood, so the page can show it.
     */
    public Map<String, Object> search(Map<String, String> params) throws StorageException {
        VehicleType vehicle = optionalVehicle(params);
        ServiceCategory category = parseCategory(params.get("category"));
        String query = params.getOrDefault("q", "").trim();

        // "My car won't start": no category chosen, so read the problem from the words
        Map<String, Object> problem = null;
        if (category == null && !query.isEmpty()) {
            List<SymptomMatcher.Match> matches = matchesFor(vehicle, query);
            if (!matches.isEmpty()) {
                problem = problemToMap(query, matches);
                category = matches.get(0).category();
                // Search for the SERVICE: a shop's text says "battery", not "won't start"
                query = ConsoleMenu.defaultQueryFor(category);
            }
        }
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
        answer.put("problem", problem);
        answer.put("vehicle", vehicle == null ? "" : vehicle.displayName());
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
        answer.put("guides", guidesFor(category, outcome.terms()));
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

    /**
     * Crawled information pages (they have no address, so a "near me" search never
     * lists them as places) about the chosen or detected service. Ranked by how often
     * the search words occur in them, using the inverted index: O(G * t) for G
     * candidate pages and t search words.
     */
    private List<Map<String, Object>> guidesFor(ServiceCategory category, List<String> terms) {
        List<Map<String, Object>> guides = new ArrayList<>();
        if (category == null || category == ServiceCategory.OTHER) {
            return guides;
        }
        record Scored(ServiceDocument doc, int count) {
        }
        List<Scored> scored = new ArrayList<>();
        for (ServiceDocument doc : system.documents()) {
            if (doc.hasLocation() || doc.isTestFixture() || !doc.hasId() || !doc.hasCategory(category)) {
                continue;
            }
            int count = 0;
            for (String term : terms) {
                count += system.index().termFrequency(term, doc.getId());
            }
            if (count > 0) {
                scored.add(new Scored(doc, count));
            }
        }
        scored.sort((a, b) -> b.count() != a.count() ? Integer.compare(b.count(), a.count())
                : a.doc().getTitle().compareTo(b.doc().getTitle()));
        for (Scored item : scored.subList(0, Math.min(GUIDES_SHOWN, scored.size()))) {
            Map<String, Object> guide = new LinkedHashMap<>();
            guide.put("title", item.doc().getTitle());
            guide.put("url", item.doc().getUrl());
            guide.put("source", item.doc().getSourceName());
            guide.put("description", item.doc().getDescription());
            guides.add(guide);
        }
        return guides;
    }

    /** The vehicle in the request, or null for "any vehicle" when none is given. */
    private static VehicleType optionalVehicle(Map<String, String> params) {
        String name = params.get("vehicle");
        return name == null || name.isBlank() ? null : VehicleType.fromUserInput(name);
    }

    /** Symptom matches whose service applies to this vehicle (no "EV charging" for a gas car); null = any vehicle. */
    private List<SymptomMatcher.Match> matchesFor(VehicleType vehicle, String description) {
        List<SymptomMatcher.Match> fitting = new ArrayList<>();
        for (SymptomMatcher.Match match : system.symptoms().match(description)) {
            if (vehicle == null || match.category().appliesTo(vehicle)) {
                fitting.add(match);
            }
        }
        return fitting;
    }

    /** What the page shows as "Looks like: Battery problem (you said: won't start)". */
    private static Map<String, Object> problemToMap(String description, List<SymptomMatcher.Match> matches) {
        SymptomMatcher.Match best = matches.get(0);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("text", description);
        problem.put("category", best.category().name());
        problem.put("label", best.category().displayName());
        problem.put("symptoms", best.symptoms());
        List<Map<String, Object>> alternatives = new ArrayList<>();
        for (SymptomMatcher.Match other : matches.subList(1, Math.min(matches.size(), 3))) {
            alternatives.add(Map.of("category", other.category().name(), "label", other.category().displayName()));
        }
        problem.put("alternatives", alternatives);
        return problem;
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
        item.put("next2Days", hours == null ? List.of() : openingLabels(hours, clock));
        item.put("website", doc.getWebsite());
        item.put("contactSource", doc.getContactSource()); // "" unless details came from the shop's own site
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

    /** "Today 2:00 PM – 5:00 PM", ... for the next LATER_DAYS days, in Ontario time. */
    private static List<String> openingLabels(OpeningHours hours, java.time.Clock clock) {
        List<String> labels = new ArrayList<>();
        for (OpeningHours.Window window : hours.openingsWithin(
                java.time.LocalDateTime.now(clock.withZone(Availability.ONTARIO)), LATER_DAYS)) {
            labels.add(window.label());
        }
        return labels;
    }

    /**
     * GET /api/priority?vehicle=gas&lat=..&lon=..&q=my brakes failed
     *
     * Priority help: the nearest shops the driver can call right now. A shop must
     * have a phone number, a position within PRIORITY_MAX_KM, suit the vehicle, and not
     * be closed now (unknown hours are allowed: the driver can still call). When
     * the description is about charging, chargers are offered instead of garages.
     * The PRIORITY_SHOPS nearest are kept with TopK (a heap of size k: O(n log k));
     * the page calls the first one straight away. Nothing is sent by the server.
     */
    public Map<String, Object> priority(Map<String, String> params) {
        VehicleType vehicle = optionalVehicle(params);
        GeoPoint near = GeoPoint.parse(params.get("lat"), params.get("lon"));
        if (near == null) {
            throw new InvalidInputException("Priority help needs your location. Allow location access, or call 911 if you are in danger.");
        }
        String query = params.getOrDefault("q", "").trim();
        List<SymptomMatcher.Match> matches = query.isEmpty() ? List.of() : matchesFor(vehicle, query);
        ServiceCategory category = matches.isEmpty() ? null : matches.get(0).category();
        boolean wantCharger = category == ServiceCategory.EV_CHARGING;

        List<ServiceDocument> candidates = new ArrayList<>();
        for (ServiceDocument doc : system.documents()) {
            if (!shown(doc) || !doc.hasLocation() || doc.getPhones().isEmpty()
                    || doc.hasCategory(ServiceCategory.EV_CHARGING) != wantCharger
                    || (vehicle != null && !doc.appliesTo(vehicle))
                    || doc.getLocation().distanceKm(near) > PRIORITY_MAX_KM
                    || Availability.of(doc.getOpeningHours(), clock).state() == Availability.State.CLOSED) {
                continue;
            }
            candidates.add(doc);
        }
        java.util.Comparator<ServiceDocument> nearestFirst = java.util.Comparator
                .comparingDouble((ServiceDocument doc) -> doc.getLocation().distanceKm(near))
                .thenComparing(ServiceDocument::getTitle);
        List<Map<String, Object>> shops = new ArrayList<>();
        for (ServiceDocument doc : ca.carcareplus.search.TopK.best(candidates, PRIORITY_SHOPS, nearestFirst)) {
            Map<String, Object> shop = new LinkedHashMap<>();
            shop.put("title", doc.getTitle());
            shop.put("phone", doc.getPhones().get(0));
            shop.put("phones", doc.getPhones());
            shop.put("distanceKm", Math.round(doc.getLocation().distanceKm(near) * 10) / 10.0);
            shop.put("address", doc.getAddress());
            shop.put("city", doc.getCity());
            shop.put("availability", Availability.of(doc.getOpeningHours(), clock).label());
            shop.put("matchesProblem", category != null && doc.hasCategory(category));
            shop.put("lat", doc.getLocation().latitude());
            shop.put("lon", doc.getLocation().longitude());
            shop.put("url", doc.getUrl());
            shops.add(shop);
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("problem", matches.isEmpty() ? null : problemToMap(query, matches));
        answer.put("near", Map.of("lat", near.latitude(), "lon", near.longitude()));
        answer.put("emergencyNumber", "911");
        answer.put("shops", shops);
        answer.put("message", !shops.isEmpty() ? ""
                : "No " + (wantCharger ? "charging station" : "repair shop") + " with a phone number is open within "
                  + formatKm(PRIORITY_MAX_KM) + ". If you are in danger, call 911; otherwise call a roadside assistance service.");
        return answer;
    }

    // ------------------------------------------------------------- non-priority: visit requests

    /** Longest name, problem text and note kept in a visit request. */
    static final int MAX_NAME = 60;
    static final int MAX_PROBLEM = 200;
    static final int MAX_NOTE = 300;
    /** Most references the page may ask about at once. */
    static final int MAX_VISIT_IDS = 20;

    /**
     * GET /api/visit/slots?url=..  -- the times the driver can choose for this shop
     * in the next LATER_DAYS days: every 30 minutes while it is open, or morning /
     * afternoon when its hours are unknown (the shop then confirms the time).
     */
    public Map<String, Object> visitSlots(Map<String, String> params) {
        ServiceDocument shop = shopFor(params.get("url"));
        VisitSlots slots = slotsFor(shop);
        List<Map<String, Object>> days = new ArrayList<>();
        for (VisitSlots.Day day : slots.days()) {
            List<Map<String, String>> times = new ArrayList<>();
            day.slots().forEach(slot -> times.add(Map.of("value", slot.value(), "label", slot.label())));
            days.add(Map.of("date", day.date().toString(), "label", day.label(), "slots", times));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("shop", shopSummary(shop));
        answer.put("hoursKnown", slots.hoursKnown());
        answer.put("days", days);
        answer.put("message", !slots.isEmpty() ? ""
                : shop.getTitle() + " is not open in the next " + LATER_DAYS + " days. Please pick another shop.");
        return answer;
    }

    /**
     * POST /api/visit  url, slot, name, phone, email, note, q, details, vehicle
     * (q is the problem, e.g. a drop-down phrase; details is the driver's optional extra text,
     * saved with the problem but not used to choose the service)
     *
     * Checks everything again on the server (the slot must be one that visitSlots
     * offers right now; a name and a way to reply -- phone or email -- are needed),
     * saves the request with a reference such as "CC-1001", and returns it with the
     * message the driver sends to the shop. CarCare+ does not contact the shop itself.
     */
    public Map<String, Object> requestVisit(Map<String, String> params) throws StorageException {
        ServiceDocument shop = shopFor(params.get("url"));
        VisitSlots slots = slotsFor(shop);
        String slot = params.getOrDefault("slot", "").trim();
        if (slot.isEmpty()) {
            throw new InvalidInputException("Please choose a time.");
        }
        if (slots.find(slot) == null) {
            throw new InvalidInputException("That time is no longer available. Please choose another one.");
        }
        String name = clean(params.get("name"));
        if (name.length() < 2 || name.length() > MAX_NAME || !name.matches("[\\p{L}][\\p{L} .'-]*")) {
            throw new InvalidInputException("Please enter your name (letters only, up to " + MAX_NAME + " characters).");
        }
        String phone = clean(params.get("phone"));
        String email = clean(params.get("email"));
        if (phone.isEmpty() && email.isEmpty()) {
            throw new InvalidInputException("Please give a phone number or an email so the shop can confirm the time.");
        }
        if (!phone.isEmpty()) {
            phone = DataValidator.normalizePhone(phone);
            if (phone == null) {
                throw new InvalidInputException("Please enter a 10-digit phone number, e.g. 519-555-0100.");
            }
        }
        if (!email.isEmpty()) {
            if (!DataValidator.isValidEmail(email)) {
                throw new InvalidInputException("Please enter a valid email, e.g. name@example.com.");
            }
            email = DataValidator.normalizeEmail(email);
        }
        String note = clean(params.get("note"));
        if (note.length() > MAX_NOTE) {
            throw new InvalidInputException("The note is too long (at most " + MAX_NOTE + " characters).");
        }
        String problem = clean(params.get("q"));
        String service = serviceFor(params.get("vehicle"), problem); // from the problem only, not the free-text details
        String details = clean(params.get("details"));
        if (!details.isEmpty() && !details.equalsIgnoreCase(problem)) {
            problem = problem.isEmpty() ? details : problem + " - " + details;
        }
        if (problem.length() > MAX_PROBLEM) {
            problem = problem.substring(0, MAX_PROBLEM);
        }
        String vehicleName = params.getOrDefault("vehicle", "").trim();
        VehicleType vehicle = vehicleName.isEmpty() ? null : VehicleType.fromUserInput(vehicleName);
        VisitRequest saved = visits.add(new VisitRequest(null,
                java.time.LocalDateTime.now(clock.withZone(Availability.ONTARIO)).withNano(0).toString(),
                shop.getUrl(), shop.getTitle(), first(shop.getPhones()), first(shop.getEmails()),
                slot, slots.describe(slot), slots.hoursKnown(), problem, service,
                vehicle == null ? "" : vehicle.displayName(), name, phone == null ? "" : phone, email, note,
                VisitRequest.REQUESTED));
        return visitToMap(saved);
    }

    /** The service a visit is for ("Battery problem"), or "" when the problem is not understood. */
    String serviceFor(String vehicleName, String problem) {
        if (problem.isEmpty()) {
            return "";
        }
        VehicleType vehicle = vehicleName == null || vehicleName.isBlank() ? null : VehicleType.fromUserInput(vehicleName.trim());
        List<SymptomMatcher.Match> matches = matchesFor(vehicle, problem);
        return matches.isEmpty() ? "" : matches.get(0).category().displayName();
    }

    /** GET /api/visits?ids=CC-1001,CC-1002 -- the driver's own requests (the page remembers their references). */
    public List<Map<String, Object>> visits(Map<String, String> params) {
        List<String> ids = new ArrayList<>();
        for (String id : params.getOrDefault("ids", "").split(",")) {
            if (!id.isBlank()) {
                ids.add(id.trim());
            }
        }
        if (ids.size() > MAX_VISIT_IDS) {
            ids = ids.subList(ids.size() - MAX_VISIT_IDS, ids.size());
        }
        List<Map<String, Object>> answer = new ArrayList<>();
        visits.find(ids).forEach(request -> answer.add(visitToMap(request)));
        return answer;
    }

    /** POST /api/visit/cancel  id=CC-1001 */
    public Map<String, Object> cancelVisit(Map<String, String> params) throws StorageException {
        String id = params.getOrDefault("id", "").trim();
        if (id.isEmpty()) {
            throw new InvalidInputException("Which request? The reference is missing.");
        }
        return visitToMap(visits.cancel(id));
    }

    private ServiceDocument shopFor(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidInputException("Please choose a shop.");
        }
        for (ServiceDocument doc : system.documents()) {
            if (doc.getUrl().equals(url.trim()) && shown(doc)) {
                return doc;
            }
        }
        throw new InvalidInputException("That shop is not in CarCare+ any more. Please search again.");
    }

    private VisitSlots slotsFor(ServiceDocument shop) {
        return VisitSlots.of(OpeningHours.parse(shop.getOpeningHours()),
                java.time.LocalDateTime.now(clock.withZone(Availability.ONTARIO)), LATER_DAYS);
    }

    private Map<String, Object> shopSummary(ServiceDocument shop) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", shop.getTitle());
        summary.put("url", shop.getUrl());
        summary.put("phone", first(shop.getPhones()));
        summary.put("email", first(shop.getEmails()));
        summary.put("availability", Availability.of(shop.getOpeningHours(), clock).label());
        return summary;
    }

    private Map<String, Object> visitToMap(VisitRequest request) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", request.id());
        item.put("status", request.status());
        item.put("createdAt", request.createdAt());
        item.put("shopTitle", request.shopTitle());
        item.put("shopUrl", request.shopUrl());
        item.put("shopPhone", request.shopPhone());
        item.put("shopEmail", request.shopEmail());
        // website and position from the current data, for shops without a phone or email
        ServiceDocument shop = system.documents().stream()
                .filter(doc -> doc.getUrl().equals(request.shopUrl())).findFirst().orElse(null);
        item.put("shopWebsite", shop == null ? "" : shop.getWebsite());
        if (shop != null && shop.hasLocation()) {
            item.put("shopLat", shop.getLocation().latitude());
            item.put("shopLon", shop.getLocation().longitude());
        }
        item.put("slot", request.slot());
        item.put("slotLabel", request.slotLabel());
        item.put("exactTime", request.exactTime());
        item.put("past", java.time.LocalDateTime.parse(request.slot())
                .isBefore(java.time.LocalDateTime.now(clock.withZone(Availability.ONTARIO)).minusHours(1)));
        item.put("service", request.service());
        item.put("problem", request.problem());
        item.put("name", request.name());
        item.put("phone", request.phone());
        item.put("email", request.email());
        item.put("subject", request.subject());
        item.put("message", request.message());
        return item;
    }

    /** Trims and removes control characters (no line breaks inside one field). */
    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }

    private static String first(List<String> list) {
        return list == null || list.isEmpty() ? "" : list.get(0);
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

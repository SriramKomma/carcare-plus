package ca.carcareplus.models;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Everything the user asked for in one search.
 * Created by the console menu and passed to SearchEngine.search(...).
 *
 * Format checks on the text itself (allowed characters, postal-code format)
 * are done by DataValidator in Phase 4; this record only guarantees the
 * request is complete and consistent.
 *
 * @param query       words the user typed, e.g. "battery replacement"
 * @param vehicleType EV or GAS (required)
 * @param category    optional category filter; null means "any category"
 * @param location    optional city, province or postal code; "" means "anywhere"
 * @param maxResults  how many results to show (1 to MAX_RESULTS_LIMIT)
 * @param near        optional point ("use my location"); null means no distance filter
 * @param radiusKm    with near: only pages with a known position within this distance
 */
public record SearchRequest(
        String query,
        VehicleType vehicleType,
        ServiceCategory category,
        String location,
        int maxResults,
        GeoPoint near,
        double radiusKm) {

    public static final int DEFAULT_MAX_RESULTS = 10;
    public static final int MAX_RESULTS_LIMIT = 50;
    public static final double DEFAULT_RADIUS_KM = 10;
    public static final double MAX_RADIUS_KM = 200;

    /** A request without "near me". */
    public SearchRequest(String query, VehicleType vehicleType, ServiceCategory category,
                         String location, int maxResults) {
        this(query, vehicleType, category, location, maxResults, null, 0);
    }

    public SearchRequest {
        if (query == null || query.isBlank()) {
            throw new InvalidInputException("Please enter something to search for.");
        }
        if (vehicleType == null) {
            throw new InvalidInputException("Please choose a vehicle type (EV or Gas).");
        }
        if (category != null && !category.appliesTo(vehicleType)) {
            throw new InvalidInputException("'" + category.displayName()
                    + "' is not available for " + vehicleType.displayName() + ".");
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS_LIMIT) {
            throw new InvalidInputException(
                    "Number of results must be between 1 and " + MAX_RESULTS_LIMIT + ".");
        }
        if (near != null && !(radiusKm > 0 && radiusKm <= MAX_RADIUS_KM)) {
            throw new InvalidInputException("Search radius must be more than 0 and at most "
                    + (int) MAX_RADIUS_KM + " km.");
        }
        query = query.trim();
        location = location == null ? "" : location.trim();
    }

    /** Shortcut: any category, anywhere, default number of results. */
    public static SearchRequest of(String query, VehicleType vehicleType) {
        return new SearchRequest(query, vehicleType, null, "", DEFAULT_MAX_RESULTS);
    }

    public boolean hasCategory() {
        return category != null;
    }

    public boolean hasLocation() {
        return !location.isEmpty();
    }

    public boolean hasNear() {
        return near != null;
    }
}

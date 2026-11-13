package ca.carcareplus.models;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * A position on Earth in decimal degrees, e.g. Windsor = (42.3149, -83.0364).
 *
 * Used for shops from OpenStreetMap (they have coordinates) and for the user's
 * "use my location" search.
 */
public record GeoPoint(double latitude, double longitude) {

    /** Mean Earth radius used by the haversine formula. */
    public static final double EARTH_RADIUS_KM = 6371.0;

    public GeoPoint {
        if (Double.isNaN(latitude) || latitude < -90 || latitude > 90) {
            throw new InvalidInputException("Latitude must be between -90 and 90.");
        }
        if (Double.isNaN(longitude) || longitude < -180 || longitude > 180) {
            throw new InvalidInputException("Longitude must be between -180 and 180.");
        }
    }

    /**
     * Straight-line ("as the crow flies") distance with the haversine formula.
     * Accurate to well under 1% for the distances a driver cares about; road
     * distance would need a routing service.
     */
    public double distanceKm(GeoPoint other) {
        if (other == null) {
            throw new InvalidInputException("Second point is required.");
        }
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Parses two text values (from a web request); blank values give null. */
    public static GeoPoint parse(String latitude, String longitude) {
        if ((latitude == null || latitude.isBlank()) && (longitude == null || longitude.isBlank())) {
            return null;
        }
        try {
            return new GeoPoint(Double.parseDouble(latitude.trim()), Double.parseDouble(longitude.trim()));
        } catch (NumberFormatException | NullPointerException e) {
            throw new InvalidInputException("Location coordinates must be two numbers.");
        }
    }
}

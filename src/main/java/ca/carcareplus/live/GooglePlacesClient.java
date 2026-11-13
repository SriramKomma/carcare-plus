package ca.carcareplus.live;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.models.GeoPoint;
import ca.carcareplus.validation.DataValidator;

/**
 * "Live nearby" search with Google Places API (New), Text Search:
 * https://developers.google.com/maps/documentation/places/web-service/text-search
 *
 * This is an EXTRA feature next to the project's own search engine. Google's
 * terms do not allow storing its results, so they are fetched for each request,
 * shown, and never indexed. The graded features (index, ranking, ...) run on the
 * OpenStreetMap / crawled data instead.
 *
 * The API key comes from the GOOGLE_MAPS_API_KEY environment variable and is only
 * used here on the server, never sent to the browser.
 *
 * Cost control: the field mask asks only for the fields the page shows. Ratings,
 * opening hours and reviews are in Google's higher-priced field groups; each
 * search is one billable request.
 */
public class GooglePlacesClient {

    public static final String API_KEY_VARIABLE = "GOOGLE_MAPS_API_KEY";
    public static final String DEFAULT_ENDPOINT = "https://places.googleapis.com/v1/places:searchText";
    static final int RESULTS = 10;
    static final int REVIEWS_SHOWN = 3;

    static final String FIELD_MASK = String.join(",",
            "places.id", "places.displayName", "places.formattedAddress", "places.location",
            "places.nationalPhoneNumber", "places.rating", "places.userRatingCount",
            "places.currentOpeningHours.openNow", "places.regularOpeningHours.weekdayDescriptions",
            "places.googleMapsUri", "places.websiteUri", "places.businessStatus", "places.reviews");

    /** One review, with the author attribution Google requires. */
    public record Review(String author, String authorUrl, int rating, String text, String when) {
    }

    /** One place as shown on the page. Null/empty values mean "Google did not say". */
    public record Place(String name, String address, String phone, Double rating, Integer ratingCount,
                       Boolean openNow, List<String> hours, Double latitude, Double longitude,
                       String mapsUrl, String website, List<Review> reviews) {
    }

    private static final Gson GSON = new Gson();

    private final String apiKey;
    private final String endpoint;
    private final HttpClient client;

    public GooglePlacesClient(String apiKey, String endpoint) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidInputException("A Google API key is required.");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new InvalidInputException("Google Places endpoint is required.");
        }
        this.apiKey = apiKey.trim();
        this.endpoint = endpoint;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** @return a client using the key in GOOGLE_MAPS_API_KEY, or null if it is not set */
    public static GooglePlacesClient fromEnvironment() {
        String key = System.getenv(API_KEY_VARIABLE);
        return key == null || key.isBlank() ? null : new GooglePlacesClient(key, DEFAULT_ENDPOINT);
    }

    /**
     * @param words    what to look for, e.g. "brake repair" (validated like any query)
     * @param location optional city or postal code, e.g. "Windsor" (validated)
     * @param near     optional user position: results are biased to this circle
     * @param radiusKm radius of the circle (used with near)
     * @throws InvalidInputException       bad words or location
     * @throws ServiceUnavailableException Google could not be reached or refused the request
     */
    public List<Place> search(String words, String location, GeoPoint near, double radiusKm)
            throws ServiceUnavailableException {
        String query = DataValidator.validateQuery(words);
        String place = DataValidator.validateLocation(location);
        if (place.isEmpty() && near == null) {
            throw new InvalidInputException("Live search needs a city, a postal code or your location.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(query, place, near, radiusKm)))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ServiceUnavailableException("Could not reach Google Places: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Live search was interrupted.", e);
        }
        if (response.statusCode() != 200) {
            throw new ServiceUnavailableException("Google Places refused the request (HTTP "
                    + response.statusCode() + "): " + errorMessage(response.body()));
        }
        try {
            return parsePlaces(response.body());
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new ServiceUnavailableException("Google Places sent an answer we could not read.", e);
        }
    }

    /** JSON body: {"textQuery": "brake repair in Windsor, Ontario", ...}. */
    static String requestBody(String query, String location, GeoPoint near, double radiusKm) {
        JsonObject body = new JsonObject();
        String text = location.isEmpty() ? query : query + " in " + location;
        if (!location.isEmpty() && !text.toLowerCase(java.util.Locale.ROOT).contains("ontario")) {
            text += ", Ontario";
        }
        body.addProperty("textQuery", text);
        body.addProperty("regionCode", "CA");
        body.addProperty("languageCode", "en");
        body.addProperty("pageSize", RESULTS);
        if (near != null) {
            JsonObject center = new JsonObject();
            center.addProperty("latitude", near.latitude());
            center.addProperty("longitude", near.longitude());
            JsonObject circle = new JsonObject();
            circle.add("center", center);
            // Google allows a bias radius of at most 50 km
            circle.addProperty("radius", Math.min(radiusKm, 50) * 1000);
            JsonObject bias = new JsonObject();
            bias.add("circle", circle);
            body.add("locationBias", bias);
        }
        return GSON.toJson(body);
    }

    static List<Place> parsePlaces(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Place> places = new ArrayList<>();
        JsonArray array = root.has("places") ? root.getAsJsonArray("places") : new JsonArray();
        for (JsonElement element : array) {
            JsonObject p = element.getAsJsonObject();
            if ("CLOSED_PERMANENTLY".equals(string(p, "businessStatus"))) {
                continue; // a closed shop cannot help a driver
            }
            JsonObject location = object(p, "location");
            JsonObject current = object(p, "currentOpeningHours");
            JsonObject regular = object(p, "regularOpeningHours");
            List<String> hours = new ArrayList<>();
            if (regular != null && regular.has("weekdayDescriptions")) {
                regular.getAsJsonArray("weekdayDescriptions").forEach(h -> hours.add(h.getAsString()));
            }
            List<Review> reviews = new ArrayList<>();
            if (p.has("reviews")) {
                for (JsonElement r : p.getAsJsonArray("reviews")) {
                    if (reviews.size() == REVIEWS_SHOWN) {
                        break;
                    }
                    JsonObject review = r.getAsJsonObject();
                    JsonObject author = object(review, "authorAttribution");
                    JsonObject text = object(review, "text");
                    reviews.add(new Review(
                            author == null ? "Google user" : string(author, "displayName"),
                            author == null ? "" : string(author, "uri"),
                            review.has("rating") ? review.get("rating").getAsInt() : 0,
                            text == null ? "" : string(text, "text"),
                            string(review, "relativePublishTimeDescription")));
                }
            }
            JsonObject name = object(p, "displayName");
            places.add(new Place(
                    name == null ? "" : string(name, "text"),
                    string(p, "formattedAddress"),
                    string(p, "nationalPhoneNumber"),
                    p.has("rating") ? p.get("rating").getAsDouble() : null,
                    p.has("userRatingCount") ? p.get("userRatingCount").getAsInt() : null,
                    current != null && current.has("openNow") ? current.get("openNow").getAsBoolean() : null,
                    hours,
                    location == null ? null : location.get("latitude").getAsDouble(),
                    location == null ? null : location.get("longitude").getAsDouble(),
                    string(p, "googleMapsUri"),
                    string(p, "websiteUri"),
                    reviews));
        }
        return places;
    }

    /** Google errors look like {"error": {"message": "API key not valid..."}}. */
    static String errorMessage(String body) {
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return error.get("message").getAsString();
            }
        } catch (RuntimeException e) {
            // not JSON: fall through
        }
        return "no details";
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : "";
    }
}

package ca.carcareplus.osm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.GeoPoint;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.validation.DataValidator;

/**
 * Turns the OpenStreetMap files saved by OverpassDownloader into ServiceDocuments,
 * so OSM shops are indexed and searched exactly like parsed web pages.
 *
 * One OSM element (a shop or charger) -> one document:
 * <pre>
 *   url          https://www.openstreetmap.org/node/123   (unique, so no duplicates)
 *   title        name tag (elements without a name are skipped; chargers fall back
 *                to "&lt;operator&gt; EV charging station")
 *   description  "Car repair shop in Windsor, ON"
 *   body text    built only from the element's tags: kind, services, connectors,
 *                opening hours, address, phone -- nothing is invented
 *   city         addr:city, or else the NEAREST city/town/village within 25 km
 *   province     ON (only Ontario is downloaded)
 *   categories   from tags (shop=tyres -> FLAT_TIRE, service:vehicle:brakes -> BRAKES,
 *                amenity=charging_station -> EV_CHARGING) plus the usual keyword rules
 * </pre>
 *
 * Nearest place: a linear scan over all P places for each of S shops, O(S * P)
 * with the haversine distance. With a few thousand of each this takes well under
 * a second, so a spatial index (k-d tree) is not needed.
 */
public final class OsmImporter {

    public static final String SOURCE_NAME = "OpenStreetMap";
    public static final String ATTRIBUTION = "Data (c) OpenStreetMap contributors, ODbL 1.0";
    static final String PROVINCE = "ON";

    /** A shop farther than this from every place keeps an empty city. */
    static final double MAX_PLACE_DISTANCE_KM = 25.0;

    /** OSM "service:vehicle:*" tags that map onto our categories. */
    static final Map<String, ServiceCategory> SERVICE_TAGS = Map.ofEntries(
            Map.entry("brakes", ServiceCategory.BRAKES),
            Map.entry("batteries", ServiceCategory.BATTERY),
            Map.entry("tyres", ServiceCategory.FLAT_TIRE),
            Map.entry("wheels", ServiceCategory.FLAT_TIRE),
            Map.entry("electrical", ServiceCategory.ELECTRICAL),
            Map.entry("electronics", ServiceCategory.ELECTRICAL),
            Map.entry("engine", ServiceCategory.ENGINE),
            Map.entry("oil_change", ServiceCategory.ENGINE),
            Map.entry("exhaust", ServiceCategory.ENGINE),
            Map.entry("transmission", ServiceCategory.ENGINE),
            Map.entry("air_conditioning", ServiceCategory.OVERHEATING_COOLING),
            Map.entry("glass", ServiceCategory.WIPERS),
            Map.entry("towing", ServiceCategory.BREAKDOWN_TOWING));

    /** OSM socket tags -> connector names used elsewhere in CarCare+. */
    static final Map<String, String> SOCKETS = Map.of(
            "type2_combo", "CCS", "type1_combo", "CCS", "chademo", "CHAdeMO",
            "type1", "J1772", "nacs", "NACS", "tesla_supercharger", "NACS", "tesla_destination", "NACS");

    /** A city, town or village with its position. */
    public record Place(String name, double lat, double lon) {
    }

    private final ServiceCategorizer categorizer;

    public OsmImporter(ServiceCategorizer categorizer) {
        if (categorizer == null) {
            throw new InvalidInputException("OsmImporter needs a categorizer.");
        }
        this.categorizer = categorizer;
    }

    /**
     * Reads the downloaded files in the folder.
     *
     * @return documents, or an empty list if nothing has been downloaded yet
     * @throws StorageException if a file exists but is not valid Overpass JSON
     */
    public List<ServiceDocument> load(Path folder) throws StorageException {
        if (folder == null) {
            throw new InvalidInputException("OSM folder is required.");
        }
        // Every part that has been downloaded so far (a part may still be missing)
        JsonArray shops = new JsonArray();
        Set<String> seen = new java.util.HashSet<>();
        for (String name : List.of(OverpassDownloader.SHOPS_FILE, OverpassDownloader.REPAIRS_FILE,
                OverpassDownloader.CHARGERS_FILE)) {
            Path file = folder.resolve(name);
            if (Files.exists(file)) {
                for (JsonElement element : read(file)) {
                    JsonObject obj = element.getAsJsonObject();
                    String key = (obj.has("type") ? obj.get("type").getAsString() : "") + "/"
                            + (obj.has("id") ? obj.get("id").getAsString() : "");
                    if (seen.add(key)) { // the same element in two files counts once
                        shops.add(element);
                    }
                }
            }
        }
        if (shops.isEmpty()) {
            return List.of();
        }
        Path placesFile = folder.resolve(OverpassDownloader.PLACES_FILE);
        List<Place> places = Files.exists(placesFile) ? parsePlaces(read(placesFile)) : List.of();
        return toDocuments(shops, places);
    }

    private static JsonArray read(Path file) throws StorageException {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray elements = root.getAsJsonArray("elements");
            if (elements == null) {
                throw new StorageException(file + " has no \"elements\" list (not an Overpass answer?).");
            }
            return elements;
        } catch (IOException e) {
            throw new StorageException("Could not read " + file + ": " + e.getMessage(), e);
        } catch (JsonParseException | IllegalStateException e) {
            throw new StorageException(file + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Places with a name and coordinates; others are ignored. */
    static List<Place> parsePlaces(JsonArray elements) {
        List<Place> places = new ArrayList<>();
        for (JsonElement element : elements) {
            JsonObject obj = element.getAsJsonObject();
            String name = tag(obj, "name");
            double[] point = point(obj);
            if (!name.isEmpty() && point != null) {
                places.add(new Place(name, point[0], point[1]));
            }
        }
        return places;
    }

    List<ServiceDocument> toDocuments(JsonArray elements, List<Place> places) {
        List<ServiceDocument> documents = new ArrayList<>();
        for (JsonElement element : elements) {
            ServiceDocument doc = toDocument(element.getAsJsonObject(), places);
            if (doc != null) {
                documents.add(doc);
            }
        }
        return documents;
    }

    /** @return the document, or null if the element has no usable name or position */
    ServiceDocument toDocument(JsonObject element, List<Place> places) {
        String type = element.has("type") ? element.get("type").getAsString() : "";
        long id = element.has("id") ? element.get("id").getAsLong() : -1;
        double[] point = point(element);
        if (type.isEmpty() || id < 0 || point == null) {
            return null;
        }
        boolean charger = "charging_station".equals(tag(element, "amenity"));
        boolean tyres = "tyres".equals(tag(element, "shop"));
        String kind = charger ? "EV charging station" : tyres ? "Tyre shop" : "Car repair shop";

        String name = tag(element, "name");
        if (name.isEmpty() && charger) {
            String operator = firstTag(element, "operator", "brand", "network");
            name = operator.isEmpty() ? "" : operator + " EV charging station";
        }
        if (name.isEmpty()) {
            return null; // an unnamed shop cannot be shown to a driver in a useful way
        }

        String city = tag(element, "addr:city");
        if (city.isEmpty()) {
            Place nearest = nearestPlace(point[0], point[1], places, MAX_PLACE_DISTANCE_KM);
            city = nearest == null ? "" : nearest.name();
        }
        String street = (tag(element, "addr:housenumber") + " " + tag(element, "addr:street")).trim();

        ServiceDocument doc = new ServiceDocument("https://www.openstreetmap.org/" + type + "/" + id, SOURCE_NAME);
        doc.setTitle(name);
        doc.setDescription(kind + (city.isEmpty() ? " in Ontario" : " in " + city + ", " + PROVINCE));
        doc.setAddress(street);
        doc.setCity(city);
        doc.setProvince(PROVINCE);
        doc.setContentHash(""); // OSM ids are unique; two chargers with the same text are still two places
        doc.setLocation(new GeoPoint(point[0], point[1]));
        doc.setOpeningHours(tag(element, "opening_hours"));
        doc.setWebsite(firstTag(element, "website", "contact:website"));

        List<String> phones = new ArrayList<>();
        for (String raw : splitList(firstTag(element, "phone", "contact:phone"))) {
            String phone = DataValidator.normalizePhone(raw);
            if (phone != null) {
                phones.add(phone);
            }
        }
        doc.setPhones(phones);
        String email = DataValidator.normalizeEmail(firstTag(element, "email", "contact:email"));
        doc.setEmails(email == null ? List.of() : List.of(email));
        String postalCode = DataValidator.normalizePostalCode(tag(element, "addr:postcode"));
        doc.setPostalCodes(postalCode == null ? List.of() : List.of(postalCode));

        Set<ServiceCategory> tagCategories = EnumSet.noneOf(ServiceCategory.class);
        List<String> services = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : tags(element).entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("service:vehicle:") && "yes".equals(entry.getValue().getAsString())) {
                String service = key.substring("service:vehicle:".length());
                services.add(service.replace('_', ' '));
                ServiceCategory category = SERVICE_TAGS.get(service);
                if (category != null) {
                    tagCategories.add(category);
                }
            }
        }
        if (tyres) {
            tagCategories.add(ServiceCategory.FLAT_TIRE);
        }
        Set<String> connectors = new LinkedHashSet<>();
        if (charger) {
            tagCategories.add(ServiceCategory.EV_CHARGING);
            for (Map.Entry<String, String> socket : SOCKETS.entrySet()) {
                if (!tag(element, "socket:" + socket.getKey()).isEmpty()
                        && !"no".equals(tag(element, "socket:" + socket.getKey()))) {
                    connectors.add(socket.getValue());
                }
            }
        }

        doc.setBodyText(bodyText(element, kind, services, connectors, street, city, postalCode, phones));

        // Keyword rules as for web pages ("Midas Brakes" -> BRAKES), plus the tag categories
        categorizer.tag(doc);
        Set<VehicleType> vehicles = EnumSet.noneOf(VehicleType.class);
        vehicles.addAll(doc.getVehicleTypes());
        for (ServiceCategory category : tagCategories) {
            doc.addCategory(category);
            vehicles.addAll(category.vehicleTypes());
        }
        if (!charger && !tagCategories.isEmpty()) {
            // A repair shop fixes cars in general: tagging brakes must not hide it from EV drivers
            vehicles.add(VehicleType.EV);
            vehicles.add(VehicleType.GAS);
        }
        doc.setVehicleTypes(vehicles);
        return doc;
    }

    /** Plain sentences from the tags only, e.g. "Car repair shop. Services: brakes, tyres. ..." */
    private static String bodyText(JsonObject element, String kind, List<String> services, Set<String> connectors,
                                   String street, String city, String postalCode, List<String> phones) {
        List<String> sentences = new ArrayList<>();
        sentences.add(kind + ("Car repair shop".equals(kind) ? " (car repair)" : ""));
        String brand = firstTag(element, "brand", "operator");
        if (!brand.isEmpty()) {
            sentences.add("Operator: " + brand);
        }
        if (!services.isEmpty()) {
            sentences.add("Services: " + String.join(", ", services));
        }
        if (!connectors.isEmpty()) {
            sentences.add("Connectors: " + String.join(", ", connectors));
        }
        String capacity = tag(element, "capacity");
        if (!capacity.isEmpty()) {
            sentences.add("Charging points: " + capacity);
        }
        String hours = tag(element, "opening_hours");
        if (!hours.isEmpty()) {
            sentences.add("Opening hours: " + hours);
        }
        List<String> address = new ArrayList<>();
        if (!street.isEmpty()) {
            address.add(street);
        }
        if (!city.isEmpty()) {
            address.add(city);
        }
        address.add("Ontario");
        if (postalCode != null) {
            address.add(postalCode);
        }
        sentences.add("Address: " + String.join(", ", address));
        if (!phones.isEmpty()) {
            sentences.add("Phone: " + String.join(", ", phones));
        }
        String website = firstTag(element, "website", "contact:website");
        if (!website.isEmpty()) {
            sentences.add("Website: " + website);
        }
        return String.join(". ", sentences) + ".";
    }

    // ------------------------------------------------------------ geography

    /**
     * Closest place by straight-line distance, or null if none is within maxKm.
     * Linear scan: O(P) per shop.
     */
    static Place nearestPlace(double lat, double lon, List<Place> places, double maxKm) {
        Place best = null;
        double bestKm = maxKm;
        for (Place place : places) {
            double km = distanceKm(lat, lon, place.lat(), place.lon());
            if (km <= bestKm) {
                bestKm = km;
                best = place;
            }
        }
        return best;
    }

    /** Haversine great-circle distance (see GeoPoint.distanceKm). */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        return new GeoPoint(lat1, lon1).distanceKm(new GeoPoint(lat2, lon2));
    }

    // ---------------------------------------------------------------- tags

    /** Nodes have lat/lon; ways and relations have a "center" (from "out center"). */
    private static double[] point(JsonObject element) {
        JsonObject source = element.has("lat") ? element
                : element.has("center") ? element.getAsJsonObject("center") : null;
        if (source == null || !source.has("lat") || !source.has("lon")) {
            return null;
        }
        return new double[] {source.get("lat").getAsDouble(), source.get("lon").getAsDouble()};
    }

    private static JsonObject tags(JsonObject element) {
        return element.has("tags") ? element.getAsJsonObject("tags") : new JsonObject();
    }

    static String tag(JsonObject element, String key) {
        JsonObject tags = tags(element);
        return tags.has(key) ? tags.get(key).getAsString().trim() : "";
    }

    private static String firstTag(JsonObject element, String... keys) {
        for (String key : keys) {
            String value = tag(element, key);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /** OSM separates several values with ";" ("519-555-0100;519-555-0101"). */
    private static List<String> splitList(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split(";")) {
            if (!part.isBlank()) {
                parts.add(part.trim().toLowerCase(Locale.ROOT).startsWith("tel:") ? part.trim().substring(4) : part.trim());
            }
        }
        return parts;
    }
}

package ca.carcareplus.osm;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.JsonParser;

import ca.carcareplus.crawler.WebCrawler;
import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Downloads OpenStreetMap data for Ontario from the public Overpass API:
 * car repair shops, tyre shops and EV charging stations, plus every city, town
 * and village (used to give each shop a city).
 *
 * Why OpenStreetMap: its data may be copied and reused under the Open Database
 * Licence (ODbL) as long as "© OpenStreetMap contributors" is credited, so it is
 * a legal source of real shop data while website crawling waits for approval.
 *
 * Three small queries (repair/tyre shops, EV chargers, places) instead of one
 * big one: a big query for all of Ontario can time out on busy public servers.
 * Each answer is saved to its own file as soon as it arrives, and a part that is
 * already on disk is not downloaded again. So when busy servers make one part
 * fail, running the command again only fetches what is still missing. To refresh
 * everything, delete the data/osm folder first.
 *
 * Politeness: three requests per run, plus at most one retry per server after a
 * "busy" answer, identified with the crawler's User-Agent. Parsing never downloads.
 */
public final class OverpassDownloader {

    public static final String DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter";

    /**
     * Public Overpass servers with the same OpenStreetMap data, tried in this order.
     * A server may refuse a network (HTTP 406/403), be busy (429/504) or be down, so
     * the next one is tried instead of giving up.
     */
    public static final List<String> PUBLIC_ENDPOINTS = List.of(
            DEFAULT_ENDPOINT,
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter");
    static final Duration TIMEOUT = Duration.ofMinutes(5);
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(20);

    /** Ontario's boundary in OSM is tagged ISO3166-2=CA-ON. */
    static final String ONTARIO_AREA = "area[\"ISO3166-2\"=\"CA-ON\"][admin_level=4]->.on;";

    /** "out center" gives buildings (ways) one point, so every shop has coordinates. */
    public static final String REPAIR_QUERY = "[out:json][timeout:180];" + ONTARIO_AREA
            + "nwr(area.on)[\"shop\"~\"^(car_repair|tyres)$\"];"
            + "out center tags;";

    public static final String CHARGER_QUERY = "[out:json][timeout:180];" + ONTARIO_AREA
            + "nwr(area.on)[\"amenity\"=\"charging_station\"];"
            + "out center tags;";

    public static final String PLACES_QUERY = "[out:json][timeout:120];" + ONTARIO_AREA
            + "node(area.on)[\"place\"~\"^(city|town|village)$\"][\"name\"];"
            + "out;";

    /** Older single-file layout (still read by OsmImporter; used by the tests' sample). */
    public static final String SHOPS_FILE = "ontario-shops.json";
    public static final String REPAIRS_FILE = "ontario-repairs.json";
    public static final String CHARGERS_FILE = "ontario-chargers.json";
    public static final String PLACES_FILE = "ontario-places.json";

    /** What one run did with each part. */
    public record Report(List<String> downloaded, List<String> skipped, List<String> failed, long bytes) {
        public boolean complete() {
            return failed.isEmpty();
        }
    }

    private static final Logger LOG = Logger.getLogger(OverpassDownloader.class.getName());

    private final HttpClient client;
    private final List<String> endpoints;
    private final Duration retryDelay;
    private String usedEndpoint = "";

    /** One server only. */
    public OverpassDownloader(String endpoint) {
        this(endpoint == null ? null : List.of(endpoint));
    }

    /** Several servers, tried in order until one answers. */
    public OverpassDownloader(List<String> endpoints) {
        this(endpoints, DEFAULT_RETRY_DELAY);
    }

    /** @param retryDelay wait before retrying a busy server (tests use a short one) */
    OverpassDownloader(List<String> endpoints, Duration retryDelay) {
        this.retryDelay = retryDelay;
        if (endpoints == null || endpoints.isEmpty() || endpoints.stream().anyMatch(e -> e == null || e.isBlank())) {
            throw new InvalidInputException("Overpass endpoint is required.");
        }
        this.endpoints = List.copyOf(endpoints);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** The server that answered last, e.g. for the console message. */
    public String usedEndpoint() {
        return usedEndpoint;
    }

    /**
     * Downloads the parts that are not in the folder yet: repair/tyre shops,
     * EV chargers and places. A part that fails on every server is reported in
     * the result; the other parts are still saved.
     *
     * @throws CarCareException if the folder cannot be created or a file cannot be written
     */
    public Report downloadOntario(Path folder) throws CarCareException {
        if (folder == null) {
            throw new InvalidInputException("Output folder is required.");
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new CarCareException("Could not create " + folder + ": " + e.getMessage(), e);
        }
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put(REPAIRS_FILE, REPAIR_QUERY);
        parts.put(CHARGERS_FILE, CHARGER_QUERY);
        parts.put(PLACES_FILE, PLACES_QUERY);
        List<String> downloaded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long bytes = 0;
        for (Map.Entry<String, String> part : parts.entrySet()) {
            Path target = folder.resolve(part.getKey());
            if (Files.exists(target)) {
                skipped.add(part.getKey());
                continue;
            }
            try {
                String body = download(part.getValue());
                checkElements(body);
                save(body, target);
                downloaded.add(part.getKey());
                bytes += body.getBytes(StandardCharsets.UTF_8).length;
            } catch (CarCareException e) {
                if (e instanceof SaveException) {
                    throw e;
                }
                LOG.warning(() -> part.getKey() + " not downloaded: " + e.getMessage());
                failed.add(part.getKey() + " (" + e.getMessage() + ")");
            }
        }
        return new Report(downloaded, skipped, failed, bytes);
    }

    /** An answer must be Overpass JSON with an "elements" list. */
    static void checkElements(String body) throws CarCareException {
        try {
            if (JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("elements") == null) {
                throw new CarCareException("The answer has no \"elements\" list.");
            }
        } catch (RuntimeException e) {
            throw new CarCareException("The answer was not Overpass JSON: " + e.getMessage(), e);
        }
    }

    /** Writing to disk failed: not a server problem, so the run stops. */
    private static final class SaveException extends CarCareException {
        private static final long serialVersionUID = 1L;

        SaveException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void save(String body, Path target) throws CarCareException {
        try {
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, body, StandardCharsets.UTF_8);
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SaveException("Could not save " + target + ": " + e.getMessage(), e);
        }
    }

    /** Tries each server in order; returns the answer of the first that answers. */
    String download(String query) throws CarCareException {
        List<String> failures = new ArrayList<>();
        // Start with the server that worked last time (shops and places use the same one)
        List<String> order = new ArrayList<>(endpoints);
        if (order.remove(usedEndpoint)) {
            order.add(0, usedEndpoint);
        }
        for (String endpoint : order) {
            try {
                String body = fetchWithRetry(endpoint, query);
                usedEndpoint = endpoint;
                return body;
            } catch (CarCareException e) {
                LOG.warning(() -> endpoint + ": " + e.getMessage() + " Trying the next server...");
                failures.add(URI.create(endpoint).getHost() + ": " + e.getMessage());
            }
        }
        throw new CarCareException("No Overpass server answered. " + String.join(" | ", failures));
    }

    /** A "busy" answer (429, 502, 503, 504) gets one more try after a short wait. */
    private String fetchWithRetry(String endpoint, String query) throws CarCareException {
        try {
            return fetch(endpoint, query);
        } catch (BusyException e) {
            LOG.info(() -> endpoint + " is busy; retrying in " + retryDelay.toSeconds() + " s...");
            try {
                Thread.sleep(retryDelay.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CarCareException("Download interrupted.", interrupted);
            }
            return fetch(endpoint, query);
        }
    }

    /** The server answered "busy, try later". */
    private static final class BusyException extends CarCareException {
        private static final long serialVersionUID = 1L;

        BusyException(String message) {
            super(message);
        }
    }

    String fetch(String endpoint, String query) throws CarCareException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("User-Agent", WebCrawler.USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CarCareException("Could not reach the Overpass API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CarCareException("Download interrupted.", e);
        }
        int status = response.statusCode();
        if (status == 429 || status == 502 || status == 503 || status == 504) {
            throw new BusyException("Overpass API answered HTTP " + status + " (server busy).");
        }
        if (status != 200) {
            throw new CarCareException("Overpass API answered HTTP " + status + ".");
        }
        // Overpass reports query timeouts inside a 200 answer
        if (response.body().contains("\"remark\"") && response.body().contains("runtime error")) {
            throw new BusyException("Overpass API could not finish the query in time (server busy).");
        }
        return response.body();
    }
}

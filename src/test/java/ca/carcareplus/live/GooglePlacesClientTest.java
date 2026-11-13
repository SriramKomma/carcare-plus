package ca.carcareplus.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.models.GeoPoint;

/**
 * GooglePlacesClient against a FAKE Google server on localhost. The answer below
 * is invented in Google's documented format; no real Google request is made.
 */
class GooglePlacesClientTest {

    static final String FAKE_ANSWER = """
            {"places": [
              {"id": "a1", "displayName": {"text": "Test Auto Service"},
               "formattedAddress": "100 Test St, Windsor, ON N9A 1A1, Canada",
               "location": {"latitude": 42.31, "longitude": -83.03},
               "nationalPhoneNumber": "(519) 555-0100", "rating": 4.6, "userRatingCount": 128,
               "currentOpeningHours": {"openNow": true},
               "regularOpeningHours": {"weekdayDescriptions": ["Monday: 8:00 AM - 5:00 PM", "Sunday: Closed"]},
               "googleMapsUri": "https://maps.google.com/?cid=1", "websiteUri": "https://test-auto.example",
               "businessStatus": "OPERATIONAL",
               "reviews": [
                 {"rating": 5, "text": {"text": "Fast and honest."}, "relativePublishTimeDescription": "a week ago",
                  "authorAttribution": {"displayName": "Test Reviewer", "uri": "https://maps.google.com/u/1"}},
                 {"rating": 4, "text": {"text": "Good."}}, {"rating": 3}, {"rating": 2}]},
              {"id": "a2", "displayName": {"text": "Test Closed Garage"}, "businessStatus": "CLOSED_PERMANENTLY"},
              {"id": "a3", "displayName": {"text": "Test Minimal Shop"}}
            ]}""";

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastHeaders = new AtomicReference<>();
    private int status = 200;
    private String answer = FAKE_ANSWER;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/places:searchText", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastHeaders.set(Map.of(
                    "key", String.valueOf(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key")),
                    "mask", String.valueOf(exchange.getRequestHeaders().getFirst("X-Goog-FieldMask"))));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GooglePlacesClient client() {
        return new GooglePlacesClient("test-key", "http://localhost:" + server.getAddress().getPort() + "/v1/places:searchText");
    }

    @Test
    void sendsKeyFieldMaskAndQuery() throws Exception {
        client().search("brake repair", "Windsor", null, 10);

        assertEquals("test-key", lastHeaders.get().get("key"));
        assertTrue(lastHeaders.get().get("mask").contains("places.rating"));
        JsonObject body = JsonParser.parseString(lastBody.get()).getAsJsonObject();
        assertEquals("brake repair in Windsor, Ontario", body.get("textQuery").getAsString());
        assertEquals("CA", body.get("regionCode").getAsString());
        assertTrue(!body.has("locationBias"));
    }

    @Test
    void nearMeAddsALocationBiasCappedAt50Km() throws Exception {
        client().search("tire shop", "", new GeoPoint(42.3, -83.0), 80);

        JsonObject circle = JsonParser.parseString(lastBody.get()).getAsJsonObject()
                .getAsJsonObject("locationBias").getAsJsonObject("circle");
        assertEquals(42.3, circle.getAsJsonObject("center").get("latitude").getAsDouble());
        assertEquals(50_000, circle.get("radius").getAsDouble());
        assertEquals("tire shop", JsonParser.parseString(lastBody.get()).getAsJsonObject().get("textQuery").getAsString());
    }

    @Test
    void parsesPlacesSkipsClosedOnesAndKeepsThreeReviews() throws Exception {
        List<GooglePlacesClient.Place> places = client().search("car repair", "N9A 1A1", null, 10);

        assertEquals(2, places.size(), "permanently closed place left out");
        GooglePlacesClient.Place p = places.get(0);
        assertEquals("Test Auto Service", p.name());
        assertEquals(4.6, p.rating());
        assertEquals(128, p.ratingCount());
        assertEquals(true, p.openNow());
        assertEquals(List.of("Monday: 8:00 AM - 5:00 PM", "Sunday: Closed"), p.hours());
        assertEquals(42.31, p.latitude());
        assertEquals(3, p.reviews().size());
        assertEquals(new GooglePlacesClient.Review("Test Reviewer", "https://maps.google.com/u/1", 5,
                "Fast and honest.", "a week ago"), p.reviews().get(0));
        assertEquals("Google user", p.reviews().get(1).author());

        GooglePlacesClient.Place minimal = places.get(1);
        assertNull(minimal.rating());
        assertNull(minimal.openNow());
        assertNull(minimal.latitude());
        assertEquals("", minimal.phone());
    }

    @Test
    void googleErrorsBecomeServiceUnavailableWithGooglesMessage() {
        status = 403;
        answer = "{\"error\": {\"code\": 403, \"message\": \"API key not valid. Please pass a valid API key.\"}}";
        ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class,
                () -> client().search("car repair", "Windsor", null, 10));
        assertTrue(e.getMessage().contains("HTTP 403"), e.getMessage());
        assertTrue(e.getMessage().contains("API key not valid"), e.getMessage());
    }

    @Test
    void unreadableAnswerAndUnreachableServer() {
        answer = "<html>oops</html>";
        assertThrows(ServiceUnavailableException.class, () -> client().search("car repair", "Windsor", null, 10));
        GooglePlacesClient nowhere = new GooglePlacesClient("k", "http://localhost:1/nothing");
        assertThrows(ServiceUnavailableException.class, () -> nowhere.search("car repair", "Windsor", null, 10));
    }

    @Test
    void inputIsValidatedBeforeAnyRequest() {
        assertThrows(InvalidInputException.class, () -> client().search("<script>", "Windsor", null, 10));
        assertThrows(InvalidInputException.class, () -> client().search("car repair", "N9B 3P", null, 10));
        assertThrows(InvalidInputException.class, () -> client().search("car repair", "", null, 10),
                "needs a city or a position");
        assertNull(lastBody.get(), "nothing was sent to Google");
        assertThrows(InvalidInputException.class, () -> new GooglePlacesClient(" ", "http://x"));
    }

    @Test
    void errorMessageFallsBackForNonJson() {
        assertEquals("no details", GooglePlacesClient.errorMessage("Bad Gateway"));
    }
}

package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.storage.RawPageStore;

/** Starts the real server on a free local port and talks to it over HTTP. */
class WebServerTest {

    @TempDir
    Path dir;

    private WebServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws CarCareException {
        RawPageStore rawPages = new RawPageStore(dir.resolve("raw-pages"));
        Path parsedDir = dir.resolve("parsed-pages");
        HTMLParser parser = new HTMLParser(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
        FixtureLoader.load(Path.of("fixtures", "html"), rawPages);
        new ParsePipeline(rawPages, parser, parsedDir).parseSources(List.of(FixtureLoader.SOURCE_NAME));
        server = WebServer.start(SearchSystem.load(parsedDir, dir.resolve("history.txt")), 0, null); // no Google key in tests
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + pathAndQuery)).GET().build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesThePage() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(response.body().contains("<title>CarCare+</title>"));
        assertEquals(404, get("/secret.txt").statusCode());
    }

    @Test
    void searchAnswersJson() throws Exception {
        HttpResponse<String> response = get("/api/search?vehicle=gas&category=BRAKES&q=brake+pads&location=T2P%201A1");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.body().contains("\"query\":\"brake pads\""), response.body());
        assertTrue(response.body().contains("\"location\":\"T2P 1A1\""), response.body());
        assertTrue(response.body().contains("brake-shop-calgary"), response.body());
    }

    @Test
    void badInputIs400WithMessage() throws Exception {
        HttpResponse<String> response = get("/api/search?vehicle=gas&q=%3Cscript%3E");
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Search text may only contain"), response.body());
        // Broken % encoding (q=%ZZ) is tested in parseQueryDecodesValues: HttpClient refuses to send such a URL
    }

    @Test
    void unknownApiPathAndWrongMethodAreRejected() throws Exception {
        assertEquals(404, get("/api/searchx").statusCode());
        HttpResponse<String> post = send(HttpRequest.newBuilder(URI.create(server.url() + "api/history"))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertEquals(405, post.statusCode());
    }

    @Test
    void portInUseGivesClearError() {
        CarCareException e = assertThrows(CarCareException.class,
                () -> WebServer.start(SearchSystem.load(dir.resolve("parsed-pages"), dir.resolve("h.txt")), server.port()));
        assertTrue(e.getMessage().contains("already in use"), e.getMessage());
    }

    @Test
    void liveSearchWithoutKeyIs503() throws Exception {
        HttpResponse<String> response = get("/api/live?q=car+repair&location=Windsor");
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("GOOGLE_MAPS_API_KEY"), response.body());
    }

    @Test
    void parseQueryDecodesValues() {
        assertEquals(Map.of("q", "brake pads", "location", "N9B 3P4", "empty", ""),
                WebServer.parseQuery("q=brake+pads&location=N9B%203P4&empty&&"));
        assertEquals(Map.of(), WebServer.parseQuery(null));
        assertThrows(InvalidInputException.class, () -> WebServer.parseQuery("q=%ZZ"));
    }

    @Test
    void parsePortChecksRange() {
        assertEquals(8080, Main.parsePort(" 8080 "));
        assertThrows(InvalidInputException.class, () -> Main.parsePort("0"));
        assertThrows(InvalidInputException.class, () -> Main.parsePort("70000"));
        assertThrows(InvalidInputException.class, () -> Main.parsePort("web"));
    }
}

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
    void visitRequestsArePostedAsAForm() throws Exception {
        // real clock here, so take the first time the server offers (fixture pages have no hours: tomorrow morning at the latest)
        String url = "https://fixtures.carcareplus.invalid/brake-shop-calgary";
        HttpResponse<String> slots = get("/api/visit/slots?url=" + java.net.URLEncoder.encode(url, "UTF-8"));
        assertEquals(200, slots.statusCode(), slots.body());
        java.util.regex.Matcher first = java.util.regex.Pattern.compile("\"value\":\"([^\"]+)\"").matcher(slots.body());
        assertTrue(first.find(), slots.body());

        String form = "url=" + java.net.URLEncoder.encode(url, "UTF-8") + "&slot=" + first.group(1)
                + "&name=Sam+Lee&phone=519-555-0100";
        HttpResponse<String> posted = send(HttpRequest.newBuilder(URI.create(server.url() + "api/visit"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
        assertEquals(200, posted.statusCode(), posted.body());
        assertTrue(posted.body().contains("\"id\":\"CC-1001\""), posted.body());

        assertEquals(405, get("/api/visit?" + form).statusCode(), "saving needs POST");
        HttpResponse<String> notAForm = send(HttpRequest.newBuilder(URI.create(server.url() + "api/visit"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        assertEquals(400, notAForm.statusCode());
        HttpResponse<String> tooBig = send(HttpRequest.newBuilder(URI.create(server.url() + "api/visit"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("note=" + "x".repeat(WebServer.MAX_BODY_BYTES))).build());
        assertEquals(400, tooBig.statusCode());
        assertTrue(get("/api/visits?ids=CC-1001").body().contains("Sam Lee"));
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

    @Test
    void manySearchesAtOnceAreAllAnsweredAndAllCounted() throws Exception {
        // 20 searches sent together: the pool answers them in parallel, and the shared
        // lock makes sure no search-history update is lost (20 searches -> count 20)
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> pending = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            pending.add(client.sendAsync(HttpRequest.newBuilder(URI.create(
                    "http://localhost:" + server.port() + "/api/search?vehicle=gas&q=towing")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (var future : pending) {
            assertEquals(200, future.get(30, java.util.concurrent.TimeUnit.SECONDS).statusCode());
        }
        String history = get("/api/history").body();
        assertTrue(history.contains("{\"term\":\"towing\",\"count\":20}"), history);
    }

    private HttpResponse<String> postForm(String path, String form) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(server.url() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
    }

    @Test
    void servesTheLiveDemoPage() throws Exception {
        HttpResponse<String> response = get("/live-demo");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<title>CarCare+ Live dispatch</title>"));
        assertTrue(get("/").body().contains("href=\"/live-demo\""), "the main page links to it");
    }

    @Test
    void liveDispatchOverHttpGivesTheJobToExactlyOneRepairer() throws Exception {
        String repairers = get("/api/repairers").body();
        assertTrue(repairers.contains("\"name\":\"Sriram Komma\""), repairers);

        HttpResponse<String> posted = postForm("api/jobs", "name=Sam+Lee&phone=519+555+0100&area=Windsor&vehicle=gas"
                + "&q=flat+tire&repairers=sriram,sarvesh,saikrishna,dharanish");
        assertEquals(200, posted.statusCode(), posted.body());
        assertTrue(posted.body().contains("\"id\":\"JOB-1\""), posted.body());
        assertTrue(get("/api/jobs/live?repairer=dharanish").body().contains("\"mine\":\"NEW\""));

        // All four press Accept at the same moment, as four separate HTTP requests
        List<String> team = List.of("sriram", "sarvesh", "saikrishna", "dharanish");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(team.size());
        try {
            java.util.concurrent.CountDownLatch gun = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<String>> answers = new java.util.ArrayList<>();
            for (String repairer : team) {
                answers.add(pool.submit(() -> {
                    gun.await();
                    return postForm("api/jobs/accept", "id=JOB-1&repairer=" + repairer).body();
                }));
            }
            gun.countDown();
            int winners = 0;
            for (java.util.concurrent.Future<String> answer : answers) {
                if (answer.get(10, java.util.concurrent.TimeUnit.SECONDS).contains("\"accepted\":true")) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
        String customer = get("/api/jobs/live?job=JOB-1").body();
        assertTrue(customer.contains("\"status\":\"ASSIGNED\""), customer);
        assertTrue(customer.contains("accepted your request"), customer);
        assertEquals(405, get("/api/jobs/accept?id=JOB-1&repairer=sriram").statusCode(), "accepting needs POST");
        assertEquals(400, get("/api/jobs/live?repairer=nobody").statusCode());
    }

    @Test
    void requestsThroughATunnelMayOnlyBringSmsReplies() throws Exception {
        HttpRequest viaTunnel = HttpRequest.newBuilder(URI.create(server.url() + "api/stats"))
                .header("X-Forwarded-For", "203.0.113.5").GET().build();
        assertEquals(403, send(viaTunnel).statusCode(), "the search API is not open to the internet");
        assertEquals(403, send(HttpRequest.newBuilder(URI.create(server.url())).header("X-Forwarded-For", "203.0.113.5")
                .GET().build()).statusCode(), "nor the page");
        // The SMS address answers, but without Twilio set up (tests) no reply is believed
        HttpResponse<String> sms = send(HttpRequest.newBuilder(URI.create(server.url() + "api/sms/incoming"))
                .header("X-Forwarded-For", "203.0.113.5").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("From=%2B15195550102&Body=ACCEPTED")).build());
        assertEquals(403, sms.statusCode());
        assertTrue(sms.headers().firstValue("Content-Type").orElse("").startsWith("text/xml"));
        assertEquals(200, get("/api/stats").statusCode(), "this computer is still served");
    }

    @Test
    void pretendPhonesWorkOverHttp() throws Exception {
        postForm("api/jobs", "name=Sam+Lee&phone=519+555+0100&vehicle=gas&q=flat+tire&repairers=sriram,sarvesh");
        String phone = "";
        for (int i = 0; i < 50 && !phone.contains("JOB-1"); i++) { // texts are sent on the SMS thread
            phone = get("/api/sms/phone?repairer=sriram").body();
            Thread.sleep(20);
        }
        assertTrue(phone.contains("CarCare+ repair request JOB-1"), phone);
        HttpResponse<String> reply = postForm("api/sms/phone/reply", "repairer=sriram&text=ACCEPTED");
        assertEquals(200, reply.statusCode(), reply.body());
        assertTrue(get("/api/jobs/live?job=JOB-1").body().contains("Sriram Komma accepted your request"));
    }
}

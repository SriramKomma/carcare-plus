package ca.carcareplus.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.parser.ServiceCategorizer;

/** Server fallback, retries and resuming, using fake servers on localhost (no internet). */
class OverpassDownloaderTest {

    /** A fake answer whose element id says which query it answered. */
    static String answerFor(String query) {
        int id = query.contains("car_repair") ? 1 : query.contains("charging_station") ? 2 : 3;
        return "{\"elements\": [{\"type\": \"node\", \"id\": " + id + "}]}";
    }

    private static boolean isChargers(String query) {
        return query.contains("charging_station");
    }

    private final List<HttpServer> servers = new ArrayList<>();

    /** @param reply (call number, query) -> {status, body} */
    private String fake(AtomicInteger calls, BiFunction<Integer, String, Object[]> reply) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/interpreter", exchange -> {
            int call = calls.incrementAndGet();
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String query = URLDecoder.decode(form.substring("data=".length()), StandardCharsets.UTF_8);
            Object[] answer = reply.apply(call, query);
            byte[] bytes = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders((Integer) answer[0], bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        servers.add(server);
        return "http://localhost:" + server.getAddress().getPort() + "/api/interpreter";
    }

    private static Object[] ok(String query) {
        return new Object[] {200, answerFor(query)};
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    private static OverpassDownloader downloader(String... urls) {
        return new OverpassDownloader(List.of(urls), Duration.ofMillis(10));
    }

    @Test
    void refusedServerIsSkippedAndEachPartIsSaved(@TempDir Path dir) throws Exception {
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger working = new AtomicInteger();
        String bad = fake(refused, (call, q) -> new Object[] {406, "<h1>406 Not Acceptable</h1>"});
        String good = fake(working, (call, q) -> ok(q));
        OverpassDownloader downloader = downloader(bad, good);

        OverpassDownloader.Report report = downloader.downloadOntario(dir);

        assertTrue(report.complete());
        assertEquals(List.of(OverpassDownloader.REPAIRS_FILE, OverpassDownloader.CHARGERS_FILE, OverpassDownloader.PLACES_FILE),
                report.downloaded());
        assertEquals(answerFor(OverpassDownloader.REPAIR_QUERY), Files.readString(dir.resolve(OverpassDownloader.REPAIRS_FILE)));
        assertEquals(answerFor(OverpassDownloader.CHARGER_QUERY), Files.readString(dir.resolve(OverpassDownloader.CHARGERS_FILE)));
        assertEquals(answerFor(OverpassDownloader.PLACES_QUERY), Files.readString(dir.resolve(OverpassDownloader.PLACES_FILE)));
        assertEquals(good, downloader.usedEndpoint());
        assertEquals(1, refused.get(), "later parts go straight to the server that worked");
        assertEquals(3, working.get());
    }

    @Test
    void failedPartDoesNotThrowAwayTheOthersAndASecondRunResumes(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String noChargers = fake(calls, (call, q) -> isChargers(q) ? new Object[] {504, "busy"} : ok(q));

        OverpassDownloader.Report first = downloader(noChargers).downloadOntario(dir);

        assertFalse(first.complete());
        assertEquals(List.of(OverpassDownloader.REPAIRS_FILE, OverpassDownloader.PLACES_FILE), first.downloaded());
        assertEquals(1, first.failed().size());
        assertTrue(first.failed().get(0).startsWith(OverpassDownloader.CHARGERS_FILE), first.failed().toString());
        assertTrue(Files.exists(dir.resolve(OverpassDownloader.REPAIRS_FILE)));
        assertFalse(Files.exists(dir.resolve(OverpassDownloader.CHARGERS_FILE)));

        AtomicInteger second = new AtomicInteger();
        String nowWorking = fake(second, (call, q) -> ok(q));
        OverpassDownloader.Report resumed = downloader(nowWorking).downloadOntario(dir);

        assertTrue(resumed.complete());
        assertEquals(List.of(OverpassDownloader.CHARGERS_FILE), resumed.downloaded());
        assertEquals(List.of(OverpassDownloader.REPAIRS_FILE, OverpassDownloader.PLACES_FILE), resumed.skipped());
        assertEquals(1, second.get(), "only the missing part was requested");
    }

    @Test
    void partialDownloadIsAlreadyUsableByTheImporter(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(OverpassDownloader.REPAIRS_FILE), """
                {"elements": [{"type": "node", "id": 10, "lat": 42.31, "lon": -83.03,
                  "tags": {"shop": "car_repair", "name": "Test Resumed Garage", "addr:city": "Windsor"}}]}""");
        OsmImporter importer = new OsmImporter(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
        assertEquals(1, importer.load(dir).size(), "repairs without chargers or places");

        Files.writeString(dir.resolve(OverpassDownloader.SHOPS_FILE), Files.readString(dir.resolve(OverpassDownloader.REPAIRS_FILE)));
        assertEquals(1, importer.load(dir).size(), "the same element in two files counts once");
    }

    @Test
    void busyServerIsRetriedOnceBeforeMovingOn(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String flaky = fake(calls, (call, q) -> call == 1 ? new Object[] {502, "Bad Gateway"} : ok(q));

        assertTrue(downloader(flaky).downloadOntario(dir).complete());
        assertEquals(4, calls.get(), "1 busy answer + 3 successful parts");
    }

    @Test
    void allServersFailingIsReportedAndNothingIsWritten(@TempDir Path dir) throws Exception {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        String refusing = fake(a, (call, q) -> new Object[] {406, "no"});
        String busy = fake(b, (call, q) -> new Object[] {504, "busy"});

        OverpassDownloader.Report report = downloader(refusing, busy).downloadOntario(dir);

        assertEquals(3, report.failed().size());
        assertTrue(report.failed().get(0).contains("No Overpass server answered"), report.failed().get(0));
        assertTrue(report.failed().get(0).contains("HTTP 406") && report.failed().get(0).contains("HTTP 504"));
        assertEquals(3, a.get(), "406 is not retried");
        assertEquals(6, b.get(), "504 is retried once per part");
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "no empty or half-written files");
        }
    }

    @Test
    void timeoutInsideA200AndNonJsonAreFailures(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String odd = fake(calls, (call, q) -> isChargers(q)
                ? new Object[] {200, "{\"elements\": [], \"remark\": \"runtime error: Query timed out\"}"}
                : new Object[] {200, "<html>maintenance</html>"});

        OverpassDownloader.Report report = downloader(odd).downloadOntario(dir);

        assertEquals(3, report.failed().size());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void checkElementsAcceptsOnlyOverpassJson() throws Exception {
        OverpassDownloader.checkElements("{\"elements\": []}");
        assertThrows(CarCareException.class, () -> OverpassDownloader.checkElements("{\"remark\": \"x\"}"));
        assertThrows(CarCareException.class, () -> OverpassDownloader.checkElements("<html>"));
    }

    @Test
    void serverListIsChecked() {
        assertThrows(InvalidInputException.class, () -> new OverpassDownloader(List.of()));
        assertThrows(InvalidInputException.class, () -> new OverpassDownloader((String) null));
        assertEquals(OverpassDownloader.DEFAULT_ENDPOINT, OverpassDownloader.PUBLIC_ENDPOINTS.get(0));
        assertTrue(OverpassDownloader.PUBLIC_ENDPOINTS.stream().noneMatch(u -> u.contains("mail.ru")),
                "its certificate is not trusted by Java");
    }
}

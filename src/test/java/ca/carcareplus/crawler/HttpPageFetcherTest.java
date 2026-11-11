package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.carcareplus.crawler.FetchResult.Outcome;
import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Tests the real HTTP code against a tiny web server running inside the test
 * (JDK's built-in com.sun.net.httpserver), so no internet access is needed and
 * every error case can be produced on demand.
 */
class HttpPageFetcherTest {

    private static final int MAX_BYTES = 1_000;
    private static HttpServer server;
    private static ExecutorService executor;
    private static String base;
    private static volatile String lastUserAgent;

    private final HttpPageFetcher fetcher =
            new HttpPageFetcher("TestAgent/1.0", Duration.ofMillis(800), MAX_BYTES);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> {
            lastUserAgent = ex.getRequestHeaders().getFirst("User-Agent");
            respond(ex, 200, "text/html; charset=UTF-8", "<html><title>Café Tires</title></html>");
        });
        server.createContext("/latin1", ex -> respondBytes(ex, 200, "text/html; charset=ISO-8859-1",
                "Café".getBytes(StandardCharsets.ISO_8859_1)));
        server.createContext("/missing", ex -> respond(ex, 404, "text/html", "not found"));
        server.createContext("/forbidden", ex -> respond(ex, 403, "text/html", "forbidden"));
        server.createContext("/pdf", ex -> respond(ex, 200, "application/pdf", "%PDF-1.4"));
        server.createContext("/big", ex -> respond(ex, 200, "text/html", "x".repeat(MAX_BYTES + 500)));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().set("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, "text/html", "too late");
        });
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        respondBytes(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respondBytes(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void fetchesHtmlPage() {
        FetchResult result = fetcher.fetch(base + "/ok");
        assertEquals(Outcome.OK, result.outcome());
        assertEquals(200, result.statusCode());
        assertTrue(result.isHtml());
        assertTrue(result.body().contains("Café Tires"), "UTF-8 must be decoded correctly");
    }

    @Test
    void sendsUserAgent() {
        fetcher.fetch(base + "/ok");
        assertEquals("TestAgent/1.0", lastUserAgent);
    }

    @Test
    void decodesDeclaredCharset() {
        assertEquals("Café", fetcher.fetch(base + "/latin1").body());
    }

    @Test
    void reports404() {
        FetchResult result = fetcher.fetch(base + "/missing");
        assertEquals(Outcome.HTTP_ERROR, result.outcome());
        assertEquals(404, result.statusCode());
    }

    @Test
    void reports403() {
        FetchResult result = fetcher.fetch(base + "/forbidden");
        assertEquals(Outcome.HTTP_ERROR, result.outcome());
        assertEquals(403, result.statusCode());
    }

    @Test
    void reportsNonHtmlContentType() {
        FetchResult result = fetcher.fetch(base + "/pdf");
        assertEquals(Outcome.OK, result.outcome());
        assertFalse(result.isHtml());
    }

    @Test
    void followsRedirects() {
        FetchResult result = fetcher.fetch(base + "/redirect");
        assertEquals(Outcome.OK, result.outcome());
        assertEquals(base + "/ok", result.finalUrl());
        assertEquals(base + "/redirect", result.requestedUrl());
    }

    @Test
    void rejectsTooLargeBody() {
        assertEquals(Outcome.TOO_LARGE, fetcher.fetch(base + "/big").outcome());
    }

    @Test
    void timesOutOnSlowServer() {
        FetchResult result = fetcher.fetch(base + "/slow");
        assertEquals(Outcome.TIMEOUT, result.outcome());
        assertEquals(0, result.statusCode());
    }

    @Test
    void reportsConnectionRefused() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        } // socket closed: nothing listens on this port any more
        FetchResult result = fetcher.fetch("http://127.0.0.1:" + closedPort + "/");
        assertEquals(Outcome.NETWORK_ERROR, result.outcome());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not a url", "ftp://example.ca/file", "/relative/path", "https://"})
    void rejectsInvalidUrls(String url) {
        assertEquals(Outcome.INVALID_URL, fetcher.fetch(url).outcome());
    }

    @Test
    void constructorValidatesSettings() {
        assertThrows(InvalidInputException.class, () -> new HttpPageFetcher(" "));
        assertThrows(InvalidInputException.class, () -> new HttpPageFetcher("A", Duration.ZERO, 10));
        assertThrows(InvalidInputException.class, () -> new HttpPageFetcher("A", Duration.ofSeconds(1), 0));
    }

    @Test
    void charsetFallsBackToUtf8() {
        assertEquals(StandardCharsets.UTF_8, HttpPageFetcher.charsetOf("text/html; charset=nonsense-123"));
        assertEquals(StandardCharsets.UTF_8, HttpPageFetcher.charsetOf(null));
        assertEquals(StandardCharsets.ISO_8859_1, HttpPageFetcher.charsetOf("text/html; charset=\"ISO-8859-1\""));
    }
}

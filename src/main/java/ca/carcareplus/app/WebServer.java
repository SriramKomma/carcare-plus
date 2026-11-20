package ca.carcareplus.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.live.GooglePlacesClient;
import ca.carcareplus.visits.VisitBook;

/**
 * A small web front end for trying every feature in a browser.
 *
 * Uses the HTTP server that ships with the JDK (com.sun.net.httpserver), so
 * there is no web framework and no new dependency. It is presentation only:
 * WebApi calls the same classes as the console menu.
 *
 * <pre>
 * GET /                  the page (src/main/resources/web/index.html)
 * GET /api/stats         pages and words loaded
 * GET /api/categories    ?vehicle=ev|gas
 * GET /api/search        ?vehicle=&category=&q=&location=
 * GET /api/complete      ?prefix=
 * GET /api/spell         ?word=
 * GET /api/matrix        ?a=&b=
 * GET /api/pages
 * GET /api/frequency     ?url=&word=
 * GET /api/history
 * GET /api/cities        cities that have pages (location suggestions)
 * GET /api/priority      ?vehicle=&lat=&lon=&q=   priority: nearest shops to call right now
 * GET /api/live          ?q=&location=&lat=&lon=&radius=   Google Places (needs GOOGLE_MAPS_API_KEY)
 * GET  /api/visit/slots  ?url=                    times to choose for a non-priority visit
 * POST /api/visit        url, slot, name, phone, email, note, q, vehicle   save a visit request
 * GET  /api/visits       ?ids=CC-1001,CC-1002     the driver's saved requests
 * POST /api/visit/cancel id=                       cancel one
 * </pre>
 *
 * POST bodies are form-encoded (application/x-www-form-urlencoded), at most
 * {@value #MAX_BODY_BYTES} bytes. Routes that change data accept POST only.
 *
 * Safety: it listens on the loopback address only (127.0.0.1), so other computers
 * cannot reach it.
 *
 * Threads: a small pool of {@value #THREADS} threads answers requests, so a slow
 * request (a "Live nearby" call to Google can take seconds) no longer blocks the
 * page, the map or the next search. The search structures (index, search history)
 * are NOT thread-safe, so every route that uses them runs while holding one shared
 * lock -- they still run one at a time, exactly as before. Only routes that touch
 * no shared state (/api/live, the page itself) run without the lock.
 */
public final class WebServer {

    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());
    private static final Gson GSON = new Gson();
    static final String PAGE_RESOURCE = "/web/index.html";
    static final int THREADS = 4;
    static final int MAX_BODY_BYTES = 8 * 1024;

    /** One API route: query parameters in, an object to send as JSON out. */
    @FunctionalInterface
    private interface Route {
        Object answer(Map<String, String> params) throws CarCareException;
    }

    private final HttpServer server;
    private final ExecutorService pool;

    private WebServer(HttpServer server, ExecutorService pool) {
        this.server = server;
        this.pool = pool;
    }

    /**
     * Starts the server. Port 0 picks a free port (used by tests).
     *
     * @throws CarCareException if the port is already in use
     */
    public static WebServer start(SearchSystem system, int port) throws CarCareException {
        return start(system, port, GooglePlacesClient.fromEnvironment());
    }

    /** @param live Google Places client for the "Live nearby" tab, or null to switch it off */
    public static WebServer start(SearchSystem system, int port, GooglePlacesClient live) throws CarCareException {
        return start(system, port, live, new VisitBook());
    }

    /** @param visits where visit requests are saved (Main uses data/visits/visits.json) */
    public static WebServer start(SearchSystem system, int port, GooglePlacesClient live, VisitBook visits)
            throws CarCareException {
        WebApi api = new WebApi(system, live, java.time.Clock.systemUTC(), visits);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new CarCareException("Could not start the web server on port " + port
                    + " (is it already in use?): " + e.getMessage(), e);
        }
        // One lock for everything that reads or changes the search structures
        Object searchLock = new Object();
        server.createContext("/", WebServer::servePage);
        route(server, "/api/stats", searchLock, params -> api.stats());
        route(server, "/api/categories", searchLock, api::categories);
        route(server, "/api/search", searchLock, api::search);
        route(server, "/api/complete", searchLock, api::complete);
        route(server, "/api/spell", searchLock, api::spell);
        route(server, "/api/matrix", searchLock, api::matrix);
        route(server, "/api/pages", searchLock, params -> api.pages());
        route(server, "/api/frequency", searchLock, api::frequency);
        route(server, "/api/history", searchLock, params -> api.history());
        route(server, "/api/cities", searchLock, params -> api.cities());
        route(server, "/api/priority", searchLock, api::priority);
        route(server, "/api/live", null, api::live); // network call to Google; no shared state
        route(server, "/api/visit/slots", searchLock, api::visitSlots);
        route(server, "/api/visits", searchLock, api::visits);
        route(server, "/api/visit", "POST", searchLock, api::requestVisit);
        route(server, "/api/visit/cancel", "POST", searchLock, api::cancelVisit);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, task -> {
            Thread thread = new Thread(task, "carcare-web");
            thread.setDaemon(true); // do not keep the program alive after the server stops
            return thread;
        });
        server.setExecutor(pool);
        server.start();
        return new WebServer(server, pool);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://localhost:" + port() + "/";
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    /** @param lock held while the route runs, or null for a route that shares no state */
    private static void route(HttpServer server, String path, Object lock, Route route) {
        route(server, path, "GET", lock, route);
    }

    /** @param method "GET" (parameters in the address) or "POST" (form-encoded body) */
    private static void route(HttpServer server, String path, String method, Object lock, Route route) {
        server.createContext(path, exchange -> {
            try (exchange) {
                if (!method.equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", method);
                    sendJson(exchange, 405, Map.of("error", "Only " + method + " is supported."));
                    return;
                }
                if (!exchange.getRequestURI().getPath().equals(path)) {
                    sendJson(exchange, 404, Map.of("error", "Not found."));
                    return;
                }
                try {
                    Map<String, String> params = "POST".equals(method)
                            ? parseQuery(readBody(exchange))
                            : parseQuery(exchange.getRequestURI().getRawQuery());
                    Object answer;
                    if (lock == null) {
                        answer = route.answer(params);
                    } else {
                        synchronized (lock) {
                            answer = route.answer(params);
                        }
                    }
                    sendJson(exchange, 200, answer); // sending happens outside the lock
                } catch (InvalidInputException e) {
                    sendJson(exchange, 400, Map.of("error", e.getMessage()));
                } catch (ServiceUnavailableException e) {
                    sendJson(exchange, 503, Map.of("error", e.getMessage()));
                } catch (CarCareException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Request " + exchange.getRequestURI() + " failed", e);
                    sendJson(exchange, 500, Map.of("error", "Something went wrong: " + e.getMessage()));
                }
            }
        });
    }

    /** The form-encoded body of a POST; too big a body is rejected before it is read in full. */
    private static String readBody(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            throw new InvalidInputException("Send the form as application/x-www-form-urlencoded.");
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new InvalidInputException("The form is too large.");
            }
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static void servePage(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/index.html")) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (InputStream page = WebServer.class.getResourceAsStream(PAGE_RESOURCE)) {
                if (page == null) {
                    send(exchange, 500, "text/plain; charset=utf-8",
                            ("Missing " + PAGE_RESOURCE).getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(exchange, 200, "text/html; charset=utf-8", page.readAllBytes());
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** "q=brake+pads&vehicle=gas" -> {q=brake pads, vehicle=gas}. A repeated name keeps the last value. */
    static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                params.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new InvalidInputException("The address has a badly encoded value.");
            }
        }
        return params;
    }
}

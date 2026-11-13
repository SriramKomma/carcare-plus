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
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.live.GooglePlacesClient;

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
 * GET /api/live          ?q=&location=&lat=&lon=&radius=   Google Places (needs GOOGLE_MAPS_API_KEY)
 * </pre>
 *
 * Safety: it listens on the loopback address only (127.0.0.1), so other computers
 * cannot reach it. No executor is set, so requests are handled one at a time on
 * one thread -- the search structures are not thread-safe, and one user does not
 * need more.
 */
public final class WebServer {

    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());
    private static final Gson GSON = new Gson();
    static final String PAGE_RESOURCE = "/web/index.html";

    /** One API route: query parameters in, an object to send as JSON out. */
    @FunctionalInterface
    private interface Route {
        Object answer(Map<String, String> params) throws CarCareException;
    }

    private final HttpServer server;

    private WebServer(HttpServer server) {
        this.server = server;
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
        WebApi api = new WebApi(system, live);
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new CarCareException("Could not start the web server on port " + port
                    + " (is it already in use?): " + e.getMessage(), e);
        }
        server.createContext("/", WebServer::servePage);
        route(server, "/api/stats", params -> api.stats());
        route(server, "/api/categories", api::categories);
        route(server, "/api/search", api::search);
        route(server, "/api/complete", api::complete);
        route(server, "/api/spell", api::spell);
        route(server, "/api/matrix", api::matrix);
        route(server, "/api/pages", params -> api.pages());
        route(server, "/api/frequency", api::frequency);
        route(server, "/api/history", params -> api.history());
        route(server, "/api/cities", params -> api.cities());
        route(server, "/api/live", api::live);
        server.start();
        return new WebServer(server);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://localhost:" + port() + "/";
    }

    public void stop() {
        server.stop(0);
    }

    private static void route(HttpServer server, String path, Route route) {
        server.createContext(path, exchange -> {
            try (exchange) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendJson(exchange, 405, Map.of("error", "Only GET is supported."));
                    return;
                }
                if (!exchange.getRequestURI().getPath().equals(path)) {
                    sendJson(exchange, 404, Map.of("error", "Not found."));
                    return;
                }
                try {
                    Object answer = route.answer(parseQuery(exchange.getRequestURI().getRawQuery()));
                    sendJson(exchange, 200, answer);
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

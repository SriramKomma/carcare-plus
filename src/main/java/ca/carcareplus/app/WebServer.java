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

import ca.carcareplus.dispatch.JobBoard;
import ca.carcareplus.dispatch.RepairerDirectory;
import ca.carcareplus.dispatch.SmsDispatcher;
import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.ServiceUnavailableException;
import ca.carcareplus.live.GooglePlacesClient;
import ca.carcareplus.sms.SimulatedSmsGateway;
import ca.carcareplus.sms.SmsGateway;
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
 * GET /live-demo         the live dispatch demo page (web/live-demo.html); routes in DispatchApi
 * POST /api/sms/incoming  SMS replies forwarded by Twilio; other SMS routes in SmsApi
 * </pre>
 *
 * POST bodies are form-encoded (application/x-www-form-urlencoded), at most
 * {@value #MAX_BODY_BYTES} bytes. Routes that change data accept POST only.
 *
 * Safety: it listens on the loopback address only (127.0.0.1), so other computers
 * cannot reach it. For real SMS replies a tunnel (e.g. ngrok) forwards Twilio's
 * requests from the internet to this computer; anything that arrives through a
 * tunnel (it carries an X-Forwarded-For header or another host name) may ONLY use
 * /api/sms/incoming, and that route believes a request only with Twilio's signature.
 * So the tunnel never exposes the search, the visit requests or the dispatch board.
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
    static final String DEMO_RESOURCE = "/web/live-demo.html";
    /** The repairers of the live dispatch demo. */
    static final java.nio.file.Path REPAIRERS_FILE = java.nio.file.Path.of("config", "repairers.txt");
    static final int THREADS = 8; // the live demo keeps 5+ windows asking twice a second
    static final int MAX_BODY_BYTES = 8 * 1024;

    /** One API route: query parameters in, an object to send as JSON out. */
    @FunctionalInterface
    private interface Route {
        Object answer(Map<String, String> params) throws CarCareException;
    }

    private final HttpServer server;
    private final ExecutorService pool;
    private final ExecutorService smsPool;
    private final SmsGateway sms;

    private WebServer(HttpServer server, ExecutorService pool, ExecutorService smsPool, SmsGateway sms) {
        this.server = server;
        this.pool = pool;
        this.smsPool = smsPool;
        this.sms = sms;
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
        return start(system, port, live, visits, RepairerDirectory.load(REPAIRERS_FILE), null);
    }

    /**
     * @param repairers who receives requests in the live dispatch demo
     * @param sms       real SMS (Main passes Twilio when it is set up), or null for pretend phones;
     *                  tests always pass null, so they never send a real text
     */
    public static WebServer start(SearchSystem system, int port, GooglePlacesClient live, VisitBook visits,
                                  RepairerDirectory repairers, SmsGateway sms) throws CarCareException {
        WebApi api = new WebApi(system, live, java.time.Clock.systemUTC(), visits);
        JobBoard board = new JobBoard(java.time.Clock.systemUTC());
        SmsGateway gateway = sms != null ? sms : new SimulatedSmsGateway(java.time.Clock.systemUTC());
        // One thread sends every SMS, in order, so no page request ever waits for Twilio
        ExecutorService smsPool = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "carcare-sms");
            thread.setDaemon(true);
            return thread;
        });
        SmsDispatcher texts = new SmsDispatcher(gateway, repairers, board, smsPool);
        DispatchApi dispatch = new DispatchApi(repairers, board, api::serviceFor, texts);
        SmsApi smsApi = new SmsApi(texts, repairers);
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
        route(server, "/api/problems", searchLock, api::problems);
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
        // Live dispatch demo. JobBoard has its own lock, so these do not wait for searches;
        // only posting a job holds the search lock, because it finds the service for the problem.
        route(server, "/api/repairers", null, params -> dispatch.repairers());
        route(server, "/api/jobs", "POST", searchLock, dispatch::post);
        route(server, "/api/jobs/accept", "POST", null, dispatch::accept);
        route(server, "/api/jobs/reject", "POST", null, dispatch::reject);
        route(server, "/api/jobs/done", "POST", null, dispatch::done);
        route(server, "/api/jobs/cancel", "POST", null, dispatch::cancel);
        route(server, "/api/jobs/live", null, dispatch::live);
        route(server, "/api/sms/phone", null, smsApi::phone);
        route(server, "/api/sms/phone/reply", "POST", null, smsApi::reply);
        server.createContext("/api/sms/incoming", exchange -> twilioReply(exchange, smsApi));
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, task -> {
            Thread thread = new Thread(task, "carcare-web");
            thread.setDaemon(true); // do not keep the program alive after the server stops
            return thread;
        });
        server.setExecutor(pool);
        server.start();
        return new WebServer(server, pool, smsPool, gateway);
    }

    /** How repairers get texts: "Twilio, from +1..." or "simulated phones ...". */
    public String smsDescription() {
        return sms.description();
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
        smsPool.shutdownNow();
    }

    /**
     * POST /api/sms/incoming: Twilio forwards an SMS reply. Twilio expects XML back
     * (TwiML); an empty Response means "send nothing now" -- our answer, if any, is sent
     * separately by SmsDispatcher.
     */
    private static void twilioReply(HttpExchange exchange, SmsApi smsApi) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendXml(exchange, 405);
                return;
            }
            if (!exchange.getRequestURI().getPath().equals("/api/sms/incoming")) {
                sendXml(exchange, 404);
                return;
            }
            try {
                Map<String, String> form = parseQuery(readBody(exchange));
                boolean genuine = smsApi.incoming(exchange.getRequestHeaders().getFirst("X-Twilio-Signature"), form);
                if (!genuine) {
                    LOG.warning("Ignored an SMS reply without a valid Twilio signature.");
                }
                sendXml(exchange, genuine ? 200 : 403);
            } catch (InvalidInputException e) {
                sendXml(exchange, 400);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "SMS reply failed", e);
                sendXml(exchange, 500);
            }
        }
    }

    private static void sendXml(HttpExchange exchange, int status) throws IOException {
        send(exchange, status, "text/xml; charset=utf-8",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * True if the request came from the internet through a tunnel rather than from a
     * browser on this computer: tunnels add X-Forwarded-For, and they pass on the
     * public host name (abc.ngrok-free.app) instead of localhost.
     */
    static boolean cameThroughTunnel(HttpExchange exchange) {
        if (exchange.getRequestHeaders().containsKey("X-Forwarded-For")) {
            return true;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1)
                : host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        name = name.toLowerCase(java.util.Locale.ROOT);
        return !(name.equals("localhost") || name.equals("127.0.0.1") || name.equals("[::1]"));
    }

    /** @param lock held while the route runs, or null for a route that shares no state */
    private static void route(HttpServer server, String path, Object lock, Route route) {
        route(server, path, "GET", lock, route);
    }

    /** @param method "GET" (parameters in the address) or "POST" (form-encoded body) */
    private static void route(HttpServer server, String path, String method, Object lock, Route route) {
        server.createContext(path, exchange -> {
            try (exchange) {
                if (cameThroughTunnel(exchange)) {
                    sendJson(exchange, 403, Map.of("error", "Only SMS replies are accepted from the internet."));
                    return;
                }
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
            if (cameThroughTunnel(exchange)) {
                send(exchange, 403, "text/plain; charset=utf-8", "Forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String resource = switch (path) {
                case "/", "/index.html" -> PAGE_RESOURCE;
                case "/live-demo", "/live-demo.html" -> DEMO_RESOURCE;
                default -> null;
            };
            if (resource == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (InputStream page = WebServer.class.getResourceAsStream(resource)) {
                if (page == null) {
                    send(exchange, 500, "text/plain; charset=utf-8",
                            ("Missing " + resource).getBytes(StandardCharsets.UTF_8));
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

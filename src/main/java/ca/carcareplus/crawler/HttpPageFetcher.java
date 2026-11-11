package ca.carcareplus.crawler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import ca.carcareplus.crawler.FetchResult.Outcome;
import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Real PageFetcher using Java's built-in HttpClient (no extra library).
 *
 * Safety limits:
 *  - connect and response timeouts, so one slow site cannot hang the crawl
 *  - a maximum body size, so one huge file cannot exhaust memory
 *  - redirects are followed, except from https to http
 *  - a descriptive User-Agent, so site owners can see who is crawling
 */
public class HttpPageFetcher implements PageFetcher {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_BODY_BYTES = 3 * 1024 * 1024;

    private static final String ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8";

    private final HttpClient client;
    private final String userAgent;
    private final Duration timeout;
    private final int maxBodyBytes;

    public HttpPageFetcher(String userAgent) {
        this(userAgent, DEFAULT_TIMEOUT, DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * @param userAgent    sent with every request; required
     * @param timeout      limit for connecting and for receiving the response headers
     * @param maxBodyBytes larger bodies are rejected with Outcome.TOO_LARGE
     */
    public HttpPageFetcher(String userAgent, Duration timeout, int maxBodyBytes) {
        if (userAgent == null || userAgent.isBlank()) {
            throw new InvalidInputException("User-Agent is required.");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new InvalidInputException("Timeout must be positive.");
        }
        if (maxBodyBytes < 1) {
            throw new InvalidInputException("Maximum body size must be positive.");
        }
        this.userAgent = userAgent.trim();
        this.timeout = timeout;
        this.maxBodyBytes = maxBodyBytes;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public FetchResult fetch(String url) {
        URI uri = toHttpUri(url);
        if (uri == null) {
            return FetchResult.failure(url, Outcome.INVALID_URL, "Not a valid http(s) URL");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", ACCEPT)
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            return FetchResult.failure(url, Outcome.INVALID_URL, e.getMessage());
        }

        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String finalUrl = response.uri().toString();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            int status = response.statusCode();

            byte[] bytes;
            try (InputStream in = response.body()) {
                // Read at most one byte more than allowed: enough to know it is too large
                bytes = in.readNBytes(maxBodyBytes + 1);
            }
            if (bytes.length > maxBodyBytes) {
                return FetchResult.failure(url, Outcome.TOO_LARGE,
                        "Response is larger than " + maxBodyBytes + " bytes");
            }
            if (status < 200 || status >= 300) {
                return FetchResult.httpError(url, finalUrl, status, contentType);
            }
            String body = new String(bytes, charsetOf(contentType));
            return FetchResult.ok(url, finalUrl, status, contentType, body);

        } catch (HttpTimeoutException e) {
            return FetchResult.failure(url, Outcome.TIMEOUT,
                    "No response within " + timeout.toMillis() + " ms");
        } catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return FetchResult.failure(url, Outcome.NETWORK_ERROR, reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // keep the interrupt so the crawler can stop
            return FetchResult.failure(url, Outcome.NETWORK_ERROR, "Interrupted");
        } catch (IllegalArgumentException e) {
            return FetchResult.failure(url, Outcome.INVALID_URL, e.getMessage());
        }
    }

    /** Parses and checks the URL; null if it is not an absolute http(s) URL with a host. */
    private static URI toHttpUri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Charset from "text/html; charset=ISO-8859-1"; UTF-8 if missing or unknown. */
    static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    String name = trimmed.substring("charset=".length()).replace("\"", "").trim();
                    try {
                        return Charset.forName(name);
                    } catch (IllegalArgumentException e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}

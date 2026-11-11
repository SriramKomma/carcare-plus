package ca.carcareplus.crawler;

import java.util.Locale;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * What happened when one URL was fetched.
 *
 * Failures are values, not exceptions: a 404 or a timeout is a normal event during a
 * crawl, and the crawler decides what to do with each outcome (skip, count, stop).
 *
 * @param requestedUrl the URL we asked for
 * @param finalUrl     the URL that answered, after redirects (same as requestedUrl if none)
 * @param outcome      OK or the kind of failure
 * @param statusCode   HTTP status, or 0 if no HTTP response was received
 * @param contentType  Content-Type header ("" if unknown)
 * @param body         response text ("" for failures)
 * @param errorMessage readable reason for failures ("" for OK)
 */
public record FetchResult(
        String requestedUrl,
        String finalUrl,
        Outcome outcome,
        int statusCode,
        String contentType,
        String body,
        String errorMessage) {

    public enum Outcome {
        OK,
        /** The server answered with a non-2xx status, e.g. 404 or 403. */
        HTTP_ERROR,
        TIMEOUT,
        /** DNS failure, connection refused, connection reset, ... */
        NETWORK_ERROR,
        INVALID_URL,
        /** Body bigger than the fetcher's limit. */
        TOO_LARGE
    }

    public FetchResult {
        if (outcome == null) {
            throw new InvalidInputException("FetchResult needs an outcome.");
        }
        requestedUrl = requestedUrl == null ? "" : requestedUrl;
        finalUrl = (finalUrl == null || finalUrl.isBlank()) ? requestedUrl : finalUrl;
        contentType = contentType == null ? "" : contentType.trim();
        body = body == null ? "" : body;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static FetchResult ok(String url, String finalUrl, int status, String contentType, String body) {
        return new FetchResult(url, finalUrl, Outcome.OK, status, contentType, body, "");
    }

    public static FetchResult httpError(String url, String finalUrl, int status, String contentType) {
        return new FetchResult(url, finalUrl, Outcome.HTTP_ERROR, status, contentType, "",
                "HTTP " + status);
    }

    public static FetchResult failure(String url, Outcome outcome, String message) {
        return new FetchResult(url, url, outcome, 0, "", "", message);
    }

    public boolean isOk() {
        return outcome == Outcome.OK;
    }

    /** Web page we can parse. */
    public boolean isHtml() {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/html") || type.startsWith("application/xhtml+xml");
    }

    /** XML document such as a sitemap. */
    public boolean isXml() {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("application/xml") || type.startsWith("text/xml");
    }

    /** One-line description for logs, e.g. "HTTP_ERROR (HTTP 404)". */
    public String describe() {
        return errorMessage.isEmpty() ? outcome.toString() : outcome + " (" + errorMessage + ")";
    }

    /** Short form without the body, which can be very large. */
    @Override
    public String toString() {
        return "FetchResult[" + requestedUrl + " -> " + describe() + ", status=" + statusCode
                + ", type=" + contentType + ", bodyLength=" + body.length() + "]";
    }
}

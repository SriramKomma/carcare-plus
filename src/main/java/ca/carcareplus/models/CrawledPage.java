package ca.carcareplus.models;

import java.time.Instant;
import java.util.Locale;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * One page downloaded by the WebCrawler, exactly as received.
 * This is the hand-off from the crawler (Module 1) to the HTML parser (Module 2).
 *
 * A record is immutable: once created, a crawled page cannot be changed by mistake.
 *
 * @param url         the URL that was fetched (after normalization)
 * @param sourceName  which configured source it came from, e.g. "Fountain Tire"
 * @param depth       BFS depth: 0 for a seed URL, 1 for pages linked from a seed, ...
 * @param statusCode  HTTP status code, e.g. 200
 * @param contentType HTTP Content-Type header, e.g. "text/html; charset=UTF-8"
 * @param html        response body (empty string if there was none)
 * @param fetchedAt   when the page was downloaded
 */
public record CrawledPage(
        String url,
        String sourceName,
        int depth,
        int statusCode,
        String contentType,
        String html,
        Instant fetchedAt) {

    /** Compact constructor: validates and normalizes every field. */
    public CrawledPage {
        if (url == null || url.isBlank()) {
            throw new InvalidInputException("CrawledPage URL must not be blank.");
        }
        if (depth < 0) {
            throw new InvalidInputException("Crawl depth cannot be negative: " + depth);
        }
        if (statusCode < 100 || statusCode > 599) {
            throw new InvalidInputException("Invalid HTTP status code: " + statusCode);
        }
        url = url.trim();
        sourceName = (sourceName == null || sourceName.isBlank()) ? "unknown" : sourceName.trim();
        contentType = contentType == null ? "" : contentType.trim();
        html = html == null ? "" : html;
        fetchedAt = fetchedAt == null ? Instant.now() : fetchedAt;
    }

    /** True for 2xx status codes. */
    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    /** True if the server said the body is HTML (the only type we parse). */
    public boolean isHtml() {
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/html") || type.startsWith("application/xhtml+xml");
    }

    /** Short description; the HTML body is left out because it can be very large. */
    @Override
    public String toString() {
        return "CrawledPage[url=" + url + ", source=" + sourceName + ", depth=" + depth
                + ", status=" + statusCode + ", htmlLength=" + html.length() + "]";
    }
}

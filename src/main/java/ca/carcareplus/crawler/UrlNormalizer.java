package ca.carcareplus.crawler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Turns the many ways a page can be linked into one canonical URL string,
 * so the crawler's visited-set recognises duplicates.
 *
 * Without this, "/brakes", "https://WWW.Example.ca:443/brakes#top" and
 * "https://www.example.ca/brakes?utm_source=mail" would be three different pages.
 *
 * Rules applied:
 *  1. Resolve relative links against the page they appeared on ("../a" -> absolute URL)
 *  2. Keep only http and https (mailto:, tel:, javascript:, data:, ftp: -> null)
 *  3. Lower-case the scheme and host (paths are case-sensitive, so they are kept)
 *  4. Remove default ports (:80 for http, :443 for https)
 *  5. Remove "#fragment" (it only scrolls within the same page)
 *  6. Resolve "." and ".." path segments; an empty path becomes "/"
 *  7. Remove tracking parameters (utm_*) but keep all other query parameters
 *
 * Every method returns null (or false) for invalid input instead of throwing,
 * because malformed links are normal on real websites and must not stop a crawl.
 */
public final class UrlNormalizer {

    private UrlNormalizer() {
    }

    /** Normalizes an absolute URL. Returns null if it is not a valid http(s) URL. */
    public static String normalize(String url) {
        return normalize(null, url);
    }

    /**
     * Resolves href against baseUrl (the page it was found on) and normalizes it.
     *
     * @param baseUrl absolute URL of the current page; may be null if href is absolute
     * @param href    link exactly as written in the HTML
     * @return canonical absolute URL, or null if the link is unusable
     */
    public static String normalize(String baseUrl, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        // Spaces are illegal in URLs but common in hand-written links
        String candidate = href.trim().replace(" ", "%20");
        if (candidate.startsWith("#")) {
            return null; // link to a spot on the same page
        }
        try {
            URI uri = new URI(candidate);
            if (uri.getScheme() == null) {
                // Relative link: needs a valid base. The base is normalized first so it
                // always has a path; java.net.URI resolves "b" against "http://a.ca"
                // (empty path) incorrectly as "http://a.cab".
                String base = normalize(null, baseUrl);
                if (base == null) {
                    return null;
                }
                if (candidate.startsWith("?")) {
                    // Query-only link: same page, new query (RFC 3986, as browsers do).
                    // URI.resolve follows the older RFC 2396 and would drop the last path segment.
                    int oldQuery = base.indexOf('?');
                    String samePage = oldQuery < 0 ? base : base.substring(0, oldQuery);
                    return canonical(new URI(samePage + candidate));
                }
                uri = new URI(base).resolve(uri);
            }
            return canonical(uri);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String canonical(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || uri.isOpaque()) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }

        URI clean = uri.normalize(); // resolves "." and ".." segments
        String host = clean.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);

        int port = clean.getPort();
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            port = -1;
        }

        String path = clean.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        // ".." above the root is meaningless: "/../a" -> "/a"
        while (path.startsWith("/../")) {
            path = path.substring(3);
        }
        if (path.equals("/..")) {
            path = "/";
        }

        String query = removeTrackingParameters(clean.getRawQuery());

        StringBuilder result = new StringBuilder();
        result.append(scheme).append("://").append(host);
        if (port != -1) {
            result.append(':').append(port);
        }
        result.append(path);
        if (!query.isEmpty()) {
            result.append('?').append(query);
        }
        return result.toString(); // the fragment is deliberately not appended
    }

    /** "utm_source=x&id=5&utm_medium=y" -> "id=5". Returns "" when nothing is left. */
    static String removeTrackingParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        StringJoiner kept = new StringJoiner("&");
        for (String parameter : rawQuery.split("&")) {
            if (!parameter.isEmpty() && !parameter.toLowerCase(Locale.ROOT).startsWith("utm_")) {
                kept.add(parameter);
            }
        }
        return kept.toString();
    }

    /** Lower-case host name of a URL, or null if the URL is invalid. */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * True if the URL's host is the domain or one of its sub-domains.
     * A leading "www." is ignored on both sides, so "www.example.ca" is in "example.ca".
     * "badexample.ca" and "example.ca.evil.com" are NOT in "example.ca".
     */
    public static boolean isInDomain(String url, String domain) {
        String host = hostOf(url);
        if (host == null || domain == null || domain.isBlank()) {
            return false;
        }
        String bareHost = stripWww(host);
        String bareDomain = stripWww(domain.trim().toLowerCase(Locale.ROOT));
        return bareHost.equals(bareDomain) || bareHost.endsWith("." + bareDomain);
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** "https://www.example.ca:8443/a?b=1" -> "https://www.example.ca:8443"; null if invalid. */
    public static String originOf(String url) {
        String normalized = normalize(url);
        if (normalized == null) {
            return null;
        }
        return normalized.substring(0, pathStart(normalized));
    }

    /** "https://www.example.ca/stores?q=1" -> "/stores?q=1"; "/" if invalid. Used for robots.txt. */
    public static String pathAndQuery(String url) {
        String normalized = normalize(url);
        if (normalized == null) {
            return "/";
        }
        return normalized.substring(pathStart(normalized));
    }

    /** Index of the first "/" after "scheme://host[:port]". Normalized URLs always have one. */
    private static int pathStart(String normalizedUrl) {
        return normalizedUrl.indexOf('/', normalizedUrl.indexOf("://") + 3);
    }
}

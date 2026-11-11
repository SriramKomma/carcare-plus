package ca.carcareplus.crawler;

import java.util.EnumMap;
import java.util.Map;

import ca.carcareplus.crawler.FetchResult.Outcome;

/**
 * Counters describing one crawl, printed at the end and used in tests.
 *
 * An EnumMap stores one counter per Counter constant in a plain array indexed by the
 * enum's position, so get/increment are O(1) with no hashing.
 */
public class CrawlStats {

    public enum Counter {
        PAGES_SAVED("Pages saved"),
        FETCH_ATTEMPTS("Page requests"),
        LINKS_QUEUED("New links queued"),
        SITEMAPS_READ("Sitemaps read"),
        ROBOTS_BLOCKED("Skipped: blocked by robots.txt"),
        HTTP_ERRORS("Skipped: HTTP error (404, 403, ...)"),
        TIMEOUTS("Skipped: timeout"),
        NETWORK_ERRORS("Skipped: network error"),
        INVALID_URLS("Skipped: invalid URL"),
        TOO_LARGE("Skipped: too large"),
        NON_HTML("Skipped: not HTML"),
        OFF_DOMAIN_REDIRECTS("Skipped: redirected off-domain"),
        DUPLICATE_REDIRECTS("Skipped: redirected to a page already seen");

        private final String label;

        Counter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Map<Counter, Integer> counts = new EnumMap<>(Counter.class);
    private String stopReason = "";

    public CrawlStats() {
        for (Counter counter : Counter.values()) {
            counts.put(counter, 0);
        }
    }

    public int get(Counter counter) {
        return counts.get(counter);
    }

    void increment(Counter counter) {
        add(counter, 1);
    }

    void add(Counter counter, int amount) {
        counts.merge(counter, amount, Integer::sum);
    }

    /** Counts a failed fetch under the matching counter. */
    void recordFailure(Outcome outcome) {
        switch (outcome) {
            case HTTP_ERROR -> increment(Counter.HTTP_ERRORS);
            case TIMEOUT -> increment(Counter.TIMEOUTS);
            case NETWORK_ERROR -> increment(Counter.NETWORK_ERRORS);
            case INVALID_URL -> increment(Counter.INVALID_URLS);
            case TOO_LARGE -> increment(Counter.TOO_LARGE);
            case OK -> {
                // not a failure
            }
        }
    }

    /** Why the crawl ended, e.g. "Reached maxPages (60)". */
    public String getStopReason() {
        return stopReason;
    }

    void setStopReason(String stopReason) {
        this.stopReason = stopReason == null ? "" : stopReason;
    }

    /** Multi-line summary; counters that stayed at 0 are left out. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Crawl summary");
        if (!stopReason.isEmpty()) {
            text.append(" (").append(stopReason).append(')');
        }
        for (Counter counter : Counter.values()) {
            int value = counts.get(counter);
            if (value > 0 || counter == Counter.PAGES_SAVED) {
                text.append(System.lineSeparator())
                        .append("  ").append(counter.label()).append(": ").append(value);
            }
        }
        return text.toString();
    }
}

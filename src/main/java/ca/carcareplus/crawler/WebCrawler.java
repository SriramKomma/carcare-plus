package ca.carcareplus.crawler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import ca.carcareplus.crawler.CrawlStats.Counter;
import ca.carcareplus.crawler.FetchResult.Outcome;
import ca.carcareplus.exceptions.CrawlException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.CrawledPage;

/**
 * Breadth-first web crawler for one source website.
 *
 * <pre>
 * check robots.txt of every seed's site (stop with CrawlException if the site refuses)
 * put seeds in the frontier at depth 0
 * while the frontier is not empty and fewer than maxPages pages are saved:
 *     take the oldest URL
 *     skip it if robots.txt disallows it
 *     wait (politeness delay), then fetch it
 *     failure      -> count it and continue (stop after 3 blocks in a row: 403/429)
 *     sitemap XML  -> queue the URLs it lists
 *     HTML page    -> save it; if depth < maxDepth, queue its in-domain links at depth + 1
 * </pre>
 *
 * Complexity: O(V + E) queue and set operations for V fetched pages and E extracted links;
 * in practice time is dominated by V * (network latency + politeness delay).
 */
public class WebCrawler {

    /** Identifies the crawler to site owners and is matched against robots.txt groups. */
    public static final String USER_AGENT =
            "CarCarePlus-COMP8547-StudentCrawler/1.0 (University of Windsor course project)";

    /** This many 403/429 answers in a row means the site is blocking us: stop. */
    static final int MAX_CONSECUTIVE_BLOCKS = 3;

    private static final Logger LOG = Logger.getLogger(WebCrawler.class.getName());

    /** Pauses between requests; replaced in tests so they run instantly. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Result of reading robots.txt: the rules, plus a problem message if access is refused. */
    private record RobotsLookup(RobotsTxtParser rules, String problem) {
    }

    private final PageFetcher fetcher;
    private final Sleeper sleeper;
    // origin ("https://host[:port]") -> its robots.txt rules, fetched once per crawl
    private final Map<String, RobotsTxtParser> robotsByOrigin = new HashMap<>();

    public WebCrawler(PageFetcher fetcher) {
        this(fetcher, Thread::sleep);
    }

    WebCrawler(PageFetcher fetcher, Sleeper sleeper) {
        if (fetcher == null || sleeper == null) {
            throw new InvalidInputException("WebCrawler needs a fetcher and a sleeper.");
        }
        this.fetcher = fetcher;
        this.sleeper = sleeper;
    }

    /** Crawls one source and returns the pages in BFS order. */
    public CrawlResult crawl(CrawlConfig config) throws CrawlException {
        return crawl(config, page -> { });
    }

    /**
     * Crawls one source. Each saved page is also handed to onPage immediately, so pages are
     * stored as they arrive and are not lost if the crawl stops part-way.
     *
     * @throws CrawlException if a seed's site forbids or blocks robots.txt access
     */
    public CrawlResult crawl(CrawlConfig config, Consumer<CrawledPage> onPage) throws CrawlException {
        if (config == null) {
            throw new InvalidInputException("Crawl configuration is required.");
        }
        Consumer<CrawledPage> listener = onPage == null ? page -> { } : onPage;

        robotsByOrigin.clear();
        checkRobotsForSeeds(config);

        CrawlStats stats = new CrawlStats();
        List<CrawledPage> pages = new ArrayList<>();
        URLFrontier frontier = new URLFrontier();
        for (String seed : config.seedUrls()) {
            frontier.add(seed, 0);
        }
        int consecutiveBlocks = 0;
        LOG.info(() -> "Crawling " + config.sourceName() + " (max " + config.maxPages()
                + " pages, depth " + config.maxDepth() + ", delay " + config.delayMillis() + " ms)");

        while (!frontier.isEmpty() && pages.size() < config.maxPages()) {
            URLFrontier.Entry entry = frontier.poll();
            String url = entry.url();

            RobotsTxtParser robots = robotsFor(url);
            if (!robots.isAllowed(UrlNormalizer.pathAndQuery(url))) {
                stats.increment(Counter.ROBOTS_BLOCKED);
                LOG.fine(() -> "robots.txt disallows " + url);
                continue;
            }

            if (!politeWait(Math.max(config.delayMillis(), robots.crawlDelayMillis()))) {
                stats.setStopReason("Interrupted");
                break;
            }
            FetchResult result = fetcher.fetch(url);
            stats.increment(Counter.FETCH_ATTEMPTS);

            if (!result.isOk()) {
                stats.recordFailure(result.outcome());
                LOG.warning(() -> "Skipped " + url + ": " + result.describe());
                if (isBlockingStatus(result)) {
                    consecutiveBlocks++;
                    if (consecutiveBlocks >= MAX_CONSECUTIVE_BLOCKS) {
                        stats.setStopReason("Stopped: site answered HTTP " + result.statusCode() + " "
                                + MAX_CONSECUTIVE_BLOCKS + " times in a row (blocking automated access)");
                        LOG.warning(stats::getStopReason);
                        break;
                    }
                }
                continue;
            }
            consecutiveBlocks = 0;

            // After a redirect, the page that answered may be off-domain or already known
            String finalUrl = UrlNormalizer.normalize(result.finalUrl());
            if (finalUrl == null || !UrlNormalizer.isInDomain(finalUrl, config.allowedDomain())) {
                stats.increment(Counter.OFF_DOMAIN_REDIRECTS);
                continue;
            }
            if (!finalUrl.equals(url) && !frontier.markSeen(finalUrl)) {
                stats.increment(Counter.DUPLICATE_REDIRECTS);
                continue;
            }

            if (result.isXml()) {
                stats.increment(Counter.SITEMAPS_READ);
                if (entry.depth() < config.maxDepth()) {
                    stats.add(Counter.LINKS_QUEUED, enqueueLinks(
                            LinkExtractor.extractSitemapUrls(result.body()),
                            finalUrl, entry.depth() + 1, config, frontier));
                }
                continue;
            }
            if (!result.isHtml()) {
                stats.increment(Counter.NON_HTML);
                continue;
            }

            CrawledPage page = new CrawledPage(finalUrl, config.sourceName(), entry.depth(),
                    result.statusCode(), result.contentType(), result.body(), Instant.now());
            pages.add(page);
            stats.increment(Counter.PAGES_SAVED);
            listener.accept(page);
            int saved = pages.size();
            LOG.info(() -> "[" + saved + "/" + config.maxPages() + "] depth " + entry.depth() + "  " + finalUrl);

            if (entry.depth() < config.maxDepth()) {
                stats.add(Counter.LINKS_QUEUED, enqueueLinks(
                        LinkExtractor.extractLinks(result.body()),
                        finalUrl, entry.depth() + 1, config, frontier));
            }
        }

        if (stats.getStopReason().isEmpty()) {
            stats.setStopReason(pages.size() >= config.maxPages()
                    ? "Reached maxPages (" + config.maxPages() + ")"
                    : "No more links to follow");
        }
        LOG.info(stats::toString);
        return new CrawlResult(pages, stats);
    }

    /**
     * Normalizes links and queues the ones that are in-domain and inside the allowed paths.
     *
     * @return how many new URLs were queued
     */
    private static int enqueueLinks(List<String> hrefs, String baseUrl, int depth,
                                    CrawlConfig config, URLFrontier frontier) {
        int added = 0;
        for (String href : hrefs) {
            String link = UrlNormalizer.normalize(baseUrl, href);
            if (link == null
                    || !UrlNormalizer.isInDomain(link, config.allowedDomain())
                    || !config.isPathAllowed(link)) {
                continue;
            }
            if (frontier.add(link, depth)) {
                added++;
            }
        }
        return added;
    }

    /** Reads robots.txt for every seed's site before any page is fetched. */
    private void checkRobotsForSeeds(CrawlConfig config) throws CrawlException {
        for (String seed : config.seedUrls()) {
            String origin = UrlNormalizer.originOf(seed);
            if (robotsByOrigin.containsKey(origin)) {
                continue;
            }
            RobotsLookup lookup = fetchRobots(origin);
            if (lookup.problem() != null) {
                throw new CrawlException("Cannot crawl " + config.sourceName() + ": " + lookup.problem());
            }
            robotsByOrigin.put(origin, lookup.rules());
        }
    }

    /** Cached robots rules for the URL's site; sites whose robots.txt fails are fully disallowed. */
    private RobotsTxtParser robotsFor(String url) {
        String origin = UrlNormalizer.originOf(url);
        RobotsTxtParser cached = robotsByOrigin.get(origin);
        if (cached != null) {
            return cached;
        }
        RobotsLookup lookup = fetchRobots(origin);
        if (lookup.problem() != null) {
            LOG.warning(lookup.problem());
        }
        robotsByOrigin.put(origin, lookup.rules());
        return lookup.rules();
    }

    /**
     * Fetches and interprets robots.txt (RFC 9309, with a stricter rule for 401/403):
     *  - 200        -> parse the rules
     *  - 404, 410.. -> no robots.txt: everything allowed
     *  - 401, 403   -> the site refuses automated clients: do not crawl
     *  - 429, 5xx, timeout, network error -> unknown: assume nothing is allowed
     */
    private RobotsLookup fetchRobots(String origin) {
        FetchResult result = fetcher.fetch(origin + "/robots.txt");
        if (result.isOk()) {
            return new RobotsLookup(RobotsTxtParser.parse(result.body(), USER_AGENT), null);
        }
        int status = result.statusCode();
        if (result.outcome() == Outcome.HTTP_ERROR) {
            if (status == 401 || status == 403) {
                return new RobotsLookup(RobotsTxtParser.disallowAll(),
                        origin + " refuses automated access (robots.txt answered HTTP " + status + ").");
            }
            if (status >= 400 && status < 500 && status != 429) {
                return new RobotsLookup(RobotsTxtParser.allowAll(), null);
            }
        }
        return new RobotsLookup(RobotsTxtParser.disallowAll(),
                "robots.txt for " + origin + " could not be read (" + result.describe()
                        + "), so crawling is treated as not allowed.");
    }

    private static boolean isBlockingStatus(FetchResult result) {
        return result.outcome() == Outcome.HTTP_ERROR
                && (result.statusCode() == 403 || result.statusCode() == 429);
    }

    /** @return false if the thread was interrupted (the crawl should stop) */
    private boolean politeWait(long millis) {
        try {
            sleeper.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

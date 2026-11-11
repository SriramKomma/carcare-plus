package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ca.carcareplus.crawler.CrawlStats.Counter;
import ca.carcareplus.crawler.FetchResult.Outcome;
import ca.carcareplus.exceptions.CrawlException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.CrawledPage;

/**
 * Crawler behaviour tests. A FakePageFetcher stands in for the internet and the sleeper
 * only records delays, so the whole class runs in milliseconds.
 *
 * Test site (www.example.ca):
 *   /   -> /a, /b, https://other.com/x
 *   /a  -> /c, /          (link back to the seed)
 *   /b  -> /a             (duplicate link)
 *   /c  -> (no links)
 */
class WebCrawlerTest {

    private static final String SITE = "https://www.example.ca";
    private static final String SEED = SITE + "/";
    private static final String ROBOTS = SITE + "/robots.txt";

    private final FakePageFetcher fetcher = new FakePageFetcher();
    private final List<Long> sleeps = new ArrayList<>();
    private final WebCrawler crawler = new WebCrawler(fetcher, sleeps::add);

    private static String links(String... hrefs) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (String href : hrefs) {
            html.append("<a href=\"").append(href).append("\">link</a>");
        }
        return html.append("</body></html>").toString();
    }

    private void buildSmallSite() {
        fetcher.html(SEED, links("/a", "/b", "https://other.com/x"))
                .html(SITE + "/a", links("/c", "/"))
                .html(SITE + "/b", links("/a"))
                .html(SITE + "/c", links());
    }

    private static CrawlConfig config(int maxDepth, int maxPages) {
        return new CrawlConfig("Example", "example.ca", maxDepth, maxPages, 1000, List.of(), List.of(SEED));
    }

    private static List<String> urls(CrawlResult result) {
        List<String> urls = new ArrayList<>();
        for (CrawledPage page : result.pages()) {
            urls.add(page.url());
        }
        return urls;
    }

    @Test
    void visitsPagesBreadthFirst() throws CrawlException {
        buildSmallSite();
        CrawlResult result = crawler.crawl(config(3, 50));

        assertEquals(List.of(SEED, SITE + "/a", SITE + "/b", SITE + "/c"), urls(result));
        assertEquals(List.of(0, 1, 1, 2), result.pages().stream().map(CrawledPage::depth).toList());
        assertEquals(4, result.stats().get(Counter.PAGES_SAVED));
        assertEquals("No more links to follow", result.stats().getStopReason());
    }

    @Test
    void readsRobotsTxtFirst() throws CrawlException {
        buildSmallSite();
        crawler.crawl(config(3, 50));
        assertEquals(ROBOTS, fetcher.requested().get(0));
    }

    @Test
    void fetchesEachPageOnlyOnce() throws CrawlException {
        buildSmallSite();
        crawler.crawl(config(3, 50));
        List<String> pages = fetcher.requestedPages();
        assertEquals(pages.size(), pages.stream().distinct().count(), "duplicate fetch in " + pages);
    }

    @Test
    void neverLeavesTheAllowedDomain() throws CrawlException {
        buildSmallSite();
        crawler.crawl(config(3, 50));
        assertFalse(fetcher.requested().stream().anyMatch(url -> url.contains("other.com")));
    }

    @Test
    void respectsMaxDepth() throws CrawlException {
        buildSmallSite();
        assertEquals(List.of(SEED, SITE + "/a", SITE + "/b"), urls(crawler.crawl(config(1, 50))));
        assertEquals(List.of(SEED), urls(crawler.crawl(config(0, 50))));
    }

    @Test
    void respectsMaxPages() throws CrawlException {
        buildSmallSite();
        CrawlResult result = crawler.crawl(config(3, 2));
        assertEquals(2, result.pages().size());
        assertEquals("Reached maxPages (2)", result.stats().getStopReason());
    }

    @Test
    void waitsBeforeEveryPageRequest() throws CrawlException {
        buildSmallSite();
        CrawlResult result = crawler.crawl(config(3, 50));
        assertEquals(result.stats().get(Counter.FETCH_ATTEMPTS), sleeps.size());
        assertTrue(sleeps.stream().allMatch(ms -> ms == 1000));
    }

    @Test
    void usesLongerCrawlDelayFromRobotsTxt() throws CrawlException {
        buildSmallSite();
        fetcher.response(ROBOTS, FetchResult.ok(ROBOTS, ROBOTS, 200, "text/plain", "User-agent: *\nCrawl-delay: 5\n"));
        crawler.crawl(config(0, 50));
        assertEquals(List.of(5000L), sleeps);
    }

    @Test
    void skipsPathsDisallowedByRobotsTxt() throws CrawlException {
        buildSmallSite();
        fetcher.response(ROBOTS, FetchResult.ok(ROBOTS, ROBOTS, 200, "text/plain", "User-agent: *\nDisallow: /b\n"));
        CrawlResult result = crawler.crawl(config(3, 50));

        assertFalse(fetcher.requested().contains(SITE + "/b"));
        assertEquals(1, result.stats().get(Counter.ROBOTS_BLOCKED));
        assertEquals(List.of(SEED, SITE + "/a", SITE + "/c"), urls(result));
    }

    @Test
    void robotsTxtForbiddenStopsTheCrawl() {
        buildSmallSite();
        fetcher.status(ROBOTS, 403);
        CrawlException error = assertThrows(CrawlException.class, () -> crawler.crawl(config(3, 50)));
        assertTrue(error.getMessage().contains("refuses automated access"));
        assertTrue(fetcher.requestedPages().isEmpty(), "no page may be fetched");
    }

    @Test
    void unreadableRobotsTxtStopsTheCrawl() {
        buildSmallSite();
        fetcher.failure(ROBOTS, Outcome.TIMEOUT);
        assertThrows(CrawlException.class, () -> crawler.crawl(config(3, 50)));
        fetcher.status(ROBOTS, 503);
        assertThrows(CrawlException.class, () -> crawler.crawl(config(3, 50)));
    }

    @Test
    void continuesAfterBrokenPages() throws CrawlException {
        fetcher.html(SEED, links("/missing", "/slow", "/broken", "/good"))
                .status(SITE + "/missing", 404)
                .failure(SITE + "/slow", Outcome.TIMEOUT)
                .failure(SITE + "/broken", Outcome.NETWORK_ERROR)
                .html(SITE + "/good", links());
        CrawlResult result = crawler.crawl(config(1, 50));

        assertEquals(List.of(SEED, SITE + "/good"), urls(result));
        assertEquals(1, result.stats().get(Counter.HTTP_ERRORS));
        assertEquals(1, result.stats().get(Counter.TIMEOUTS));
        assertEquals(1, result.stats().get(Counter.NETWORK_ERRORS));
    }

    @Test
    void stopsAfterRepeatedBlocking() throws CrawlException {
        fetcher.html(SEED, links("/1", "/2", "/3", "/4"))
                .status(SITE + "/1", 403)
                .status(SITE + "/2", 429)
                .status(SITE + "/3", 403)
                .html(SITE + "/4", links());
        CrawlResult result = crawler.crawl(config(1, 50));

        assertFalse(fetcher.requested().contains(SITE + "/4"));
        assertTrue(result.stats().getStopReason().contains("3 times in a row"));
        assertEquals(1, result.pages().size());
    }

    @Test
    void successResetsTheBlockCounter() throws CrawlException {
        fetcher.html(SEED, links("/1", "/2", "/ok", "/3", "/4"))
                .status(SITE + "/1", 403)
                .status(SITE + "/2", 403)
                .html(SITE + "/ok", links())
                .status(SITE + "/3", 403)
                .html(SITE + "/4", links());
        CrawlResult result = crawler.crawl(config(1, 50));
        assertTrue(fetcher.requested().contains(SITE + "/4"));
        assertEquals(3, result.pages().size());
    }

    @Test
    void skipsNonHtmlResponses() throws CrawlException {
        fetcher.html(SEED, links("/guide.pdf"))
                .response(SITE + "/guide.pdf", FetchResult.ok(SITE + "/guide.pdf", SITE + "/guide.pdf",
                        200, "application/pdf", "%PDF"));
        CrawlResult result = crawler.crawl(config(1, 50));
        assertEquals(1, result.stats().get(Counter.NON_HTML));
        assertEquals(List.of(SEED), urls(result));
    }

    @Test
    void skipsRedirectsToOtherDomains() throws CrawlException {
        fetcher.html(SEED, links("/partner"))
                .response(SITE + "/partner", FetchResult.ok(SITE + "/partner", "https://evil.com/landing",
                        200, "text/html", links()));
        CrawlResult result = crawler.crawl(config(1, 50));
        assertEquals(1, result.stats().get(Counter.OFF_DOMAIN_REDIRECTS));
        assertEquals(List.of(SEED), urls(result));
    }

    @Test
    void skipsRedirectsToPagesAlreadySeen() throws CrawlException {
        fetcher.html(SEED, links("/old-home"))
                .response(SITE + "/old-home", FetchResult.ok(SITE + "/old-home", SEED, 200, "text/html", links()));
        CrawlResult result = crawler.crawl(config(1, 50));
        assertEquals(1, result.stats().get(Counter.DUPLICATE_REDIRECTS));
        assertEquals(List.of(SEED), urls(result));
    }

    @Test
    void storesTheFinalUrlAfterARedirect() throws CrawlException {
        fetcher.html(SEED, links("/brakes"))
                .response(SITE + "/brakes", FetchResult.ok(SITE + "/brakes", SITE + "/services/brakes",
                        200, "text/html", links()));
        assertEquals(List.of(SEED, SITE + "/services/brakes"), urls(crawler.crawl(config(1, 50))));
    }

    @Test
    void readsSitemapsWithoutStoringThem() throws CrawlException {
        String sitemap = SITE + "/sitemap.xml";
        fetcher.response(sitemap, FetchResult.ok(sitemap, sitemap, 200, "application/xml",
                        "<urlset><url><loc>" + SITE + "/x</loc></url><url><loc>" + SITE + "/y</loc></url></urlset>"))
                .html(SITE + "/x", links())
                .html(SITE + "/y", links());
        CrawlConfig config = new CrawlConfig("Example", "example.ca", 1, 50, 1000, List.of(), List.of(sitemap));
        CrawlResult result = crawler.crawl(config);

        assertEquals(List.of(SITE + "/x", SITE + "/y"), urls(result));
        assertEquals(1, result.stats().get(Counter.SITEMAPS_READ));
    }

    @Test
    void followsOnlyAllowedPathPrefixes() throws CrawlException {
        fetcher.html(SEED, links("/stores/windsor", "/about-us"))
                .html(SITE + "/stores/windsor", links())
                .html(SITE + "/about-us", links());
        CrawlConfig config = new CrawlConfig("Example", "example.ca", 1, 50, 1000, List.of("/stores"), List.of(SEED));
        assertEquals(List.of(SEED, SITE + "/stores/windsor"), urls(crawler.crawl(config)));
    }

    @Test
    void handsEveryPageToTheListener() throws CrawlException {
        buildSmallSite();
        List<CrawledPage> received = new ArrayList<>();
        CrawlResult result = crawler.crawl(config(3, 50), received::add);
        assertEquals(result.pages(), received);
    }

    @Test
    void pagesCarrySourceAndStatus() throws CrawlException {
        buildSmallSite();
        CrawledPage first = crawler.crawl(config(0, 50)).pages().get(0);
        assertEquals("Example", first.sourceName());
        assertEquals(200, first.statusCode());
        assertTrue(first.isHtml());
    }

    @Test
    void rejectsMissingConfigAndCollaborators() {
        assertThrows(InvalidInputException.class, () -> crawler.crawl(null));
        assertThrows(InvalidInputException.class, () -> new WebCrawler(null));
    }
}

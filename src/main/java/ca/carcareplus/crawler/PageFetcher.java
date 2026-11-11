package ca.carcareplus.crawler;

/**
 * Downloads one URL.
 *
 * WebCrawler depends on this interface, not on HTTP directly. The real program uses
 * HttpPageFetcher; tests use a fake that returns 404s, 403s and timeouts on demand,
 * so crawler tests are fast, repeatable and never touch the internet.
 */
@FunctionalInterface
public interface PageFetcher {

    /**
     * Fetches the URL. Must not throw for network problems: every failure is
     * reported through the returned FetchResult's outcome.
     */
    FetchResult fetch(String url);
}

package ca.carcareplus.crawler;

import java.util.List;

import ca.carcareplus.models.CrawledPage;

/**
 * Everything one crawl produced: the saved pages in BFS order and the statistics.
 */
public record CrawlResult(List<CrawledPage> pages, CrawlStats stats) {

    public CrawlResult {
        pages = pages == null ? List.of() : List.copyOf(pages);
        stats = stats == null ? new CrawlStats() : stats;
    }
}

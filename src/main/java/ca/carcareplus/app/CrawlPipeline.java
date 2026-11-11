package ca.carcareplus.app;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import ca.carcareplus.crawler.CrawlConfig;
import ca.carcareplus.crawler.CrawlResult;
import ca.carcareplus.crawler.WebCrawler;
import ca.carcareplus.exceptions.CrawlException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.storage.RawPageStore;

/**
 * The offline part of CarCare+: crawl a source and store what was downloaded.
 * Runs only when asked (never during a search).
 *
 * Phase 3: crawl -> save raw HTML.
 * Later phases add: parse -> extract patterns -> store documents -> rebuild index.
 */
public class CrawlPipeline {

    private static final Logger LOG = Logger.getLogger(CrawlPipeline.class.getName());

    private final WebCrawler crawler;
    private final RawPageStore rawPageStore;

    public CrawlPipeline(WebCrawler crawler, RawPageStore rawPageStore) {
        if (crawler == null || rawPageStore == null) {
            throw new InvalidInputException("CrawlPipeline needs a crawler and a raw page store.");
        }
        this.crawler = crawler;
        this.rawPageStore = rawPageStore;
    }

    /**
     * Crawls one source and saves each page as soon as it arrives.
     * A page that cannot be saved is logged and skipped; the crawl continues.
     */
    public CrawlResult crawlAndSave(CrawlConfig config) throws CrawlException {
        AtomicInteger failedSaves = new AtomicInteger();
        CrawlResult result = crawler.crawl(config, page -> {
            try {
                rawPageStore.save(page);
            } catch (StorageException e) {
                failedSaves.incrementAndGet();
                LOG.warning(e.getMessage());
            }
        });
        if (failedSaves.get() > 0) {
            LOG.warning(failedSaves.get() + " page(s) were downloaded but could not be saved.");
        }
        return result;
    }
}

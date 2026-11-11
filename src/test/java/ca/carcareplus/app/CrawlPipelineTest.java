package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.crawler.CrawlConfig;
import ca.carcareplus.crawler.CrawlResult;
import ca.carcareplus.crawler.FakePageFetcher;
import ca.carcareplus.crawler.WebCrawler;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.CrawledPage;
import ca.carcareplus.storage.RawPageStore;

/** Crawler + RawPageStore working together, with a fake website. */
class CrawlPipelineTest {

    @TempDir
    Path dir;

    private static final CrawlConfig CONFIG = new CrawlConfig(
            "Example Tire", "example.ca", 1, 10, 1000, List.of(), List.of("https://www.example.ca/"));

    private static FakePageFetcher site() {
        return new FakePageFetcher()
                .html("https://www.example.ca/", "<a href=\"/brakes\">Brakes</a>")
                .html("https://www.example.ca/brakes", "<h1>Brake repair</h1>");
    }

    @Test
    void savesEveryCrawledPage() throws Exception {
        RawPageStore store = new RawPageStore(dir);
        // Real WebCrawler, but the fake fetcher answers instantly; the 1 s politeness delay
        // still applies, so this test takes about 2 seconds.
        CrawlResult result = new CrawlPipeline(new WebCrawler(site()), store).crawlAndSave(CONFIG);

        List<CrawledPage> saved = store.loadSource("Example Tire");
        assertEquals(result.pages().size(), saved.size());
        assertEquals("https://www.example.ca/brakes", saved.get(1).url());
        assertEquals("<h1>Brake repair</h1>", saved.get(1).html());
    }

    @Test
    void storageFailureDoesNotStopTheCrawl() throws Exception {
        // A regular file where the source folder should be: every save fails
        Files.writeString(dir.resolve("example-tire"), "not a folder");
        RawPageStore store = new RawPageStore(dir);

        CrawlResult result = new CrawlPipeline(new WebCrawler(site()), store).crawlAndSave(CONFIG);
        assertEquals(2, result.pages().size());
    }

    @Test
    void rejectsMissingParts() {
        assertThrows(InvalidInputException.class, () -> new CrawlPipeline(null, new RawPageStore(dir)));
        assertThrows(InvalidInputException.class,
                () -> new CrawlPipeline(new WebCrawler(new FakePageFetcher()), null));
    }
}

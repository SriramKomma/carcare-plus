package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.CrawlException;
import ca.carcareplus.exceptions.InvalidInputException;

class CrawlConfigTest {

    private static final String VALID =
            " Example Tire | Example.ca | 2 | 50 | 3000 | /stores, /services | https://www.example.ca/stores, https://www.example.ca/services ";

    @Test
    void parsesAValidLine() {
        CrawlConfig config = CrawlConfig.fromLine(VALID);
        assertEquals("Example Tire", config.sourceName());
        assertEquals("example.ca", config.allowedDomain());
        assertEquals(2, config.maxDepth());
        assertEquals(50, config.maxPages());
        assertEquals(3000, config.delayMillis());
        assertEquals(List.of("/stores", "/services"), config.pathPrefixes());
        assertEquals(List.of("https://www.example.ca/stores", "https://www.example.ca/services"), config.seedUrls());
    }

    @Test
    void starMeansAnyPath() {
        CrawlConfig config = CrawlConfig.fromLine("X | example.ca | 1 | 5 | 2000 | * | https://example.ca/");
        assertTrue(config.pathPrefixes().isEmpty());
        assertTrue(config.isPathAllowed("https://example.ca/anything"));
    }

    @Test
    void pathPrefixesFilterLinks() {
        CrawlConfig config = CrawlConfig.fromLine(VALID);
        assertTrue(config.isPathAllowed("https://www.example.ca/stores/windsor-on"));
        assertFalse(config.isPathAllowed("https://www.example.ca/about-us"));
    }

    @Test
    void delayBelowMinimumIsRaised() {
        CrawlConfig config = CrawlConfig.fromLine("X | example.ca | 1 | 5 | 10 | * | https://example.ca/");
        assertEquals(CrawlConfig.MIN_DELAY_MILLIS, config.delayMillis());
    }

    @Test
    void withMaxPagesKeepsEverythingElse() {
        CrawlConfig small = CrawlConfig.fromLine(VALID).withMaxPages(3);
        assertEquals(3, small.maxPages());
        assertEquals("Example Tire", small.sourceName());
        assertEquals(2, small.seedUrls().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "X | example.ca | 1 | 5 | 2000 | *",                                          // too few fields
        "X | example.ca | one | 5 | 2000 | * | https://example.ca/",                  // depth not a number
        "X | example.ca | 9 | 5 | 2000 | * | https://example.ca/",                    // depth too large
        "X | example.ca | 1 | 0 | 2000 | * | https://example.ca/",                    // maxPages too small
        "X | example.ca | 1 | 5 | 2000 | * | https://other.ca/",                      // seed outside domain
        "X | example.ca | 1 | 5 | 2000 | * | not-a-url",                              // invalid seed
        "X | example.ca | 1 | 5 | 2000 | * | ",                                       // no seed
        "  | example.ca | 1 | 5 | 2000 | * | https://example.ca/",                    // no name
        "X | example.ca | 1 | 5 | 2000 | stores | https://example.ca/"                // prefix without '/'
    })
    void rejectsInvalidLines(String line) {
        assertThrows(InvalidInputException.class, () -> CrawlConfig.fromLine(line));
    }

    @Test
    void loadsFileSkippingCommentsAndBlankLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sources.txt");
        Files.writeString(file, """
                # comment

                A | a.ca | 1 | 5 | 2000 | * | https://a.ca/
                B | b.ca | 1 | 5 | 2000 | * | https://b.ca/
                """);
        List<CrawlConfig> configs = CrawlConfig.loadFile(file);
        assertEquals(2, configs.size());
        assertEquals("B", configs.get(1).sourceName());
    }

    @Test
    void badLineReportsItsLineNumber(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sources.txt");
        Files.writeString(file, "# header\nA | a.ca | x | 5 | 2000 | * | https://a.ca/\n");
        CrawlException error = assertThrows(CrawlException.class, () -> CrawlConfig.loadFile(file));
        assertTrue(error.getMessage().contains("line 2"), error.getMessage());
    }

    @Test
    void duplicateSourceNamesAreRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sources.txt");
        Files.writeString(file, "A | a.ca | 1 | 5 | 2000 | * | https://a.ca/\na | a.ca | 1 | 5 | 2000 | * | https://a.ca/x\n");
        assertThrows(CrawlException.class, () -> CrawlConfig.loadFile(file));
    }

    @Test
    void missingFileGivesCrawlException(@TempDir Path dir) {
        assertThrows(CrawlException.class, () -> CrawlConfig.loadFile(dir.resolve("nope.txt")));
    }

    @Test
    void projectSourcesFileIsValid() throws Exception {
        // Guards config/sources.txt itself: a typo there would break the real crawl
        List<CrawlConfig> configs = CrawlConfig.loadFile(Path.of("config", "sources.txt"));
        assertFalse(configs.isEmpty());
    }
}

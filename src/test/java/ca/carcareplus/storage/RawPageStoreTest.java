package ca.carcareplus.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.CrawledPage;

class RawPageStoreTest {

    @TempDir
    Path dir;

    private static CrawledPage page(String url, String html) {
        return new CrawledPage(url, "Example Tire", 1, 200, "text/html; charset=UTF-8", html,
                Instant.parse("2026-11-06T15:00:00Z"));
    }

    @Test
    void savesAndLoadsPages() throws Exception {
        RawPageStore store = new RawPageStore(dir);
        Path file = store.save(page("https://example.ca/a", "<p>Brakes</p>"));
        store.save(page("https://example.ca/b", "<p>Batteries</p>"));

        assertTrue(Files.exists(file));
        assertEquals(dir.resolve("example-tire"), file.getParent());

        List<CrawledPage> loaded = store.loadSource("Example Tire");
        assertEquals(2, loaded.size());
        CrawledPage first = loaded.get(0);
        assertEquals("https://example.ca/a", first.url());
        assertEquals("<p>Brakes</p>", first.html());
        assertEquals(1, first.depth());
        assertEquals(200, first.statusCode());
        assertEquals("text/html; charset=UTF-8", first.contentType());
        assertEquals(Instant.parse("2026-11-06T15:00:00Z"), first.fetchedAt());
    }

    @Test
    void savingSameUrlTwiceKeepsOneLatestCopy() throws Exception {
        RawPageStore store = new RawPageStore(dir);
        store.save(page("https://example.ca/a", "old"));
        store.save(page("https://example.ca/a", "new"));

        List<CrawledPage> loaded = store.loadSource("Example Tire");
        assertEquals(1, loaded.size());
        assertEquals("new", loaded.get(0).html());
    }

    @Test
    void unknownSourceLoadsAsEmpty() throws Exception {
        assertTrue(new RawPageStore(dir).loadSource("Never Crawled").isEmpty());
    }

    @Test
    void skipsDamagedIndexLinesAndMissingFiles() throws Exception {
        RawPageStore store = new RawPageStore(dir);
        Path html = store.save(page("https://example.ca/a", "kept"));
        Path deleted = store.save(page("https://example.ca/b", "deleted"));
        Files.delete(deleted);

        Path index = html.getParent().resolve(RawPageStore.INDEX_FILE);
        Files.writeString(index, String.join(System.lineSeparator(),
                "garbage line",
                "../../etc/passwd\thttps://x.ca\t0\t200\ttext/html\t2026-11-06T15:00:00Z",
                html.getFileName() + "\thttps://example.ca/c\tnot-a-number\t200\ttext/html\t2026-11-06T15:00:00Z",
                ""), StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<CrawledPage> loaded = store.loadSource("Example Tire");
        assertEquals(1, loaded.size());
        assertEquals("kept", loaded.get(0).html());
    }

    @Test
    void makesSafeFolderNames() {
        assertEquals("fountain-tire", RawPageStore.slug("Fountain Tire"));
        assertEquals("electrify-canada", RawPageStore.slug("  Electrify   Canada! "));
        assertEquals("unknown", RawPageStore.slug("!!!"));
        assertEquals("unknown", RawPageStore.slug(null));
    }

    @Test
    void fileNameDependsOnlyOnUrl() {
        String name = RawPageStore.fileNameFor("https://example.ca/a");
        assertEquals(name, RawPageStore.fileNameFor("https://example.ca/a"));
        assertTrue(name.matches("[0-9a-f]{16}\\.html"));
        assertNotEquals(name, RawPageStore.fileNameFor("https://example.ca/b"));
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(InvalidInputException.class, () -> new RawPageStore(null));
        assertThrows(InvalidInputException.class, () -> new RawPageStore(dir).save(null));
    }
}

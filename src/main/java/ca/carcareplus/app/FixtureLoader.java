package ca.carcareplus.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.CrawledPage;
import ca.carcareplus.storage.RawPageStore;

/**
 * Copies the hand-made pages in fixtures/html into the raw page store, as if
 * they had been crawled, so the whole pipeline can be tried before real crawling
 * is approved.
 *
 * They are stored under the source name "Test Fixtures" with URLs on the
 * ".invalid" domain, which is reserved and can never be a real website.
 * ParsePipeline marks every document from this source as a test fixture.
 */
public final class FixtureLoader {

    public static final String SOURCE_NAME = "Test Fixtures";
    static final String URL_PREFIX = "https://fixtures.carcareplus.invalid/";

    private FixtureLoader() {
        // static helper methods only
    }

    /**
     * @return number of fixture pages stored
     * @throws StorageException if the fixture folder cannot be read or a page cannot be saved
     */
    public static int load(Path fixtureDir, RawPageStore store) throws StorageException {
        if (fixtureDir == null || store == null) {
            throw new InvalidInputException("Fixture folder and raw page store are required.");
        }
        if (!Files.isDirectory(fixtureDir)) {
            throw new StorageException("Fixture folder " + fixtureDir + " does not exist.");
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fixtureDir, "*.html")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            throw new StorageException("Cannot list " + fixtureDir + ": " + e.getMessage(), e);
        }
        files.sort(null); // same order on every computer

        Instant now = Instant.now();
        for (Path file : files) {
            String html;
            try {
                html = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new StorageException("Cannot read fixture " + file + ": " + e.getMessage(), e);
            }
            String name = file.getFileName().toString().replaceFirst("\\.html$", "");
            store.save(new CrawledPage(URL_PREFIX + name, SOURCE_NAME, 0, 200, "text/html; charset=UTF-8", html, now));
        }
        return files.size();
    }
}

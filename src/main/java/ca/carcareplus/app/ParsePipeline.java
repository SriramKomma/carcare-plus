package ca.carcareplus.app;

import java.nio.file.Path;
import java.util.List;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.CrawledPage;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.storage.DocumentStorage;
import ca.carcareplus.storage.RawPageStore;

/**
 * Offline step 2: raw HTML pages -> parsed, de-duplicated, stored documents.
 *
 * Every run REBUILDS data/parsed-pages from the raw pages. The raw pages are the
 * source of truth and the parsed documents are derived from them, so re-crawling
 * a page and parsing again gives the new version (instead of keeping the old one
 * as "duplicate URL"). Parsing a few hundred pages takes well under a second.
 */
public class ParsePipeline {

    /** What happened during one parsing run. */
    public record ParseReport(int pagesRead, int documentsSaved, int duplicates, int skipped) {
        @Override
        public String toString() {
            return "Pages read: " + pagesRead + ", documents saved: " + documentsSaved
                    + ", duplicates skipped: " + duplicates + ", unusable pages skipped: " + skipped;
        }
    }

    private final RawPageStore rawPages;
    private final HTMLParser parser;
    private final Path parsedDir;

    public ParsePipeline(RawPageStore rawPages, HTMLParser parser, Path parsedDir) {
        if (rawPages == null || parser == null || parsedDir == null) {
            throw new InvalidInputException("ParsePipeline needs a raw page store, a parser and an output folder.");
        }
        this.rawPages = rawPages;
        this.parser = parser;
        this.parsedDir = parsedDir;
    }

    /**
     * Parses every saved page of the given sources and saves the documents.
     * Sources with no saved pages are simply skipped.
     *
     * @throws StorageException if raw pages cannot be read or documents cannot be saved
     */
    public ParseReport parseSources(List<String> sourceNames) throws StorageException {
        return parseSources(sourceNames, List.of());
    }

    /**
     * Same, and also stores documents that did not come from HTML pages
     * (OpenStreetMap shops from OsmImporter). They count as "pages read" too.
     */
    public ParseReport parseSources(List<String> sourceNames, List<ServiceDocument> extraDocuments)
            throws StorageException {
        if (sourceNames == null || extraDocuments == null) {
            throw new InvalidInputException("Source names and extra documents are required.");
        }
        DocumentStorage storage = new DocumentStorage(parsedDir); // empty: full rebuild
        int read = 0;
        int duplicates = 0;
        int skipped = 0;
        for (String source : sourceNames) {
            for (CrawledPage page : rawPages.loadSource(source)) {
                read++;
                if (!page.isSuccessful() || !page.isHtml()) {
                    skipped++;
                    continue;
                }
                ServiceDocument doc = parser.parse(page);
                doc.setTestFixture(FixtureLoader.SOURCE_NAME.equals(page.sourceName()));
                if (storage.addIfNew(doc) != DocumentStorage.AddResult.ADDED) {
                    duplicates++;
                }
            }
        }
        for (ServiceDocument doc : extraDocuments) {
            read++;
            if (storage.addIfNew(doc) != DocumentStorage.AddResult.ADDED) {
                duplicates++;
            }
        }
        storage.saveAll();
        return new ParseReport(read, storage.size(), duplicates, skipped);
    }
}

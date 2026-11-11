package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.CrawledPage;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.search.SearchOutcome;
import ca.carcareplus.storage.RawPageStore;

/**
 * Whole offline + online pipeline on the invented pages in fixtures/html:
 * fixtures -> raw page store -> parser + regex + categorizer -> document storage
 * -> index + vocabulary -> search, completion, spell check, frequency.
 */
class EndToEndTest {

    private static final Path FIXTURES = Path.of("fixtures", "html");

    @TempDir
    Path dir;

    private RawPageStore rawPages;
    private Path parsedDir;
    private Path historyFile;
    private ParsePipeline pipeline;

    @BeforeEach
    void setUp() throws CarCareException {
        rawPages = new RawPageStore(dir.resolve("raw-pages"));
        parsedDir = dir.resolve("parsed-pages");
        historyFile = dir.resolve("search-history.txt");
        HTMLParser parser = new HTMLParser(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
        pipeline = new ParsePipeline(rawPages, parser, parsedDir);
    }

    private SearchSystem loadFixturesAndBuild() throws CarCareException {
        FixtureLoader.load(FIXTURES, rawPages);
        pipeline.parseSources(List.of(FixtureLoader.SOURCE_NAME));
        return SearchSystem.load(parsedDir, historyFile);
    }

    private static String path(SearchResult result) {
        return result.document().getUrl().replace("https://fixtures.carcareplus.invalid/", "");
    }

    // ------------------------------------------------------------ offline

    @Test
    void fixturesAreLoadedParsedAndStored() throws Exception {
        int loaded = FixtureLoader.load(FIXTURES, rawPages);
        ParsePipeline.ParseReport report = pipeline.parseSources(List.of(FixtureLoader.SOURCE_NAME, "Unknown Source"));

        assertEquals(6, loaded);
        assertEquals(new ParsePipeline.ParseReport(6, 6, 0, 0), report);
        assertTrue(Files.exists(parsedDir.resolve("documents.json")));
        assertEquals(6, Files.list(parsedDir.resolve("texts")).count());
    }

    @Test
    void everyFixtureDocumentIsFlaggedAsTestFixture() throws Exception {
        SearchSystem system = loadFixturesAndBuild();
        assertEquals(6, system.documents().size());
        assertTrue(system.documents().stream().allMatch(ServiceDocument::isTestFixture));
    }

    @Test
    void parsingAgainRebuildsInsteadOfPilingUp() throws Exception {
        FixtureLoader.load(FIXTURES, rawPages);
        FixtureLoader.load(FIXTURES, rawPages); // loaded twice: raw store keeps one per URL
        pipeline.parseSources(List.of(FixtureLoader.SOURCE_NAME));
        ParsePipeline.ParseReport second = pipeline.parseSources(List.of(FixtureLoader.SOURCE_NAME));

        assertEquals(6, second.documentsSaved());
        assertEquals(6, SearchSystem.load(parsedDir, historyFile).documents().size());
    }

    @Test
    void sameContentUnderTwoUrlsIsStoredOnce() throws Exception {
        String html = Files.readString(FIXTURES.resolve("brake-shop-calgary.html"));
        Instant now = Instant.now();
        rawPages.save(new CrawledPage("https://www.example.ca/brakes", "Example", 0, 200, "text/html", html, now));
        rawPages.save(new CrawledPage("https://www.example.ca/brakes/index.html", "Example", 0, 200, "text/html", html, now));
        rawPages.save(new CrawledPage("https://www.example.ca/logo.png", "Example", 0, 200, "image/png", "", now));

        ParsePipeline.ParseReport report = pipeline.parseSources(List.of("Example"));

        assertEquals(new ParsePipeline.ParseReport(3, 1, 1, 1), report);
    }

    // ------------------------------------------------------------- search

    @Test
    void brakeSearchForGasCarRanksCalgaryBrakeShopFirst() throws Exception {
        SearchOutcome outcome = loadFixturesAndBuild().engine()
                .search(new SearchRequest("brake pads", VehicleType.GAS, null, "Alberta", 10));

        SearchResult top = outcome.results().get(0);
        assertEquals("brake-shop-calgary", path(top));
        assertTrue(top.scoreBreakdown().contains("phrase +10"), top.scoreBreakdown());
        assertTrue(top.scoreBreakdown().contains("location +3"), top.scoreBreakdown());
        assertEquals(List.of("(403) 555-0177"), top.document().getPhones(), "footer phone not included");
        assertEquals("AB", top.document().getProvince());
    }

    @Test
    void evOnlyChargerPageIsHiddenFromGasDrivers() throws Exception {
        SearchSystem system = loadFixturesAndBuild();

        List<String> ev = system.engine().search(SearchRequest.of("charging station", VehicleType.EV))
                .results().stream().map(EndToEndTest::path).toList();
        List<String> gas = system.engine().search(SearchRequest.of("charging station", VehicleType.GAS))
                .results().stream().map(EndToEndTest::path).toList();

        assertEquals("charger-page", ev.get(0));
        assertFalse(gas.contains("charger-page"), gas.toString());
    }

    @Test
    void misspelledQueryGetsSuggestionAndSuggestionFindsResults() throws Exception {
        SearchSystem system = loadFixturesAndBuild();

        SearchOutcome typo = system.engine().search(SearchRequest.of("battrey", VehicleType.GAS));
        assertFalse(typo.hasResults());
        String suggestion = typo.suggestions().get("battrey").get(0);
        assertEquals("battery", suggestion);

        SearchOutcome retry = system.engine().search(SearchRequest.of(suggestion, VehicleType.GAS));
        assertTrue(retry.hasResults());
    }

    @Test
    void wordCompletionUsesWordsFromThePages() throws Exception {
        SearchSystem system = loadFixturesAndBuild();
        assertEquals(List.of("battery"), system.completer().complete("bat", 5));
        assertTrue(system.completer().complete("battery re", 5).contains("battery replacement"));
    }

    @Test
    void frequencyCountForOnePage() throws Exception {
        SearchSystem system = loadFixturesAndBuild();
        String url = "https://fixtures.carcareplus.invalid/brake-shop-calgary";
        int count = system.frequencyCounter().countInDocument("brake", url);
        assertEquals(count, system.frequencyCounter().countByScanning("brake", url));
        assertTrue(count >= 5, "brake appears in title, description, heading and body: " + count);
    }

    @Test
    void searchHistoryPersistsAcrossTwoRuns() throws Exception {
        SearchSystem first = loadFixturesAndBuild();
        first.engine().search(SearchRequest.of("battery", VehicleType.GAS));
        first.engine().search(SearchRequest.of("batteries", VehicleType.EV));
        first.saveHistory();

        SearchSystem second = SearchSystem.load(parsedDir, historyFile);
        assertEquals(2, second.searchFrequency().count("battery"));
    }

    @Test
    void emptyDataGivesEmptySystemNotACrash() throws StorageException {
        SearchSystem system = SearchSystem.load(dir.resolve("nothing-here"), historyFile);
        assertTrue(system.isEmpty());
        assertFalse(system.engine().search(SearchRequest.of("battery", VehicleType.GAS)).hasResults());
        assertEquals(List.of(), system.completer().complete("bat", 5));
    }

    @Test
    void formattedOutputLabelsFixturesAndShowsSuggestions() throws Exception {
        SearchSystem system = loadFixturesAndBuild();
        SearchRequest request = SearchRequest.of("battery battrey", VehicleType.GAS);
        String text = ResultFormatter.format(request, system.engine().search(request));

        assertTrue(text.contains("1. [TEST FIXTURE] "), text);
        assertTrue(text.contains("Why: "), text);
        assertTrue(text.contains("Did you mean: battrey -> battery"), text);
    }

    @Test
    void missingFixtureFolderIsAClearError() {
        StorageException e = assertThrows(StorageException.class,
                () -> FixtureLoader.load(dir.resolve("no-fixtures"), rawPages));
        assertTrue(e.getMessage().contains("does not exist"));
    }

    @Test
    void suggestionsMapIsEmptyWhenAllWordsKnown() throws Exception {
        assertEquals(Map.of(), loadFixturesAndBuild().engine()
                .search(SearchRequest.of("battery", VehicleType.GAS)).suggestions());
    }
}

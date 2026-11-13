package ca.carcareplus.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.app.ParsePipeline;
import ca.carcareplus.app.ResultFormatter;
import ca.carcareplus.app.SearchSystem;
import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.models.SearchRequest;
import ca.carcareplus.models.SearchResult;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.search.SearchOutcome;
import ca.carcareplus.storage.RawPageStore;

/**
 * A typed location keeps only places in that city: OSM test shops go through the
 * whole pipeline (parse -> storage -> index -> search).
 */
class CityFilterTest {

    @TempDir
    Path dir;

    private SearchSystem system;

    @BeforeEach
    void setUp() throws CarCareException {
        ServiceCategorizer categorizer = ServiceCategorizer.loadFile(Path.of("config", "categories.txt"));
        Path parsedDir = dir.resolve("parsed-pages");
        new ParsePipeline(new RawPageStore(dir.resolve("raw")), new HTMLParser(categorizer), parsedDir)
                .parseSources(List.of(), new OsmImporter(categorizer).load(OsmImporterTest.TEST_DATA));
        system = SearchSystem.load(parsedDir, dir.resolve("history.txt"));
    }

    private SearchOutcome search(String words, VehicleType vehicle, ServiceCategory category, String location) {
        return system.engine().search(new SearchRequest(words, vehicle, category, location, 10));
    }

    private static List<String> titles(SearchOutcome outcome) {
        return outcome.results().stream().map(r -> r.document().getTitle()).toList();
    }

    @Test
    void onlyShopsInTheTypedCityAreShown() {
        SearchOutcome outcome = search("car repair", VehicleType.GAS, null, "Windsor");

        assertEquals(List.of("Test Brake Garage", "Test General Auto").stream().sorted().toList(),
                titles(outcome).stream().sorted().toList());
        assertFalse(titles(outcome).contains("Test Street Garage"), "Toronto shop on Windsor Street is not in Windsor");
        assertTrue(outcome.outsideLocation() >= 2, "Toronto and the remote shop were filtered out");
        for (SearchResult result : outcome.results()) {
            assertEquals("Windsor", result.document().getCity());
        }
    }

    @Test
    void categoryShopsComeFirstAndGeneralShopsFollow() {
        SearchOutcome outcome = search("brake problem repair", VehicleType.GAS, ServiceCategory.BRAKES, "windsor");
        assertEquals(List.of("Test Brake Garage", "Test General Auto"), titles(outcome));
    }

    @Test
    void cityWithProvinceAndNearestPlaceCity() {
        assertEquals(List.of("Test Brake Garage", "Test General Auto").size(),
                search("repair", VehicleType.EV, null, "Windsor, ON").results().size());
        assertEquals(List.of(), titles(search("repair", VehicleType.EV, null, "Windsor, BC")));
        assertEquals(List.of("Test Tyre Shop"), titles(search("tyre", VehicleType.GAS, null, "Tecumseh")));
    }

    @Test
    void postalCodeAreaAndProvince() {
        assertEquals(List.of("Test Brake Garage"), titles(search("repair", VehicleType.GAS, null, "N9A 9Z9")));
        assertEquals(4, search("repair", VehicleType.GAS, null, "Ontario").results().size(),
                "every repair shop with a name and position");
    }

    @Test
    void chargersOnlyForEvDriversInTheirCity() {
        assertEquals(List.of("Test Charge Co EV charging station"),
                titles(search("ev charging station", VehicleType.EV, ServiceCategory.EV_CHARGING, "Toronto")));
        assertEquals(List.of(), titles(search("charging station", VehicleType.GAS, null, "Toronto")));
    }

    @Test
    void emptyCityExplainsWhereMatchesAre() {
        SearchRequest request = new SearchRequest("repair", VehicleType.GAS, null, "Ottawa", 10);
        SearchOutcome outcome = system.engine().search(request);

        assertFalse(outcome.hasResults());
        String text = ResultFormatter.format(request, outcome);
        assertTrue(text.contains("No results found in Ottawa. " + outcome.outsideLocation()
                + " matching page(s) are in other places"), text);
    }

    @Test
    void osmResultsCarryAttributionAndAddress() {
        SearchRequest request = new SearchRequest("brake", VehicleType.GAS, null, "Windsor", 10);
        String text = ResultFormatter.format(request, system.engine().search(request));
        assertTrue(text.contains("Address: 100 Ouellette Avenue, Windsor"), text);
        assertTrue(text.contains(OsmImporter.ATTRIBUTION), text);
    }
}

package ca.carcareplus.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.parser.ServiceCategorizer;

/** Conversion of (invented) Overpass answers into documents: src/test/resources/osm/. */
class OsmImporterTest {

    static final Path TEST_DATA = Path.of("src", "test", "resources", "osm");

    private OsmImporter importer;
    private Map<String, ServiceDocument> byUrl;

    @BeforeEach
    void setUp() throws CarCareException {
        importer = new OsmImporter(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
        byUrl = importer.load(TEST_DATA).stream()
                .collect(Collectors.toMap(ServiceDocument::getUrl, Function.identity()));
    }

    private ServiceDocument doc(String typeAndId) {
        return byUrl.get("https://www.openstreetmap.org/" + typeAndId);
    }

    @Test
    void namedElementsWithAPositionBecomeDocuments() {
        // 4004 has no name, relation 8008 has no position
        assertEquals(Set.of("node/1001", "way/2002", "node/3003", "node/5005", "node/6006", "node/7007"),
                byUrl.keySet().stream().map(u -> u.replace("https://www.openstreetmap.org/", ""))
                        .collect(Collectors.toSet()));
        assertTrue(byUrl.values().stream().allMatch(d -> d.getSourceName().equals(OsmImporter.SOURCE_NAME)));
        assertTrue(byUrl.values().stream().allMatch(d -> d.getProvince().equals("ON")));
    }

    @Test
    void repairShopFieldsComeFromTags() {
        ServiceDocument shop = doc("node/1001");
        assertEquals("Test Brake Garage", shop.getTitle());
        assertEquals("Car repair shop in Windsor, ON", shop.getDescription());
        assertEquals("100 Ouellette Avenue", shop.getAddress());
        assertEquals("Windsor", shop.getCity());
        assertEquals(List.of("(519) 555-0101", "(519) 555-0102"), shop.getPhones(), "both ';'-separated phones");
        assertEquals(List.of("N9A 1A1"), shop.getPostalCodes());
        assertTrue(shop.getBodyText().contains("Services: brakes"), shop.getBodyText());
        assertTrue(shop.getBodyText().contains("Opening hours: Mo-Fr 08:00-17:00"), shop.getBodyText());
        assertTrue(shop.hasCategory(ServiceCategory.BRAKES));
        assertEquals(Set.of(VehicleType.EV, VehicleType.GAS), shop.getVehicleTypes());
        assertEquals("", shop.getContentHash());
    }

    @Test
    void cityFallsBackToNearestPlace() {
        assertEquals("Tecumseh", doc("way/2002").getCity(), "way uses its center point");
        assertTrue(doc("way/2002").hasCategory(ServiceCategory.FLAT_TIRE), "shop=tyres");
        assertEquals("", doc("node/5005").getCity(), "more than 25 km from every place");
        assertEquals("Car repair shop in Ontario", doc("node/5005").getDescription());
    }

    @Test
    void unnamedChargerUsesOperatorAndListsConnectors() {
        ServiceDocument charger = doc("node/3003");
        assertEquals("Test Charge Co EV charging station", charger.getTitle());
        assertTrue(charger.hasCategory(ServiceCategory.EV_CHARGING));
        assertEquals(Set.of(VehicleType.EV), charger.getVehicleTypes());
        assertTrue(charger.getBodyText().contains("CCS"), charger.getBodyText());
        assertTrue(charger.getBodyText().contains("CHAdeMO"), charger.getBodyText());
        assertTrue(charger.getBodyText().contains("Charging points: 3"), charger.getBodyText());
    }

    @Test
    void generalShopWithoutServicesSuitsBothVehicles() {
        ServiceDocument shop = doc("node/6006");
        assertTrue(shop.appliesTo(VehicleType.EV));
        assertTrue(shop.appliesTo(VehicleType.GAS));
        assertTrue(shop.getBodyText().startsWith("Car repair shop (car repair)."), shop.getBodyText());
    }

    @Test
    void haversineDistances() {
        assertEquals(0.0, OsmImporter.distanceKm(42.3, -83.0, 42.3, -83.0), 1e-9);
        double windsorToronto = OsmImporter.distanceKm(42.3149, -83.0364, 43.6532, -79.3832);
        assertTrue(windsorToronto > 290 && windsorToronto < 340, "about 330 km in a straight line: " + windsorToronto);
        double oneDegreeLatitude = OsmImporter.distanceKm(42.0, -83.0, 43.0, -83.0);
        assertEquals(111.2, oneDegreeLatitude, 0.5);
    }

    @Test
    void nearestPlaceRespectsMaximumDistance() {
        List<OsmImporter.Place> places = List.of(
                new OsmImporter.Place("Windsor", 42.3149, -83.0364),
                new OsmImporter.Place("Tecumseh", 42.2970, -82.8870));
        assertEquals("Tecumseh", OsmImporter.nearestPlace(42.30, -82.90, places, 25).name());
        assertEquals("Windsor", OsmImporter.nearestPlace(42.32, -83.04, places, 25).name());
        assertNull(OsmImporter.nearestPlace(45.0, -80.0, places, 25));
        assertNull(OsmImporter.nearestPlace(42.3, -83.0, List.of(), 25));
    }

    @Test
    void missingDownloadGivesNoDocuments(@TempDir Path empty) throws StorageException {
        assertEquals(List.of(), importer.load(empty));
    }

    @Test
    void shopsWithoutPlacesFileStillLoadWithAddressCities(@TempDir Path dir) throws Exception {
        Files.copy(TEST_DATA.resolve(OverpassDownloader.SHOPS_FILE), dir.resolve(OverpassDownloader.SHOPS_FILE));
        List<ServiceDocument> docs = importer.load(dir);
        assertEquals(6, docs.size());
        assertTrue(docs.stream().anyMatch(d -> d.getTitle().equals("Test Tyre Shop") && d.getCity().isEmpty()));
    }

    @Test
    void corruptFileIsAStorageError(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(OverpassDownloader.SHOPS_FILE), "{not json");
        assertThrows(StorageException.class, () -> importer.load(dir));
        Files.writeString(dir.resolve(OverpassDownloader.SHOPS_FILE), "{\"remark\": \"runtime error\"}");
        assertThrows(StorageException.class, () -> importer.load(dir));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(InvalidInputException.class, () -> new OsmImporter(null));
        assertThrows(InvalidInputException.class, () -> importer.load(null));
        assertThrows(InvalidInputException.class, () -> new OverpassDownloader(" "));
    }

    @Test
    void queriesAskForOntarioShopsChargersAndPlaces() {
        assertTrue(OverpassDownloader.REPAIR_QUERY.contains("CA-ON"));
        assertTrue(OverpassDownloader.REPAIR_QUERY.contains("car_repair|tyres"));
        assertTrue(OverpassDownloader.CHARGER_QUERY.contains("charging_station"));
        assertTrue(OverpassDownloader.PLACES_QUERY.contains("city|town|village"));
    }
}

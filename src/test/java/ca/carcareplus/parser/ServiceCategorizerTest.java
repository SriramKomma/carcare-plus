package ca.carcareplus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;

class ServiceCategorizerTest {

    private static final ServiceCategorizer RULES = ServiceCategorizer.fromLines(List.of(
            "# test rules",
            "",
            "BATTERY     | battery, batteries, jump start",
            "BRAKES      | brake, brakes, brake pads",
            "ENGINE      | engine, oil change",
            "EV_CHARGING | charging station, kw",
            "FLAT_TIRE   | tire, tires"));

    private static ServiceDocument doc(String title, List<String> headings, String body) {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/page", "Example Shop");
        doc.setTitle(title);
        doc.setHeadings(headings);
        doc.setBodyText(body);
        return doc;
    }

    // ------------------------------------------------------------- normalizing

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = ';', value = {
        "Brake-Pads, Rotors!;  ' brake pads rotors '",
        "  JUMP   Start ;      ' jump start '",
        "350kW;                ' 350kw '",
        "'';                   ''",
        "'!!!';                ''"
    })
    void normalizeLowerCasesAndReplacesPunctuation(String input, String expected) {
        assertEquals(expected, ServiceCategorizer.normalize(input));
    }

    @Test
    void countsWholeWordsOnly() {
        String text = ServiceCategorizer.normalize("Brake, brakes, BRAKE pads. Brakeline brake");
        assertEquals(3, ServiceCategorizer.countWholeWord(text, " brake "));
        assertEquals(1, ServiceCategorizer.countWholeWord(text, " brakes "));
        assertEquals(1, ServiceCategorizer.countWholeWord(text, " brake pads "));
        assertEquals(0, ServiceCategorizer.countWholeWord(text, " engine "));
    }

    @Test
    void countsWordsThatFollowEachOtherDirectly() {
        // the space between two keywords belongs to both of them
        assertEquals(3, ServiceCategorizer.countWholeWord(" tire tire tire ", " tire "));
    }

    // ----------------------------------------------------------------- scoring

    @Test
    void headingHitsCountThreeAndBodyHitsCountOne() {
        ServiceDocument doc = doc("Battery service", List.of("Brakes"), "battery and brake pads");
        Map<ServiceCategory, Integer> scores = RULES.score(doc);

        assertEquals(3 + 1, scores.get(ServiceCategory.BATTERY));     // title + body
        assertEquals(3 + 1 + 1, scores.get(ServiceCategory.BRAKES));  // heading "brakes" + body "brake" + "brake pads"
        assertFalse(scores.containsKey(ServiceCategory.ENGINE));
    }

    @Test
    void oneMentionInBodyIsNotEnough() {
        ServiceDocument doc = doc("Store hours", List.of(), "We also sell a battery or two.");
        assertEquals(Set.of(), RULES.categorize(doc));
    }

    @Test
    void twoMentionsInBodyAreEnough() {
        ServiceDocument doc = doc("Store hours", List.of(), "Battery testing. New batteries in stock.");
        assertEquals(Set.of(ServiceCategory.BATTERY), RULES.categorize(doc));
    }

    @Test
    void oneHeadingMentionIsEnough() {
        ServiceDocument doc = doc("Store", List.of("Oil change"), "");
        assertEquals(Set.of(ServiceCategory.ENGINE), RULES.categorize(doc));
    }

    @Test
    void sourceNameIsIgnoredSoBrandWordsDoNotCount() {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/stores/1", "Example Tire");
        doc.setTitle("Windsor Store | Example Tire");
        doc.setHeadings(List.of("Example Tire Windsor"));
        doc.setBodyText("Welcome to Example Tire. Example Tire Example Tire.");

        assertEquals(Set.of(), RULES.categorize(doc));
    }

    @Test
    void emptyDocumentGetsNoCategories() {
        assertEquals(Set.of(), RULES.categorize(doc("", List.of(), "")));
    }

    // ------------------------------------------------------- vehicle types

    @Test
    void chargingPageIsTaggedEvOnly() {
        ServiceDocument doc = doc("Charging station Windsor", List.of(), "Up to 350 kW");
        RULES.tag(doc);
        assertEquals(Set.of(ServiceCategory.EV_CHARGING), doc.getCategories());
        assertEquals(Set.of(VehicleType.EV), doc.getVehicleTypes());
    }

    @Test
    void enginePageIsTaggedGasOnly() {
        ServiceDocument doc = doc("Engine repair", List.of(), "");
        RULES.tag(doc);
        assertEquals(Set.of(VehicleType.GAS), doc.getVehicleTypes());
    }

    @Test
    void mixedPageIsTaggedWithBothVehicleTypes() {
        ServiceDocument doc = doc("Brakes", List.of("Engine"), "");
        RULES.tag(doc);
        assertEquals(EnumSet.of(ServiceCategory.BRAKES, ServiceCategory.ENGINE), doc.getCategories());
        assertEquals(EnumSet.of(VehicleType.EV, VehicleType.GAS), doc.getVehicleTypes());
    }

    @Test
    void pageWithoutCategoryHasNoVehicleTypeAndAppliesToBoth() {
        ServiceDocument doc = doc("About us", List.of(), "Family owned since 1970.");
        RULES.tag(doc);
        assertTrue(doc.getVehicleTypes().isEmpty());
        assertTrue(doc.appliesTo(VehicleType.EV) && doc.appliesTo(VehicleType.GAS));
    }

    // ------------------------------------------------------------ rule loading

    @Test
    void rulesAreNormalizedAndDuplicateKeywordsDropped() {
        ServiceCategorizer rules = ServiceCategorizer.fromLines(List.of("battery | Battery, BATTERY ,jump-start"));
        assertEquals(List.of(" battery ", " jump start "), rules.rules().get(ServiceCategory.BATTERY));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
        "BATTERY battery;           expected 'CATEGORY | keyword",
        "TYRES | tire;              unknown category 'TYRES'",
        "BATTERY | , ,;             has no keywords",
        "BATTERY | a | b;           expected 'CATEGORY | keyword"
    })
    void badLinesAreRejectedWithLineNumber(String line, String messagePart) {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> ServiceCategorizer.fromLines(List.of("# header", line)));
        assertTrue(e.getMessage().startsWith("line 2: "), e.getMessage());
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    @Test
    void categoryListedTwiceIsRejected() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> ServiceCategorizer.fromLines(List.of("BATTERY | battery", "BATTERY | boost")));
        assertTrue(e.getMessage().contains("listed twice"));
    }

    @Test
    void missingFileGivesCheckedException(@TempDir Path dir) {
        CarCareException e = assertThrows(CarCareException.class,
                () -> ServiceCategorizer.loadFile(dir.resolve("nope.txt")));
        assertTrue(e.getMessage().contains("Cannot read categories file"));
    }

    @Test
    void badLineInFileNamesTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("categories.txt");
        Files.writeString(file, "BATTERY | battery\nWHEELS | rim\n");
        CarCareException e = assertThrows(CarCareException.class, () -> ServiceCategorizer.loadFile(file));
        assertTrue(e.getMessage().contains("categories.txt: line 2: unknown category 'WHEELS'"), e.getMessage());
    }

    @Test
    void realConfigFileLoadsAndCoversEveryCategoryExceptOther() throws CarCareException {
        ServiceCategorizer rules = ServiceCategorizer.loadFile(Path.of("config", "categories.txt"));
        Set<ServiceCategory> expected = EnumSet.allOf(ServiceCategory.class);
        expected.remove(ServiceCategory.OTHER);
        assertEquals(expected, rules.rules().keySet());
    }
}

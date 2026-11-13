package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.parser.HTMLParser;
import ca.carcareplus.parser.ServiceCategorizer;
import ca.carcareplus.storage.RawPageStore;
import ca.carcareplus.validation.DataValidator;

/**
 * Runs whole menu sessions from a script (one line = one thing the user types)
 * on the invented fixture pages, and checks what the menu printed.
 */
class ConsoleMenuTest {

    @TempDir
    Path dir;

    private SearchSystem system;
    private Path historyFile;

    @BeforeEach
    void setUp() throws CarCareException {
        RawPageStore rawPages = new RawPageStore(dir.resolve("raw-pages"));
        Path parsedDir = dir.resolve("parsed-pages");
        historyFile = dir.resolve("search-history.txt");
        HTMLParser parser = new HTMLParser(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
        FixtureLoader.load(Path.of("fixtures", "html"), rawPages);
        new ParsePipeline(rawPages, parser, parsedDir).parseSources(List.of(FixtureLoader.SOURCE_NAME));
        system = SearchSystem.load(parsedDir, historyFile);
    }

    /** Runs the menu with the given lines as keyboard input and returns everything printed. */
    private String run(String... lines) throws Exception {
        String input = lines.length == 0 ? "" : String.join("\n", lines) + "\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new ConsoleMenu(system, new BufferedReader(new StringReader(input)), out).run();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** Menu number of a category, e.g. BRAKES for GAS -> "5". */
    private static String option(VehicleType vehicle, ServiceCategory category) {
        return String.valueOf(ConsoleMenu.categoryOptions(vehicle).indexOf(category) + 1);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    // ------------------------------------------------------------- main menu

    @Test
    void exitSavesHistoryAndSaysGoodbye() throws Exception {
        String output = run("0");
        assertTrue(output.contains("Loaded 6 pages"), output);
        assertTrue(output.contains("===== CarCare+ main menu ====="), output);
        assertTrue(output.endsWith("Goodbye!" + System.lineSeparator()), output);
    }

    @Test
    void endOfInputInsideAPromptStillEndsCleanly() throws Exception {
        String output = run("1", "2"); // input stops while choosing a category
        assertTrue(output.contains("What do you need help with?"), output);
        assertTrue(output.contains("Search history saved. Goodbye!"), output);
    }

    @Test
    void unknownMenuChoiceIsExplained() throws Exception {
        String output = run("9", "hello", "0");
        assertEquals(2, count(output, "Please enter a number from 0 to 5."), output);
    }

    @Test
    void backOptionsReturnToMainMenu() throws Exception {
        String output = run("1", "0", "1", "1", "0", "0");
        assertEquals(3, count(output, "===== CarCare+ main menu ====="), output);
        assertFalse(output.contains("Search \""), "no search was run");
    }

    // ---------------------------------------------------------------- search

    @Test
    void gasBrakeSearchInAlbertaRanksCalgaryShopFirst() throws Exception {
        String output = run("1", "2", option(VehicleType.GAS, ServiceCategory.BRAKES), "brake pads", "Alberta", "0");

        assertTrue(output.contains("Search \"brake pads\" for Gas Vehicle"), output);
        assertTrue(output.contains("near Alberta"), output);
        int top = output.indexOf("1. [TEST FIXTURE] Brake Repair Calgary");
        assertTrue(top >= 0, output);
        assertTrue(output.indexOf("category +5", top) > top, "category bonus shown: " + output);
        assertTrue(output.indexOf("location +3", top) > top, "location bonus shown: " + output);
    }

    @Test
    void enterUsesTheCategoryNameAsTheQuery() throws Exception {
        String output = run("1", "2", option(VehicleType.GAS, ServiceCategory.BRAKES), "", "", "0");
        assertTrue(output.contains("Search \"brake problem repair\" for Gas Vehicle"), output);
    }

    @Test
    void otherCategoryNeedsTypedWords() throws Exception {
        String output = run("1", "1", option(VehicleType.EV, ServiceCategory.OTHER), "", "tow truck", "", "0");
        assertTrue(output.contains("Please type a few words"), output);
        assertTrue(output.contains("Search \"tow truck\""), output);
    }

    @Test
    void evDriversCanFindChargingStations() throws Exception {
        String output = run("1", "1", option(VehicleType.EV, ServiceCategory.EV_CHARGING), "", "", "0");
        assertTrue(output.contains("Find EV charging stations"), output);
        assertTrue(output.contains("Search \"ev charging station\" for Electric Vehicle"), output);
    }

    @Test
    void wordEndingWithQuestionMarkShowsCompletionsToPickFrom() throws Exception {
        String output = run("1", "2", option(VehicleType.GAS, ServiceCategory.BRAKES), "bra?", "1", "", "0");
        assertTrue(output.contains("  1. bra"), output);
        assertTrue(output.contains("Searching for: bra"), output);
        assertTrue(output.contains("Search \"bra"), output);
    }

    @Test
    void noCompletionsAsksAgain() throws Exception {
        String output = run("1", "2", option(VehicleType.GAS, ServiceCategory.BRAKES), "zzqx?", "brake", "", "0");
        assertTrue(output.contains("No completions for 'zzqx'"), output);
        assertTrue(output.contains("Search \"brake\""), output);
    }

    @Test
    void misspelledWordOffersCorrectedSearch() throws Exception {
        String output = run("1", "2", option(VehicleType.GAS, ServiceCategory.BRAKES), "breke", "", "y", "0");
        assertTrue(output.contains("Did you mean: breke -> brake"), output);
        assertTrue(output.contains("Search again for \"brake\"? (y/n): "), output);
        assertTrue(output.contains("Search \"brake\" for Gas Vehicle"), output);
        assertTrue(output.contains("Brake Repair Calgary"), output);
    }

    @Test
    void correctedSearchCanBeDeclined() throws Exception {
        String output = run("1", "2", option(VehicleType.GAS, ServiceCategory.BRAKES), "breke", "", "maybe", "n", "0");
        assertTrue(output.contains("Please answer y or n."), output);
        assertFalse(output.contains("Search \"brake\""), output);
    }

    @Test
    void badInputIsRejectedAndAskedAgain() throws Exception {
        String output = run("1", "x", "2", "99", option(VehicleType.GAS, ServiceCategory.BRAKES),
                "<script>", "brake", "N9B 3P", "n9b3p4", "0");
        assertTrue(output.contains("Please enter 1, 2 or 0."), output);
        assertTrue(output.contains("Please enter a number from 0 to"), output);
        assertTrue(output.contains("Search text may only contain"), output);
        assertTrue(output.contains("not a valid Canadian postal code"), output);
        assertTrue(output.contains("Search \"brake\" for Gas Vehicle (Petrol/Diesel) near N9B 3P4"), output);
    }

    @Test
    void searchesAreCountedAndSavedOnExit() throws Exception {
        String output = run("1", "2", option(VehicleType.GAS, ServiceCategory.BRAKES), "brake", "", "5", "0");
        assertTrue(output.contains("Most searched words:"), output);
        assertTrue(output.contains("  brake  1"), output);
        assertTrue(Files.readString(historyFile).contains("brake"), "history written to the file");
    }

    // ----------------------------------------------------- other menu options

    @Test
    void wordCompletionOption() throws Exception {
        String output = run("2", "bat", "2", "zzqx", "0");
        assertTrue(output.contains("Most frequent words starting with 'bat':"), output);
        assertTrue(output.contains("  battery"), output);
        assertTrue(output.contains("No completions for 'zzqx'."), output);
    }

    @Test
    void spellCheckOption() throws Exception {
        String output = run("3", "brake", "3", "breke", "3", "two words", "3", "zzzzzzzzqx", "0");
        assertTrue(output.contains("'brake' appears in the pages - spelled correctly."), output);
        assertTrue(output.contains("'breke' is not in the pages. Did you mean:"), output);
        assertTrue(output.contains("  brake  (edit distance 1"), output);
        assertTrue(output.contains("Error: Please type exactly one word."), output);
        assertTrue(output.contains("no word is within 2 edit(s)"), output);
    }

    @Test
    void wordFrequencyOption() throws Exception {
        List<ServiceDocument> pages = system.documents();
        int calgary = 0;
        while (!pages.get(calgary).getUrl().endsWith("brake-shop-calgary")) {
            calgary++;
        }
        String url = pages.get(calgary).getUrl();
        int expected = system.frequencyCounter().countInDocument("brake", url);
        String page = String.valueOf(calgary + 1);

        String output = run("4", page, "brake", "4", page, "", "4", "0", "0");

        assertTrue(expected > 0);
        assertTrue(output.contains("'brake' occurs " + expected + " time(s) on " + url), output);
        assertTrue(output.contains("Most frequent words on " + url + ":"), output);
    }

    // ------------------------------------------------------------ small helpers

    @Test
    void defaultQueriesAreCleanedCategoryNames() {
        assertEquals("vehicle breakdown towing repair", ConsoleMenu.defaultQueryFor(ServiceCategory.BREAKDOWN_TOWING));
        assertEquals("ev charging station", ConsoleMenu.defaultQueryFor(ServiceCategory.EV_CHARGING), "not a repair");
        assertEquals("ev maintenance motor high-voltage system repair",
                ConsoleMenu.defaultQueryFor(ServiceCategory.EV_MAINTENANCE));
        assertEquals("", ConsoleMenu.defaultQueryFor(ServiceCategory.OTHER));
        assertEquals("", ConsoleMenu.defaultQueryFor(null));
    }

    @ParameterizedTest
    @EnumSource(value = ServiceCategory.class, names = "OTHER", mode = EnumSource.Mode.EXCLUDE)
    void everyDefaultQueryPassesValidation(ServiceCategory category) {
        String query = ConsoleMenu.defaultQueryFor(category);
        assertEquals(query, DataValidator.validateQuery(query));
    }

    @Test
    void correctedQueryReplacesOnlyMisspelledTerms() {
        assertEquals("battery dead", ConsoleMenu.correctedQuery(List.of("battrey", "dead"),
                Map.of("battrey", List.of("battery", "batter"))));
        assertEquals("dead", ConsoleMenu.correctedQuery(List.of("dead"), Map.of()));
    }

    @Test
    void chargingStationsOnlyOfferedToEvDrivers() {
        List<ServiceCategory> ev = ConsoleMenu.categoryOptions(VehicleType.EV);
        List<ServiceCategory> gas = ConsoleMenu.categoryOptions(VehicleType.GAS);
        assertEquals(ServiceCategory.EV_CHARGING, ev.get(ev.size() - 1));
        assertFalse(gas.contains(ServiceCategory.EV_CHARGING));
        assertTrue(gas.contains(ServiceCategory.FUEL));
        assertFalse(ev.contains(ServiceCategory.FUEL));
    }

    @Test
    void constructorRejectsMissingParts() {
        BufferedReader in = new BufferedReader(new StringReader(""));
        assertThrows(InvalidInputException.class, () -> new ConsoleMenu(null, in, System.out));
        assertThrows(InvalidInputException.class, () -> new ConsoleMenu(system, null, System.out));
        assertThrows(InvalidInputException.class, () -> new ConsoleMenu(system, in, null));
    }
}

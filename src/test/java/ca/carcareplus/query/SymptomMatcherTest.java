package ca.carcareplus.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;

class SymptomMatcherTest {

    /** Small hand-written rules, so the scores below can be checked by hand. */
    private static final SymptomMatcher RULES = SymptomMatcher.fromLines(List.of(
            "# test rules",
            "",
            "BATTERY | won't start, dead battery, clicking key",
            "BRAKES  | squeal, brake pedal soft, grinding",
            "ENGINE  | check engine, engine light"));

    // ------------------------------------------------------------- matching

    @Test
    void matchesSymptomWordsInAnyOrder() {
        // "battery is dead" has both words of "dead battery", in the other order
        assertEquals(ServiceCategory.BATTERY, RULES.bestCategory("My battery is dead"));
    }

    @Test
    void apostrophesAndCaseDoNotMatter() {
        assertEquals(ServiceCategory.BATTERY, RULES.bestCategory("CAR WONT START"));
        assertEquals(ServiceCategory.BATTERY, RULES.bestCategory("car won’t start")); // curly apostrophe
    }

    @Test
    void scoreIsTheNumberOfWordsInTheMatchedSymptoms() {
        // won't start (2) + clicking key (2) = 4
        SymptomMatcher.Match best = RULES.match("It won't start, just clicking when I turn the key").get(0);
        assertEquals(ServiceCategory.BATTERY, best.category());
        assertEquals(4, best.score());
        assertEquals(List.of("won't start", "clicking key"), best.symptoms());
    }

    @Test
    void moreSpecificSymptomWinsAndOthersAreKeptAsAlternatives() {
        // BRAKES: squeal (1) + brake pedal soft (3) = 4; ENGINE: engine light (2)
        List<SymptomMatcher.Match> matches = RULES.match("squeal and soft brake pedal, also engine light on");
        assertEquals(2, matches.size());
        assertEquals(ServiceCategory.BRAKES, matches.get(0).category());
        assertEquals(4, matches.get(0).score());
        assertEquals(ServiceCategory.ENGINE, matches.get(1).category());
    }

    @Test
    void equalScoresKeepCategoryOrder() {
        // BATTERY "won't start" = 2, ENGINE "check engine" = 2 -> BATTERY comes first in the enum
        List<SymptomMatcher.Match> matches = RULES.match("check engine and won't start");
        assertEquals(ServiceCategory.BATTERY, matches.get(0).category());
        assertEquals(ServiceCategory.ENGINE, matches.get(1).category());
    }

    @Test
    void partOfASymptomIsNotEnough() {
        // only "pedal" of "brake pedal soft"
        assertTrue(RULES.match("the pedal feels odd").isEmpty());
    }

    @Test
    void wordsInsideOtherWordsDoNotMatch() {
        // "squealing" is a different token from "squeal" (whole words only)
        assertNull(RULES.bestCategory("squealing"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "the and of", "???", "xyz qwerty"})
    void nothingMatchesForEmptyOrUnknownText(String text) {
        assertTrue(RULES.match(text).isEmpty());
        assertNull(RULES.bestCategory(text));
    }

    @Test
    void veryLongTextIsHandled() {
        String text = "blah ".repeat(10_000) + "grinding";
        assertEquals(ServiceCategory.BRAKES, RULES.bestCategory(text));
    }

    // ------------------------------------------------------------- loading

    @Test
    void countsSymptoms() {
        assertEquals(8, RULES.symptomCount());
    }

    @Test
    void rejectsLineWithoutSeparator() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> SymptomMatcher.fromLines(List.of("# ok", "BATTERY won't start")));
        assertTrue(e.getMessage().startsWith("line 2:"), e.getMessage());
    }

    @Test
    void rejectsUnknownCategory() {
        assertThrows(InvalidInputException.class, () -> SymptomMatcher.fromLines(List.of("WINDOWS | foggy")));
    }

    @Test
    void rejectsCategoryListedTwice() {
        assertThrows(InvalidInputException.class,
                () -> SymptomMatcher.fromLines(List.of("BRAKES | squeal", "brakes | grinding")));
    }

    @Test
    void rejectsSymptomMadeOnlyOfStopWords() {
        // "the" would otherwise match almost every description
        assertThrows(InvalidInputException.class, () -> SymptomMatcher.fromLines(List.of("BRAKES | squeal, the")));
    }

    @Test
    void rejectsSymptomThatTheTokenizerMakesIdentical() {
        // "won't start" and "wont start" both become [wont, start]: counting both doubles the score
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> SymptomMatcher.fromLines(List.of("BATTERY | won't start, wont start")));
        assertTrue(e.getMessage().contains("same as"), e.getMessage());
    }

    @Test
    void rejectsCategoryWithNoSymptoms() {
        assertThrows(InvalidInputException.class, () -> SymptomMatcher.fromLines(List.of("BRAKES | , ,")));
    }

    @Test
    void rejectsNullLines() {
        assertThrows(InvalidInputException.class, () -> SymptomMatcher.fromLines(null));
    }

    @Test
    void loadFileReportsMissingFile(@TempDir Path dir) {
        assertThrows(CarCareException.class, () -> SymptomMatcher.loadFile(dir.resolve("missing.txt")));
        assertThrows(InvalidInputException.class, () -> SymptomMatcher.loadFile(null));
    }

    @Test
    void loadFileNamesTheFileOnABadLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("symptoms.txt");
        Files.writeString(file, "BRAKES | squeal\nNOPE | x\n");
        CarCareException e = assertThrows(CarCareException.class, () -> SymptomMatcher.loadFile(file));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    // ------------------------------------------------------------- the real config file

    /** Descriptions a driver might type, checked against config/symptoms.txt. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "my car won't start;                            BATTERY",
            "it just clicks when I turn the key, won't start; BATTERY",
            "brakes are squealing;                          BRAKES",
            "grinding noise when I stop;                    BRAKES",
            "the pedal goes to the floor;                   BRAKES",
            "I have a flat tire;                            FLAT_TIRE",
            "nail in my tyre;                               FLAT_TIRE",
            "engine is overheating, steam from the hood;    OVERHEATING_COOLING",
            "check engine light is on;                      ENGINE",
            "ran out of gas on the highway;                 FUEL",
            "my car broke down, need a tow;                 BREAKDOWN_TOWING",
            "the wipers streak;                             WIPERS",
            "headlights flicker at night, lights flickering; ELECTRICAL",
            "my EV won't charge at home;                    EV_MAINTENANCE",
            "I need to charge my car;                       EV_CHARGING"})
    void realSymptomsFileMapsCommonProblems(String description, ServiceCategory expected) throws Exception {
        SymptomMatcher real = SymptomMatcher.loadFile(Path.of("config", "symptoms.txt"));
        assertEquals(expected, real.bestCategory(description), description);
    }
}

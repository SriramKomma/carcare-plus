package ca.carcareplus.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.query.SpellChecker.Suggestion;

class SpellCheckerTest {

    private final SpellChecker checker = new SpellChecker(Map.of(
            "battery", 40, "batter", 2, "brake", 30, "bake", 1, "tire", 25, "car", 10, "bar", 3,
            "charger", 12, "350", 5));

    @Test
    void battreySuggestsBatteryFirst() {
        // battery: 2 (swap "re" -> "er" = 2 substitutions), frequency 40
        // batter:  2 (delete one "r", change "y" -> "r"),   frequency 2
        assertEquals(List.of("battery", "batter"), checker.suggest("battrey", 3));
    }

    @Test
    void closerWordsComeFirstThenMoreFrequent() {
        // "brak": brake 1 (insert e); bar 2 (delete r, k -> r); bake 2 (delete r, insert e)
        assertEquals(List.of(new Suggestion("brake", 1, 30)), checker.suggestions("brak", 1, 5));
        // within 2: brake first (distance 1), then bar (freq 3) before bake (freq 1)
        assertEquals(List.of("brake", "bar", "bake"),
                checker.suggestions("brak", 2, 5).stream().map(Suggestion::word).toList());
    }

    @Test
    void equalDistanceIsOrderedByFrequency() {
        // "batterx": battery 1 (substitute), batter 1 (delete x) -> battery has higher frequency
        assertEquals(List.of("battery", "batter"), checker.suggest("batterx", 5));
    }

    @Test
    void shortWordsAllowOnlyOneEdit() {
        assertEquals(1, SpellChecker.defaultMaxDistance("cra"));
        assertEquals(2, SpellChecker.defaultMaxDistance("battrey"));
        // "cxr" -> car (1), bar would need 2
        assertEquals(List.of("car"), checker.suggest("cxr", 5));
    }

    @Test
    void numbersAreNeverSuggested() {
        assertEquals(List.of(), checker.suggest("35o", 5));
    }

    @Test
    void knownWordGetsNoSuggestions() {
        assertTrue(checker.isKnown("Battery"));
        assertEquals(List.of(), checker.suggest("battery", 5));
    }

    @Test
    void wordFormsAreAppliedBeforeChecking() {
        assertTrue(checker.isKnown("Tyres"), "tyres -> tire");
        assertTrue(checker.isKnown("chargers"), "plural -> charger");
    }

    @Test
    void farAwayWordGetsNoSuggestions() {
        assertFalse(checker.isKnown("alternator"));
        assertEquals(List.of(), checker.suggest("alternator", 5));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "the", "!!"})
    void emptyOrStopWordInputGivesNothing(String input) {
        assertEquals(List.of(), checker.suggest(input, 5));
        assertFalse(checker.isKnown(input));
    }

    @Test
    void limitIsRespected() {
        assertEquals(1, checker.suggestions("bxke", 2, 1).size());
        assertEquals(List.of(), checker.suggestions("bxke", 2, 0));
    }

    @Test
    void nullVocabularyIsRejected() {
        assertThrows(InvalidInputException.class, () -> new SpellChecker(null));
    }

    @Test
    void vocabularyIsCopied() {
        assertEquals(9, checker.vocabularySize());
    }
}

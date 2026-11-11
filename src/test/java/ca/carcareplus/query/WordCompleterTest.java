package ca.carcareplus.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;

class WordCompleterTest {

    private final WordCompleter completer = new WordCompleter(Map.of(
            "battery", 40, "brake", 30, "replacement", 12, "repair", 20, "montreal", 3));

    @Test
    void bat_completesToBattery() {
        assertEquals(List.of("battery"), completer.complete("bat", 5));
    }

    @Test
    void completesOnlyTheLastWordAndKeepsTheRest() {
        assertEquals(List.of("battery repair", "battery replacement"), completer.complete("battery rep", 5));
    }

    @Test
    void cleansCaseAccentsAndExtraSpaces() {
        assertEquals(List.of("battery repair"), completer.complete("  BATTERY   REPA", 5));
        assertEquals(List.of("montreal"), completer.complete("Montré", 5));
    }

    @Test
    void limitIsRespected() {
        assertEquals(List.of("battery"), completer.complete("b", 1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "battery ", "!!!", "xyz"})
    void nothingToCompleteGivesEmptyList(String input) {
        assertEquals(List.of(), completer.complete(input, 5));
    }

    @Test
    void nullVocabularyIsRejected() {
        assertThrows(InvalidInputException.class, () -> new WordCompleter(null));
    }
}

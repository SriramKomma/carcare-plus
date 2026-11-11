package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TokenizerTest {

    @Test
    void lowerCasesAndSplitsOnPunctuation() {
        assertEquals(List.of("brake", "pads", "rotors"), Tokenizer.tokenize("Brake-Pads, ROTORS!"));
    }

    @Test
    void dropsStopWordsAndSingleLetters() {
        assertEquals(List.of("battery", "replacement", "car"),
                Tokenizer.tokenize("The battery replacement for a car & you"));
    }

    @Test
    void keepsNumbersIncludingSingleDigits() {
        assertEquals(List.of("level", "2", "charger", "350", "kw"), Tokenizer.tokenize("Level 2 charger, 350 kW"));
    }

    @Test
    void removesApostrophesInsteadOfSplitting() {
        assertEquals(List.of("drivers", "side"), Tokenizer.tokenize("driver's side"));
        assertEquals(List.of("drivers", "side"), Tokenizer.tokenize("driver’s side"));
    }

    @Test
    void removesAccents() {
        assertEquals(List.of("montreal", "quebec"), Tokenizer.tokenize("Montréal, Québec"));
    }

    @Test
    void appliesSpellingSynonyms() {
        assertEquals(List.of("tire", "tires", "service", "center"), Tokenizer.tokenize("Tyre tyres service centre"));
    }

    @Test
    void dropsVeryLongJunkTokens() {
        assertEquals(List.of("ok"), Tokenizer.tokenize("ok " + "x".repeat(Tokenizer.MAX_TERM_LENGTH + 1)));
        assertEquals(List.of("x".repeat(Tokenizer.MAX_TERM_LENGTH)), Tokenizer.tokenize("x".repeat(Tokenizer.MAX_TERM_LENGTH)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "!!! ---", "the and of"})
    void textWithoutTermsGivesEmptyList(String text) {
        assertEquals(List.of(), Tokenizer.tokenize(text));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"Tyres, tires", "  BATTERY , battery", "Québec, quebec"})
    void normalizeTermMatchesTokenize(String word, String expected) {
        assertEquals(expected, Tokenizer.normalizeTerm(word));
    }

    @ParameterizedTest
    @ValueSource(strings = {"the", "a", "", "two words", "!!"})
    void normalizeTermGivesNullWhenNotExactlyOneTerm(String word) {
        assertNull(Tokenizer.normalizeTerm(word));
    }
}

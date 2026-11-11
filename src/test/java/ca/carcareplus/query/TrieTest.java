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
import ca.carcareplus.query.Trie.WordFrequency;

class TrieTest {

    private final Trie trie = Trie.of(Map.of(
            "battery", 40, "batter", 2, "bat", 1, "brake", 30, "bracket", 3, "brakes", 5, "tire", 25));

    private static List<String> words(List<WordFrequency> found) {
        return found.stream().map(WordFrequency::word).toList();
    }

    @Test
    void completesExistingPrefixMostFrequentFirst() {
        assertEquals(List.of("battery", "batter", "bat"), words(trie.wordsWithPrefix("bat", 10)));
        assertEquals(List.of("brake", "brakes", "bracket"), words(trie.wordsWithPrefix("bra", 10)));
    }

    @Test
    void returnsFrequenciesToo() {
        assertEquals(List.of(new WordFrequency("battery", 40)), trie.wordsWithPrefix("batt", 1));
    }

    @Test
    void manySuggestionsAreLimitedToK() {
        assertEquals(List.of("battery", "brake"), words(trie.wordsWithPrefix("b", 2)));
    }

    @Test
    void fullWordAsPrefixIncludesItselfAndLongerWords() {
        assertEquals(List.of("brake", "brakes"), words(trie.wordsWithPrefix("brake", 10)));
    }

    @Test
    void missingPrefixGivesEmptyList() {
        assertEquals(List.of(), trie.wordsWithPrefix("xyz", 10));
        assertEquals(List.of(), trie.wordsWithPrefix("batteryx", 10));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankPrefixGivesEmptyList(String prefix) {
        assertEquals(List.of(), trie.wordsWithPrefix(prefix, 10));
    }

    @Test
    void zeroKGivesEmptyList() {
        assertEquals(List.of(), trie.wordsWithPrefix("b", 0));
    }

    @Test
    void prefixIsCaseInsensitive() {
        assertEquals(List.of("tire"), words(trie.wordsWithPrefix("TI", 10)));
    }

    @Test
    void equalFrequenciesAreAlphabetical() {
        Trie ties = Trie.of(Map.of("pads", 3, "pad", 3, "padding", 3));
        assertEquals(List.of("pad", "padding", "pads"), words(ties.wordsWithPrefix("pa", 10)));
    }

    @Test
    void containsOnlyWholeWords() {
        assertTrue(trie.contains("brake"));
        assertTrue(trie.contains("BRAKE"));
        assertFalse(trie.contains("brak"), "prefix only, not a word");
        assertFalse(trie.contains(null));
        assertEquals(0, trie.frequency("brak"));
    }

    @Test
    void insertingAgainAddsFrequency() {
        Trie t = new Trie();
        t.insert("wiper", 2);
        t.insert("Wiper", 3);
        assertEquals(5, t.frequency("wiper"));
        assertEquals(1, t.size());
    }

    @Test
    void sharedPrefixesAreStoredOnce() {
        Trie t = new Trie();
        t.insert("brake", 1);
        t.insert("brakes", 1);
        // root + b,r,a,k,e + s = 7 nodes, not 1 + 5 + 6 = 12
        assertEquals(7, t.nodeCount());
        assertEquals(2, t.size());
    }

    @Test
    void badInsertsAreRejected() {
        Trie t = new Trie();
        assertThrows(InvalidInputException.class, () -> t.insert(" ", 1));
        assertThrows(InvalidInputException.class, () -> t.insert(null, 1));
        assertThrows(InvalidInputException.class, () -> t.insert("x", 0));
    }
}

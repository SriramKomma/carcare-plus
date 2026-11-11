package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.search.SearchFrequency.TermCount;

class SearchFrequencyTest {

    @TempDir
    Path dir;

    private Path file;
    private SearchFrequency frequency;

    @BeforeEach
    void setUp() {
        file = dir.resolve("search-history.txt");
        frequency = new SearchFrequency(file);
    }

    @Test
    void recordingIncrementsCounts() {
        frequency.record("battery");
        frequency.record("Battery");
        frequency.record("brake");

        assertEquals(2, frequency.count("battery"));
        assertEquals(1, frequency.count("BRAKE"));
        assertEquals(0, frequency.count("wiper"));
    }

    @Test
    void recordAllNormalizesAndSkipsStopWords() {
        List<String> recorded = frequency.recordAll(List.of("Tyres", "the", "", "battery"));
        assertEquals(List.of("tire", "battery"), recorded);
        assertEquals(1, frequency.count("tyre"), "tyres, tyre, tires and tire are one term");
        assertEquals(1, frequency.count("tires"));
        assertEquals(2, frequency.distinctTerms());
        assertFalse(frequency.record("and"));
        assertFalse(frequency.record(null));
    }

    @Test
    void topSearchesMostFirstTiesAlphabetical() {
        frequency.recordAll(List.of("battery", "battery", "battery", "brake", "wiper", "wiper", "alternator"));
        assertEquals(List.of(new TermCount("battery", 3), new TermCount("wiper", 2), new TermCount("alternator", 1)),
                frequency.topSearches(3));
        assertEquals(List.of(), frequency.topSearches(0));
    }

    @Test
    void historyIsAlphabetical() {
        frequency.recordAll(List.of("wiper", "battery", "brake"));
        assertEquals(List.of("battery", "brake", "wiper"), List.copyOf(frequency.history().keySet()));
    }

    @Test
    void saveThenLoadRoundTrip() throws Exception {
        frequency.recordAll(List.of("battery", "battery", "brake"));
        frequency.save();

        SearchFrequency reloaded = new SearchFrequency(file);
        reloaded.load();
        assertEquals(2, reloaded.count("battery"));
        assertEquals(1, reloaded.count("brake"));
        assertEquals("battery\t2" + System.lineSeparator() + "brake\t1" + System.lineSeparator(),
                Files.readString(file));
        assertFalse(Files.exists(dir.resolve("search-history.txt.tmp")));
    }

    @Test
    void missingFileMeansNoHistory() throws StorageException {
        frequency.record("battery");
        frequency.load();
        assertEquals(0, frequency.distinctTerms(), "load replaces what was in memory");
    }

    @Test
    void damagedLinesAreSkippedGoodLinesKept() throws Exception {
        Files.writeString(file, String.join("\n",
                "battery\t5",
                "no tab here",
                "brake\tmany",
                "wiper\t-3",
                "the\t4",
                "too\tmany\tparts",
                "",
                "BRAKE\t2",
                "tyre\t1"));
        frequency.load();

        assertEquals(5, frequency.count("battery"));
        assertEquals(2, frequency.count("brake"));
        assertEquals(1, frequency.count("tire"));
        assertEquals(0, frequency.count("wiper"));
        assertEquals(3, frequency.distinctTerms());
    }

    @Test
    void savingCreatesMissingFolder() throws Exception {
        SearchFrequency nested = new SearchFrequency(dir.resolve("data").resolve("search-history.txt"));
        nested.record("battery");
        nested.save();
        assertTrue(Files.exists(dir.resolve("data/search-history.txt")));
    }

    @Test
    void nullFileIsRejected() {
        assertThrows(InvalidInputException.class, () -> new SearchFrequency(null));
    }
}

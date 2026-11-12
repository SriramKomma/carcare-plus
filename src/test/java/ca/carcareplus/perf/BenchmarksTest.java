package ca.carcareplus.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.search.Tokenizer;

/**
 * Runs the experiments in QUICK mode. Each experiment throws if its two methods
 * disagree, so a passing run also proves the baselines compute the same answers.
 */
class BenchmarksTest {

    @Test
    void quickRunProducesAllFiveTables() {
        String md = Benchmarks.run(Benchmarks.QUICK);
        for (String title : List.of("1. Inverted index build time", "2. Query: inverted index vs linear scan",
                "3. Top-10 results", "4. Spell check", "5. Word completion")) {
            assertTrue(md.contains("## " + title), "missing table: " + title);
        }
        assertTrue(md.contains("Synthetic data"), "results must say the data is synthetic");
    }

    @Test
    void syntheticDataIsRepeatableAndIndexedUnchanged() {
        List<String> words = SyntheticData.words(500, 1);
        assertEquals(words, SyntheticData.words(500, 1), "same seed, same words");
        assertNotEquals(words, SyntheticData.words(500, 2));
        assertEquals(500, words.stream().distinct().count());
        assertTrue(words.stream().allMatch(w -> w.equals(Tokenizer.normalizeTerm(w))));
    }

    @Test
    void syntheticDocumentsAreLabelled() {
        List<ServiceDocument> docs = SyntheticData.documents(3, SyntheticData.words(50, 1), 10, 1);
        assertEquals(List.of(0, 1, 2), docs.stream().map(ServiceDocument::getId).toList());
        assertTrue(docs.stream().allMatch(d -> d.isTestFixture() && d.getUrl().contains(".invalid/")));
    }

    @Test
    void typosDifferFromTheirWordByOneEdit() {
        List<String> words = SyntheticData.words(200, 3);
        for (String typo : SyntheticData.typos(words, 50, 3)) {
            assertTrue(words.stream().anyMatch(w -> ca.carcareplus.query.LevenshteinDistance.distance(w, typo) <= 1),
                    typo);
        }
    }

    @Test
    void frequenciesFallWithPosition() {
        Map<String, Integer> f = SyntheticData.frequencies(List.of("a1", "b1", "c1"));
        assertEquals(1000, f.get("a1"));
        assertEquals(500, f.get("b1"));
        assertEquals(333, f.get("c1"));
    }

    @Test
    void baselinesAgreeWithFastVersionsOnSmallInputs() {
        assertEquals(List.of(9, 8, 7), Benchmarks.fullSortTopK(List.of(7, 9, 1, 8), Comparator.reverseOrder())
                .subList(0, 3));
        assertEquals(java.util.Set.of(1), Benchmarks.linearScan(List.of(List.of("a"), List.of("b", "c")), "c"));
    }

    @Test
    void medianIsTheMiddleRun() {
        long median = Benchmarks.medianNanos(new Benchmarks.Config(0, 5, null, null, null, null, null, 0, 0, 0, 0),
                () -> 42);
        assertTrue(median >= 0);
    }
}

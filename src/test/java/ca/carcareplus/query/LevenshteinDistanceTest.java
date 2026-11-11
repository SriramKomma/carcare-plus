package ca.carcareplus.query;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LevenshteinDistanceTest {

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
        "battery, battery, 0",
        "'',      abc,     3",
        "abc,     '',      3",
        "'',      '',      0",
        "kitten,  sitting, 3",
        "battrey, battery, 2",   // swapped letters = 2 substitutions
        "batery,  battery, 1",   // one insert
        "brakes,  brake,   1",   // one delete
        "tire,    tyre,    1",   // one substitution
        "flaw,    lawn,    2"
    })
    void knownDistances(String a, String b, int expected) {
        assertEquals(expected, LevenshteinDistance.distance(a, b));
        assertEquals(expected, LevenshteinDistance.distance(b, a), "distance is symmetric");
    }

    @Test
    void nullIsTreatedAsEmpty() {
        assertEquals(3, LevenshteinDistance.distance(null, "abc"));
        assertEquals(0, LevenshteinDistance.distanceWithin(null, null, 2));
    }

    @Test
    void matrixForCatToCut() {
        int[][] expected = {
            {0, 1, 2, 3},   //    "" c u t
            {1, 0, 1, 2},   // c
            {2, 1, 1, 2},   // a
            {3, 2, 2, 1}};  // t
        int[][] dp = LevenshteinDistance.matrix("cat", "cut");
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], dp[i], "row " + i);
        }
    }

    @Test
    void distanceWithinGivesExactDistanceWhenSmallEnough() {
        assertEquals(2, LevenshteinDistance.distanceWithin("battrey", "battery", 2));
        assertEquals(3, LevenshteinDistance.distanceWithin("kitten", "sitting", 3));
    }

    @Test
    void distanceWithinStopsWithMaxPlusOneWhenTooFar() {
        assertEquals(3, LevenshteinDistance.distanceWithin("kitten", "sitting", 2));
        assertEquals(2, LevenshteinDistance.distanceWithin("ab", "abcdef", 1), "length filter");
        assertEquals(0, LevenshteinDistance.distanceWithin("a", "b", -1));
    }

    @Test
    void distanceWithinAgreesWithFullMatrixOnRandomWords() {
        Random random = new Random(11); // fixed seed: same words every run
        for (int round = 0; round < 2_000; round++) {
            String a = randomWord(random);
            String b = randomWord(random);
            int max = random.nextInt(4);
            int full = LevenshteinDistance.distance(a, b);
            int expected = full <= max ? full : max + 1;
            assertEquals(expected, LevenshteinDistance.distanceWithin(a, b, max), a + " / " + b + " max " + max);
        }
    }

    private static String randomWord(Random random) {
        StringBuilder word = new StringBuilder();
        int length = random.nextInt(8);
        for (int i = 0; i < length; i++) {
            word.append((char) ('a' + random.nextInt(4))); // small alphabet -> many near matches
        }
        return word.toString();
    }
}

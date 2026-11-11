package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;

class TopKTest {

    private static final Comparator<Integer> BIGGEST_FIRST = Comparator.reverseOrder();

    @Test
    void returnsKBestInOrder() {
        assertEquals(List.of(9, 8, 7), TopK.best(List.of(5, 9, 1, 7, 3, 8), 3, BIGGEST_FIRST));
    }

    @Test
    void fewerItemsThanKReturnsAllSorted() {
        assertEquals(List.of(3, 2, 1), TopK.best(List.of(2, 3, 1), 10, BIGGEST_FIRST));
    }

    @Test
    void zeroOrEmptyGiveEmptyList() {
        assertEquals(List.of(), TopK.best(List.of(1, 2), 0, BIGGEST_FIRST));
        assertEquals(List.of(), TopK.best(List.<Integer>of(), 5, BIGGEST_FIRST));
    }

    @Test
    void tieBreakInComparatorMakesResultDeterministic() {
        Comparator<String> longestThenAlphabetical =
                Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder());
        assertEquals(List.of("ccc", "aa", "bb"),
                TopK.best(List.of("bb", "a", "ccc", "aa"), 3, longestThenAlphabetical));
    }

    @Test
    void sameAnswerAsFullSortOnTenThousandRandomNumbers() {
        Random random = new Random(42); // fixed seed: same numbers every run
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            numbers.add(random.nextInt(1_000_000));
        }
        List<Integer> sorted = new ArrayList<>(numbers);
        sorted.sort(BIGGEST_FIRST);

        assertEquals(sorted.subList(0, 10), TopK.best(numbers, 10, BIGGEST_FIRST));
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(InvalidInputException.class, () -> TopK.best(List.of(1), -1, BIGGEST_FIRST));
        assertThrows(InvalidInputException.class, () -> TopK.best(null, 1, BIGGEST_FIRST));
        assertThrows(InvalidInputException.class, () -> TopK.best(List.of(1), 1, null));
    }
}

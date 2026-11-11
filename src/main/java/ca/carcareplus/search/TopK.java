package ca.carcareplus.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Picks the k best items without sorting everything.
 *
 * How: a min-heap (PriorityQueue) that never holds more than k items. Its top is
 * always the WORST of the current winners. Each new item is added; if the heap now
 * has k+1 items, the worst one is removed. At the end the heap holds the k best.
 *
 * Cost: O(n log k) time and O(k) extra space for n items, compared with
 * O(n log n) for sorting the whole list. With k = 10 and n = 10,000 that is about
 * log2(10) = 3.3 steps per item instead of log2(10,000) = 13.3.
 *
 * Used by PageRanker (best pages), FrequencyCounter (most frequent words) and
 * SearchFrequency (most searched terms).
 */
public final class TopK {

    private TopK() {
        // static helper methods only
    }

    /**
     * @param items  the candidates (not changed)
     * @param k      how many to keep; 0 gives an empty list
     * @param order  "better first" order, e.g. higher score first; must break ties
     *               (for example by name) if the output should be the same every run
     * @return at most k items, best first
     */
    public static <T> List<T> best(Iterable<T> items, int k, Comparator<T> order) {
        if (k < 0) {
            throw new InvalidInputException("k must be 0 or greater: " + k);
        }
        if (items == null || order == null) {
            throw new InvalidInputException("Items and order are required.");
        }
        List<T> result = new ArrayList<>();
        if (k == 0) {
            return result;
        }
        // order.reversed(): the heap's head is the worst item, ready to be dropped
        PriorityQueue<T> heap = new PriorityQueue<>(k + 1, order.reversed());
        for (T item : items) {
            heap.offer(item);
            if (heap.size() > k) {
                heap.poll();
            }
        }
        result.addAll(heap);
        result.sort(order); // only k items left, so this sort is O(k log k)
        return result;
    }
}

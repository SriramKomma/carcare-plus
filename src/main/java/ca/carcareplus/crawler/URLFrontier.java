package ca.carcareplus.crawler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * The list of URLs the crawler still has to visit (the "frontier" of the BFS).
 *
 * Two data structures work together:
 *  - a FIFO queue (ArrayDeque): first discovered = first visited, which makes the
 *    traversal breadth-first, so every depth-1 page is visited before any depth-2 page
 *  - a HashSet of every URL ever added: "have we seen this URL?" in O(1) average time,
 *    so no page is queued twice even when hundreds of pages link to it
 *
 * Callers must pass already-normalized URLs (see UrlNormalizer).
 */
public class URLFrontier {

    /** A URL waiting to be crawled and its BFS depth (0 = seed). */
    public record Entry(String url, int depth) {
    }

    private final Deque<Entry> queue = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();

    /**
     * Queues a URL if it has never been seen. O(1) average.
     *
     * @return true if it was added, false if it was null/blank or already seen
     * @throws InvalidInputException if depth is negative
     */
    public boolean add(String url, int depth) {
        if (depth < 0) {
            throw new InvalidInputException("Depth cannot be negative: " + depth);
        }
        if (url == null || url.isBlank()) {
            return false;
        }
        if (!seen.add(url)) {
            return false; // HashSet.add returns false when the URL was already present
        }
        queue.addLast(new Entry(url, depth));
        return true;
    }

    /**
     * Records a URL as seen without queueing it (used for the final URL after a redirect).
     *
     * @return true if it was new, false if it had been seen already
     */
    public boolean markSeen(String url) {
        return url != null && !url.isBlank() && seen.add(url);
    }

    /** Removes and returns the oldest entry, or null if the frontier is empty. O(1). */
    public Entry poll() {
        return queue.pollFirst();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Number of URLs still waiting. */
    public int size() {
        return queue.size();
    }

    /** Number of distinct URLs ever added or marked seen. */
    public int seenCount() {
        return seen.size();
    }

    public boolean hasSeen(String url) {
        return url != null && seen.contains(url);
    }
}

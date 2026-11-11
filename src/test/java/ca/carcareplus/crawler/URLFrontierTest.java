package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;

class URLFrontierTest {

    private final URLFrontier frontier = new URLFrontier();

    @Test
    void returnsUrlsInFirstInFirstOutOrder() {
        frontier.add("https://a.ca/1", 0);
        frontier.add("https://a.ca/2", 1);
        frontier.add("https://a.ca/3", 1);

        assertEquals(new URLFrontier.Entry("https://a.ca/1", 0), frontier.poll());
        assertEquals("https://a.ca/2", frontier.poll().url());
        assertEquals("https://a.ca/3", frontier.poll().url());
        assertTrue(frontier.isEmpty());
    }

    @Test
    void rejectsDuplicates() {
        assertTrue(frontier.add("https://a.ca/1", 0));
        assertFalse(frontier.add("https://a.ca/1", 2));
        assertEquals(1, frontier.size());
    }

    @Test
    void rememberedUrlsCannotBeQueuedAgainAfterPolling() {
        frontier.add("https://a.ca/1", 0);
        frontier.poll();
        assertFalse(frontier.add("https://a.ca/1", 1));
        assertTrue(frontier.hasSeen("https://a.ca/1"));
        assertEquals(1, frontier.seenCount());
    }

    @Test
    void markSeenBlocksLaterAdds() {
        assertTrue(frontier.markSeen("https://a.ca/redirected"));
        assertFalse(frontier.markSeen("https://a.ca/redirected"));
        assertFalse(frontier.add("https://a.ca/redirected", 1));
        assertTrue(frontier.isEmpty());
    }

    @Test
    void pollOnEmptyFrontierReturnsNull() {
        assertNull(frontier.poll());
    }

    @Test
    void ignoresNullAndBlankUrls() {
        assertFalse(frontier.add(null, 0));
        assertFalse(frontier.add("  ", 0));
        assertFalse(frontier.markSeen(null));
        assertFalse(frontier.hasSeen(null));
        assertEquals(0, frontier.seenCount());
    }

    @Test
    void rejectsNegativeDepth() {
        assertThrows(InvalidInputException.class, () -> frontier.add("https://a.ca", -1));
    }
}

package ca.carcareplus.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;

class CrawledPageTest {

    private static final String URL = "https://www.example.ca/services/brakes";

    private static CrawledPage page(int status, String contentType, String html) {
        return new CrawledPage(URL, "Example", 1, status, contentType, html, Instant.EPOCH);
    }

    @Test
    void storesValidPage() {
        CrawledPage page = page(200, "text/html; charset=UTF-8", "<html><title>Brakes</title></html>");
        assertEquals(URL, page.url());
        assertEquals("Example", page.sourceName());
        assertEquals(1, page.depth());
        assertTrue(page.isSuccessful());
        assertTrue(page.isHtml());
    }

    @Test
    void trimsUrlAndFillsDefaults() {
        CrawledPage page = new CrawledPage("  " + URL + "  ", null, 0, 200, null, null, null);
        assertEquals(URL, page.url());
        assertEquals("unknown", page.sourceName());
        assertEquals("", page.contentType());
        assertEquals("", page.html());
        assertNotNull(page.fetchedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankUrl(String url) {
        assertThrows(InvalidInputException.class,
                () -> new CrawledPage(url, "Example", 0, 200, "text/html", "", null));
    }

    @Test
    void rejectsNullUrl() {
        assertThrows(InvalidInputException.class,
                () -> new CrawledPage(null, "Example", 0, 200, "text/html", "", null));
    }

    @Test
    void rejectsNegativeDepth() {
        assertThrows(InvalidInputException.class,
                () -> new CrawledPage(URL, "Example", -1, 200, "text/html", "", null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99, 600, -404})
    void rejectsImpossibleStatusCodes(int status) {
        assertThrows(InvalidInputException.class, () -> page(status, "text/html", ""));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 403, 404, 500})
    void nonSuccessStatusesAreNotSuccessful(int status) {
        assertFalse(page(status, "text/html", "").isSuccessful());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/png", "application/json", ""})
    void nonHtmlContentTypesAreDetected(String contentType) {
        assertFalse(page(200, contentType, "").isHtml());
    }

    @Test
    void xhtmlCountsAsHtml() {
        assertTrue(page(200, "application/xhtml+xml", "").isHtml());
    }

    @Test
    void toStringDoesNotPrintTheWholeHtml() {
        String html = "<p>" + "x".repeat(5_000) + "</p>";
        String text = page(200, "text/html", html).toString();
        assertFalse(text.contains("xxxxxxxxxx"));
        assertTrue(text.contains("htmlLength=" + html.length()));
    }
}

package ca.carcareplus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.CrawledPage;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;

/**
 * Parses the hand-made fixture pages in fixtures/html.
 * They are invented test data, not crawled pages (see the comment in each file).
 */
class HTMLParserTest {

    private static final Instant FETCHED = Instant.parse("2026-11-11T12:00:00Z");
    private static HTMLParser parser;

    @BeforeAll
    static void loadRealCategoryRules() throws CarCareException {
        parser = new HTMLParser(ServiceCategorizer.loadFile(Path.of("config", "categories.txt")));
    }

    private static CrawledPage page(String url, String source, String html) {
        return new CrawledPage(url, source, 0, 200, "text/html; charset=UTF-8", html, FETCHED);
    }

    /** Fixture pages live in fixtures/html (shared with the demo "fixtures" command). */
    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("fixtures", "html", name), StandardCharsets.UTF_8);
    }

    // --------------------------------------------------------- store fixture

    @Test
    void parsesStorePageFixture() throws IOException {
        ServiceDocument doc = parser.parse(page(
                "https://www.example-tire.ca/stores/abbotsford", "Example Tire", fixture("store-page.html")));

        assertEquals("Abbotsford Store | Example Tire", doc.getTitle());
        assertEquals("Tire, battery and brake service in Abbotsford, BC.", doc.getDescription());
        assertEquals(List.of("Example Tire Abbotsford", "Contact", "Services", "Brake service"), doc.getHeadings());
        assertEquals("2026-11-11T12:00:00Z", doc.getCrawledAt());

        // regex results come from the main content only
        assertEquals(List.of("(604) 555-0142"), doc.getPhones());
        assertEquals(List.of("abbotsford@example-tire.ca"), doc.getEmails());
        assertEquals(List.of("V2S 1A1"), doc.getPostalCodes());
        assertEquals("BC", doc.getProvince());

        assertEquals(EnumSet.of(ServiceCategory.FLAT_TIRE, ServiceCategory.BATTERY, ServiceCategory.BRAKES),
                doc.getCategories());
        assertEquals(EnumSet.of(VehicleType.EV, VehicleType.GAS), doc.getVehicleTypes());
        assertEquals(64, doc.getContentHash().length());
    }

    @Test
    void storePageBodyHasNoScriptStyleMenuFooterOrHiddenText() throws IOException {
        String body = parser.parse(page("https://www.example-tire.ca/stores/abbotsford", "Example Tire",
                fixture("store-page.html"))).getBodyText();

        assertTrue(body.contains("battery testing and replacement"));
        assertTrue(body.contains("Example Tire Abbotsford"), "non-breaking space must become a normal space");
        for (String noise : List.of("tracking", "color: red", "All stores", "Head office", "Hidden promo", "Privacy")) {
            assertFalse(body.contains(noise), "body should not contain: " + noise);
        }
    }

    @Test
    void storePageLinksAreResolvedAgainstPageUrl() throws IOException {
        List<String> links = parser.parse(page("https://www.example-tire.ca/stores/abbotsford", "Example Tire",
                fixture("store-page.html"))).getLinks();

        assertTrue(links.contains("https://www.example-tire.ca/auto-services/batteries?store=abbotsford"), links.toString());
        assertTrue(links.contains("https://www.example-tire.ca/privacy"), "footer links are still links");
        assertEquals(1, links.stream().filter(l -> l.endsWith("/auto-services/brakes")).count(), "no duplicates");
        assertTrue(links.stream().allMatch(l -> l.startsWith("http")), "no mailto/tel/javascript links");
    }

    // ------------------------------------------------------- charger fixture

    @Test
    void parsesChargerPageWithTitleAndDescriptionFallbacks() throws IOException {
        ServiceDocument doc = parser.parse(page(
                "https://www.example-charge.ca/locations/windsor", "Example Charge", fixture("charger-page.html")));

        assertEquals("Windsor Station - 1 Sample Road", doc.getTitle(), "no <title>, empty og:title -> first h1");
        assertTrue(doc.getDescription().startsWith("DC fast charging station"), doc.getDescription());
        assertEquals(List.of("(800) 555-0199"), doc.getPhones());
        assertEquals(List.of("N9B 3P4"), doc.getPostalCodes());
        assertEquals("ON", doc.getProvince());
        assertEquals(Set.of(ServiceCategory.EV_CHARGING), doc.getCategories(),
                "one body mention of 'EV battery' must not add BATTERY");
        assertEquals(Set.of(VehicleType.EV), doc.getVehicleTypes());
    }

    // ----------------------------------------------------------- edge cases

    @Test
    void malformedHtmlIsRepairedNotRejected() throws IOException {
        ServiceDocument doc = parser.parse(page("https://www.example.ca/broken", "Example", fixture("malformed.html")));

        assertEquals("Broken page", doc.getTitle());
        assertTrue(doc.getHeadings().get(0).startsWith("Brake service"), doc.getHeadings().toString());
        assertTrue(doc.getBodyText().contains("We fix brakes fast and cheap"), doc.getBodyText());
        assertFalse(doc.getBodyText().contains("junk"), "unclosed <script> content must not leak into text");
        assertEquals(List.of("(519) 555-0123"), doc.getPhones());
    }

    @Test
    void emptyHtmlGivesEmptyDocumentWithUrlTitle() {
        ServiceDocument doc = parser.parse(page("https://www.example.ca/stores/windsor-east", "Example", ""));

        assertEquals("windsor east", doc.getTitle());
        assertEquals("", doc.getDescription());
        assertEquals("", doc.getBodyText());
        assertTrue(doc.getHeadings().isEmpty());
        assertTrue(doc.getLinks().isEmpty());
        assertTrue(doc.getPhones().isEmpty() && doc.getEmails().isEmpty() && doc.getPostalCodes().isEmpty());
        assertTrue(doc.getCategories().isEmpty());
        assertEquals("", doc.getContentHash(), "empty pages get no hash, so they are not all 'duplicates'");
    }

    @Test
    void pageWithoutContactDetailsHasEmptyLists() {
        ServiceDocument doc = parser.parse(page("https://www.example.ca/about", "Example",
                "<title>About</title><p>We have been fixing cars since 1970.</p>"));
        assertTrue(doc.getPhones().isEmpty());
        assertTrue(doc.getEmails().isEmpty());
        assertTrue(doc.getPostalCodes().isEmpty());
        assertEquals("", doc.getProvince());
    }

    @Test
    void nullPageIsRejected() {
        assertThrows(InvalidInputException.class, () -> parser.parse(null));
    }

    @Test
    void nullCategorizerIsRejected() {
        assertThrows(InvalidInputException.class, () -> new HTMLParser(null));
    }

    @Test
    void largePageIsParsedQuickly() {
        String html = "<title>Big</title>" + "<p>Brake service in Windsor, call 519-555-0123.</p>".repeat(20_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ServiceDocument doc = parser.parse(page("https://www.example.ca/big", "Example", html));
            assertEquals(1, doc.getPhones().size());
        });
    }

    // ---------------------------------------------------------------- helpers

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "https://x.ca/stores/abbotsford-bc,         abbotsford bc",
        "https://x.ca/stores/abbotsford-bc/,        abbotsford bc",
        "https://x.ca/auto-services/brake_pads.html, brake pads",
        "https://x.ca/stores/windsor?tab=hours#map, windsor",
        "https://x.ca/,                             x.ca",
        "https://x.ca,                              x.ca"
    })
    void titleFromUrlUsesLastPathPart(String url, String expected) {
        assertEquals(expected, HTMLParser.titleFromUrl(url));
    }

    @Test
    void contentHashIgnoresCaseAndDiffersForDifferentText() {
        assertEquals(HTMLParser.contentHash("Brake Service"), HTMLParser.contentHash("brake service"));
        assertNotEquals(HTMLParser.contentHash("brake service"), HTMLParser.contentHash("battery service"));
        assertEquals("", HTMLParser.contentHash("  "));
        assertEquals("", HTMLParser.contentHash(null));
    }
}

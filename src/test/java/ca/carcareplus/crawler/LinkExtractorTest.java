package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LinkExtractorTest {

    @Test
    void findsDoubleSingleAndUnquotedHrefs() {
        String html = "<a href=\"/one\">1</a> <a class='x' href='/two'>2</a> <a href=/three>3</a>";
        assertEquals(List.of("/one", "/two", "/three"), LinkExtractor.extractLinks(html));
    }

    @Test
    void isCaseInsensitiveAndToleratesSpaces() {
        assertEquals(List.of("/brakes"), LinkExtractor.extractLinks("<A  HREF = \"/brakes\" >Brakes</A>"));
    }

    @Test
    void decodesAmpersandEntities() {
        assertEquals(List.of("/stores?city=windsor&prov=on"),
                LinkExtractor.extractLinks("<a href=\"/stores?city=windsor&amp;prov=on\">x</a>"));
    }

    @Test
    void ignoresAnchorsWithoutHrefAndOtherTags() {
        String html = "<a name=\"top\">x</a><abbr href=\"/no\">y</abbr><link href=\"/style.css\">"
                + "<a data-href=\"/not-a-link\">z</a>";
        assertTrue(LinkExtractor.extractLinks(html).isEmpty());
    }

    @Test
    void removesDuplicatesButKeepsOrder() {
        String html = "<a href=\"/b\"></a><a href=\"/a\"></a><a href=\"/b\"></a>";
        assertEquals(List.of("/b", "/a"), LinkExtractor.extractLinks(html));
    }

    @Test
    void emptyOrNullHtmlGivesNoLinks() {
        assertTrue(LinkExtractor.extractLinks(null).isEmpty());
        assertTrue(LinkExtractor.extractLinks("").isEmpty());
        assertTrue(LinkExtractor.extractLinks("<html><body>No links</body></html>").isEmpty());
    }

    @Test
    void survivesMalformedHtml() {
        String html = "<a href=\"/ok\">fine</a><a href=\"/unclosed <div><a href='/second'>";
        assertTrue(LinkExtractor.extractLinks(html).contains("/ok"));
    }

    @Test
    void readsSitemapLocations() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <url><loc>https://www.example.ca/locate-charger/on/windsor/1/</loc></url>
                  <url><loc> https://www.example.ca/locate-charger/on/london/2/ </loc></url>
                  <url><loc>https://www.example.ca/a?x=1&amp;y=2</loc></url>
                </urlset>
                """;
        assertEquals(List.of(
                "https://www.example.ca/locate-charger/on/windsor/1/",
                "https://www.example.ca/locate-charger/on/london/2/",
                "https://www.example.ca/a?x=1&y=2"), LinkExtractor.extractSitemapUrls(xml));
        assertTrue(LinkExtractor.extractSitemapUrls(null).isEmpty());
    }
}

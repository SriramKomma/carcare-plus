package ca.carcareplus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class ContentExtractorTest {

    private static final String BASE = "https://www.example.ca/services/brakes";

    private static Document html(String html) {
        return Jsoup.parse(html, BASE);
    }

    // ------------------------------------------------------------------- title

    @Test
    void titleComesFromTitleTag() {
        assertEquals("Brakes | Example", ContentExtractor.title(html(
                "<title>  Brakes |\n Example </title><h1>Other</h1>")));
    }

    @Test
    void titleFallsBackToOgTitleThenH1() {
        assertEquals("OG title", ContentExtractor.title(html(
                "<meta property='og:title' content='OG title'><h1>Heading</h1>")));
        assertEquals("Heading", ContentExtractor.title(html("<title> </title><h1>Heading</h1>")));
    }

    @Test
    void noTitleAnywhereGivesEmptyString() {
        assertEquals("", ContentExtractor.title(html("<p>just text</p>")));
    }

    // ------------------------------------------------------------- description

    @Test
    void descriptionComesFromMetaTag() {
        assertEquals("Brake repair in Windsor.", ContentExtractor.description(html(
                "<meta name='description' content=' Brake repair in Windsor. '><p>Para</p>")));
    }

    @Test
    void descriptionFallsBackToOgThenFirstNonEmptyParagraph() {
        assertEquals("OG", ContentExtractor.description(html(
                "<meta property='og:description' content='OG'><p>Para</p>")));
        assertEquals("First real paragraph.", ContentExtractor.description(html(
                "<nav><p>Menu text</p></nav><p> </p><p>First real paragraph.</p><p>Second.</p>")));
    }

    @Test
    void longFallbackDescriptionIsCutAtAWord() {
        String longText = "word ".repeat(200);
        String description = ContentExtractor.description(html("<p>" + longText + "</p>"));
        assertTrue(description.length() <= ContentExtractor.MAX_DESCRIPTION_LENGTH);
        assertTrue(description.endsWith("word..."), description);
    }

    @Test
    void noDescriptionGivesEmptyString() {
        assertEquals("", ContentExtractor.description(html("<h1>Only a heading</h1>")));
    }

    // ---------------------------------------------------------------- headings

    @Test
    void headingsInOrderWithoutDuplicatesOrMenuHeadings() {
        List<String> headings = ContentExtractor.headings(html(
                "<nav><h2>Menu</h2></nav><h1>Brakes</h1><h4>Too small</h4><h2>Pads</h2>"
                + "<h3>Rotors</h3><h2>Pads</h2><h2> </h2><footer><h3>Footer</h3></footer>"));
        assertEquals(List.of("Brakes", "Pads", "Rotors"), headings);
    }

    // --------------------------------------------------------------- main text

    @Test
    void mainTextDropsScriptsStylesMenusAndHiddenParts() {
        String text = ContentExtractor.mainText(html("""
                <head><style>p { color: red }</style><script>var x = 'secret';</script></head>
                <body>
                  <header>Site header</header>
                  <nav>Home | Services</nav>
                  <p>Brake service</p>
                  <noscript>Enable JavaScript</noscript>
                  <div hidden>hidden text</div>
                  <span aria-hidden="true">icon</span>
                  <div style="display:none">invisible</div>
                  <aside>Side bar</aside>
                  <footer>Site footer</footer>
                </body>"""));
        assertEquals("Brake service", text);
    }

    @Test
    void mainTextPrefersMainElementWhenItHasRealContent() {
        String mainContent = "Real content. ".repeat(10);
        String text = ContentExtractor.mainText(html(
                "<div>Cookie banner</div><main>" + mainContent + "</main><div>Newsletter</div>"));
        assertEquals(mainContent.trim(), text);
    }

    @Test
    void tinyMainElementIsIgnored() {
        String text = ContentExtractor.mainText(html("<main>Hi</main><div>Body text</div>"));
        assertEquals("Hi Body text", text);
    }

    @Test
    void articleHeaderIsKeptButSiteHeaderIsRemoved() {
        Document doc = html("<header><h1>Site logo</h1></header>"
                + "<article><header><h1>Article title</h1></header>" + "<p>Text. </p>".repeat(30) + "</article>");
        assertEquals(List.of("Article title"), ContentExtractor.headings(doc));
        assertTrue(ContentExtractor.mainText(doc).startsWith("Article title Text."));
    }

    @Test
    void blockElementsAreSeparatedBySpaces() {
        assertEquals("Phone 604-555-0142 Email a@b.ca",
                ContentExtractor.mainText(html("<p>Phone</p><p>604-555-0142</p><div>Email</div><div>a@b.ca</div>")));
    }

    @Test
    void extractingDoesNotChangeTheDocument() {
        Document doc = html("<nav>Menu</nav><p>Text</p>");
        ContentExtractor.mainText(doc);
        assertEquals(1, doc.select("nav").size());
    }

    // ------------------------------------------------------------------- links

    @Test
    void linksAreAbsoluteNormalizedAndUnique() {
        List<String> links = ContentExtractor.links(html("""
                <a href="pads">Pads</a>
                <a href="/stores#map">Stores</a>
                <a href="/stores">Stores again</a>
                <a href="https://other.ca/x">Other site</a>
                <a href="mailto:info@example.ca">Mail</a>
                <a href="tel:5195550123">Call</a>
                <a href="javascript:void(0)">JS</a>
                <a>No href</a>"""));
        assertEquals(List.of(
                "https://www.example.ca/services/pads",
                "https://www.example.ca/stores",
                "https://other.ca/x"), links);
    }

    @Test
    void menuLinksAreKept() {
        assertEquals(List.of("https://www.example.ca/about"),
                ContentExtractor.links(html("<nav><a href='/about'>About</a></nav>")));
    }

    // -------------------------------------------------------------- cleanText

    @Test
    void cleanTextNormalizesWhitespace() {
        assertEquals("a b c d", ContentExtractor.cleanText(" a b\t\n c​ d "));
        assertEquals("", ContentExtractor.cleanText(null));
        assertEquals("", ContentExtractor.cleanText(" \n "));
    }

    @Test
    void shortenLeavesShortTextAlone() {
        assertEquals("short", ContentExtractor.shorten("short", 10));
        assertEquals("aaaaaaa...", ContentExtractor.shorten("a".repeat(20), 10));
        assertFalse(ContentExtractor.shorten("one two three four", 12).contains("thr"));
    }
}

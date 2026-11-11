package ca.carcareplus.parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.CrawledPage;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.validation.PatternExtractor;

/**
 * HTML parser feature: turns one raw crawled page into a searchable ServiceDocument.
 *
 * Steps for each page:
 * 1. jsoup builds a DOM tree. It repairs broken HTML (unclosed tags, missing
 *    &lt;body&gt;) the same way a browser does, so parsing never fails on bad markup.
 * 2. ContentExtractor fills title, description, headings, main text and links,
 *    with fallbacks when something is missing.
 * 3. PatternExtractor (regex) adds phones, emails, postal codes and province.
 * 4. ServiceCategorizer adds categories and vehicle types.
 * 5. A SHA-256 hash of the text lets storage spot the same content under two URLs.
 *
 * Cost: building the tree is O(n) in the HTML length; each extraction step is
 * another O(n) walk, so a page is parsed in linear time.
 */
public class HTMLParser {

    private final ServiceCategorizer categorizer;

    public HTMLParser(ServiceCategorizer categorizer) {
        if (categorizer == null) {
            throw new InvalidInputException("HTMLParser needs a ServiceCategorizer.");
        }
        this.categorizer = categorizer;
    }

    /**
     * Parses a crawled page. Never throws because of the HTML itself: empty or
     * malformed HTML gives a document with empty fields, and the title falls back
     * to the URL.
     *
     * @throws InvalidInputException only if page is null
     */
    public ServiceDocument parse(CrawledPage page) {
        if (page == null) {
            throw new InvalidInputException("Cannot parse a null page.");
        }
        // The URL is the "base URI": jsoup uses it to resolve relative links like "../stores"
        Document dom = Jsoup.parse(page.html(), page.url());

        ServiceDocument doc = new ServiceDocument(page.url(), page.sourceName());
        doc.setCrawledAt(page.fetchedAt().toString());
        doc.setDescription(ContentExtractor.description(dom));
        doc.setHeadings(ContentExtractor.headings(dom));
        doc.setBodyText(ContentExtractor.mainText(dom));
        doc.setLinks(ContentExtractor.links(dom));

        String title = ContentExtractor.title(dom);
        doc.setTitle(title.isEmpty() ? titleFromUrl(page.url()) : title);

        PatternExtractor.fillContactDetails(doc);
        categorizer.tag(doc);
        doc.setContentHash(contentHash(doc.getBodyText()));
        return doc;
    }

    /**
     * Last-resort title made from the URL's last path part:
     * "https://x.ca/stores/abbotsford-bc" -> "abbotsford bc"; a bare domain gives the host.
     */
    static String titleFromUrl(String url) {
        String withoutQuery = url.replaceAll("[?#].*$", "");
        String[] parts = withoutQuery.replaceAll("/+$", "").split("/");
        String last = parts[parts.length - 1];
        String words = last.replaceAll("\\.(html?|php|aspx?)$", "").replaceAll("[-_]+", " ").trim();
        return words.isEmpty() ? url : words;
    }

    /**
     * SHA-256 of the lower-cased text, as 64 hex characters. Two pages with the same
     * text get the same hash, so storage can keep only one of them. Empty text
     * gives "" (no hash), so empty pages are not all treated as duplicates of each other.
     */
    static String contentHash(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(text.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // every Java runtime must support SHA-256, so this cannot happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

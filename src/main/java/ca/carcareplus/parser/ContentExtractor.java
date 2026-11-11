package ca.carcareplus.parser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import ca.carcareplus.crawler.UrlNormalizer;

/**
 * Pulls the useful parts out of a jsoup DOM tree.
 *
 * jsoup only builds the tree (and repairs broken HTML such as unclosed tags).
 * Everything in this class is our own logic: which element to trust for the
 * title, what counts as "main text", what noise to remove, and how to clean it.
 *
 * Every method returns "" or an empty list instead of null, and never throws on
 * strange HTML, so one bad page can never stop a whole parsing run.
 */
public final class ContentExtractor {

    /** Longest description we keep; a fallback paragraph is cut to this length. */
    static final int MAX_DESCRIPTION_LENGTH = 300;

    /**
     * Elements that are never page content: code, styling, menus, side bars,
     * forms and hidden elements. Site-wide header/footer are handled separately
     * in withoutNoise().
     */
    static final String NOISE_SELECTOR = String.join(", ",
            "script", "style", "noscript", "template", "iframe", "svg", "canvas",
            "nav", "aside", "form", "button", "select",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]",
            "[hidden]", "[aria-hidden=true]", "[style*=display:none]", "[style*=display: none]");

    /** A <main> or <article> shorter than this is probably not the real content. */
    static final int MIN_MAIN_TEXT_LENGTH = 100;

    private ContentExtractor() {
        // static helper methods only
    }

    /**
     * Title, trying in order: &lt;title&gt;, &lt;meta property="og:title"&gt;, first &lt;h1&gt;.
     *
     * @return the cleaned title, or "" if the page has none of them
     */
    public static String title(Document doc) {
        String title = cleanText(doc.title());
        if (title.isEmpty()) {
            title = metaContent(doc, "meta[property=og:title]");
        }
        if (title.isEmpty()) {
            Element h1 = doc.selectFirst("h1");
            title = h1 == null ? "" : cleanText(h1.text());
        }
        return title;
    }

    /**
     * Description, trying in order: &lt;meta name="description"&gt;,
     * &lt;meta property="og:description"&gt;, first paragraph of the main text
     * (cut at a word boundary to {@value #MAX_DESCRIPTION_LENGTH} characters).
     */
    public static String description(Document doc) {
        String description = metaContent(doc, "meta[name=description]");
        if (description.isEmpty()) {
            description = metaContent(doc, "meta[property=og:description]");
        }
        if (description.isEmpty()) {
            for (Element p : contentRoot(doc).select("p")) {
                String text = cleanText(p.text());
                if (!text.isEmpty()) {
                    description = shorten(text, MAX_DESCRIPTION_LENGTH);
                    break;
                }
            }
        }
        return description;
    }

    /** Text of &lt;h1&gt;, &lt;h2&gt; and &lt;h3&gt; headings outside menus/footers, in page order, no duplicates. */
    public static List<String> headings(Document doc) {
        Set<String> result = new LinkedHashSet<>();
        for (Element heading : withoutNoise(doc).select("h1, h2, h3")) {
            String text = cleanText(heading.text());
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * The page's readable text without menus, scripts, header, footer, etc.
     * If the page marks its content with &lt;main&gt; or &lt;article&gt;, only that part is used.
     * jsoup's text() puts a space between block elements, so words from
     * neighbouring paragraphs are not glued together.
     */
    public static String mainText(Document doc) {
        return cleanText(contentRoot(doc).text());
    }

    /**
     * Every link on the page as an absolute, normalized http(s) URL, no duplicates.
     * Links inside menus and footers are kept here, because the crawler and the
     * document's link list should see the whole site structure.
     * mailto:, tel:, javascript: and broken links are skipped by UrlNormalizer.
     */
    public static List<String> links(Document doc) {
        Set<String> result = new LinkedHashSet<>();
        String base = doc.location();
        for (Element a : doc.select("a[href]")) {
            String url = base.isEmpty()
                    ? UrlNormalizer.normalize(a.attr("href"))
                    : UrlNormalizer.normalize(base, a.attr("href"));
            if (url != null) {
                result.add(url);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * Tidies text from a web page: non-breaking spaces become normal spaces,
     * invisible zero-width characters are removed, and any run of whitespace
     * (spaces, tabs, new lines) becomes one space.
     */
    public static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(' ', ' ')
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The element whose text is the real content: &lt;main&gt;, else &lt;article&gt;,
     * else the whole body (all with noise removed).
     */
    private static Element contentRoot(Document doc) {
        Element cleaned = withoutNoise(doc);
        for (String tag : new String[] {"main", "[role=main]", "article"}) {
            Element candidate = cleaned.selectFirst(tag);
            if (candidate != null && candidate.text().length() >= MIN_MAIN_TEXT_LENGTH) {
                return candidate;
            }
        }
        return cleaned;
    }

    /**
     * A copy of the body with noise removed (the original document is not changed).
     *
     * &lt;header&gt; and &lt;footer&gt; are removed only when they belong to the whole site,
     * i.e. are not inside &lt;main&gt; or &lt;article&gt;. HTML5 lets an article have its own
     * header holding its &lt;h1&gt;, which we want to keep. The site footer must go:
     * a head-office phone number there would otherwise be attached to every store page.
     */
    private static Element withoutNoise(Document doc) {
        Element body = doc.body().clone();
        body.select(NOISE_SELECTOR).remove();
        for (Element part : body.select("header, footer")) {
            if (part.closest("main, article") == null) {
                part.remove();
            }
        }
        return body;
    }

    private static String metaContent(Document doc, String selector) {
        Element meta = doc.selectFirst(selector);
        return meta == null ? "" : cleanText(meta.attr("content"));
    }

    /** Cuts text to at most max characters, at the last space, adding "...". */
    static String shorten(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int cut = text.lastIndexOf(' ', max - 3);
        if (cut <= 0) {
            cut = max - 3;
        }
        return text.substring(0, cut).trim() + "...";
    }
}

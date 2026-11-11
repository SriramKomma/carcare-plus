package ca.carcareplus.crawler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the links the crawler should follow.
 *
 * The crawler only needs href values, not a full DOM, so a small regular expression
 * is enough here and keeps the crawler independent of the HTML parser module.
 * The full parser (Phase 5) extracts links again, with text and structure, for documents.
 *
 * Results keep first-seen order and contain no duplicates (LinkedHashSet).
 */
public final class LinkExtractor {

    /*
     * <a ... href="x">, <a ... href='x'> or <a ... href=x>
     * "<a\s" stops "<abbr>" from matching; "\s" before href stops "data-href" from matching.
     * [^>]*? cannot run past the end of the tag, so matching stays linear in the input size.
     */
    private static final Pattern ANCHOR_HREF = Pattern.compile(
            "<a\\s(?:[^>]*?\\s)?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))",
            Pattern.CASE_INSENSITIVE);

    /** <loc>https://...</loc> entries of an XML sitemap. */
    private static final Pattern SITEMAP_LOC = Pattern.compile(
            "<loc>\\s*([^<\\s]+)\\s*</loc>", Pattern.CASE_INSENSITIVE);

    private LinkExtractor() {
    }

    /** All href values of anchor tags in the HTML, entity-decoded. Empty list for null/empty input. */
    public static List<String> extractLinks(String html) {
        if (html == null || html.isEmpty()) {
            return List.of();
        }
        Set<String> links = new LinkedHashSet<>();
        Matcher matcher = ANCHOR_HREF.matcher(html);
        while (matcher.find()) {
            String value = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3));
            if (value != null && !value.isBlank()) {
                links.add(decodeEntities(value.trim()));
            }
        }
        return new ArrayList<>(links);
    }

    /** All page URLs listed in an XML sitemap. Empty list for null/empty input. */
    public static List<String> extractSitemapUrls(String xml) {
        if (xml == null || xml.isEmpty()) {
            return List.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        Matcher matcher = SITEMAP_LOC.matcher(xml);
        while (matcher.find()) {
            urls.add(decodeEntities(matcher.group(1)));
        }
        return new ArrayList<>(urls);
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Decodes the HTML entities that appear in URLs, e.g. "a?x=1&amp;y=2" -> "a?x=1&y=2".
     * "&amp;" is decoded last so "&amp;quot;" becomes "&quot;", not a quote character.
     */
    static String decodeEntities(String text) {
        return text.replace("&#38;", "&")
                .replace("&#x26;", "&")
                .replace("&#X26;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}

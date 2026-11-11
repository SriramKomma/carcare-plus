package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlNormalizerTest {

    private static final String BASE = "https://www.example.ca/services/brakes";

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "../stores/windsor,           https://www.example.ca/stores/windsor",
        "pads,                        https://www.example.ca/services/pads",
        "/about,                      https://www.example.ca/about",
        "./rotors,                    https://www.example.ca/services/rotors",
        "?page=2,                     https://www.example.ca/services/brakes?page=2",
        "//cdn.example.ca/logo.png,   https://cdn.example.ca/logo.png",
        "https://other.ca/x,          https://other.ca/x",
        "/../../a,                    https://www.example.ca/a"
    })
    void resolvesRelativeLinks(String href, String expected) {
        assertEquals(expected, UrlNormalizer.normalize(BASE, href));
    }

    @Test
    void lowerCasesSchemeAndHostButNotPath() {
        assertEquals("https://www.example.ca/Stores/Windsor",
                UrlNormalizer.normalize("HTTPS://WWW.Example.CA/Stores/Windsor"));
    }

    @Test
    void removesFragment() {
        assertEquals("https://www.example.ca/brakes", UrlNormalizer.normalize("https://www.example.ca/brakes#top"));
    }

    @Test
    void fragmentOnlyLinkIsIgnored() {
        assertNull(UrlNormalizer.normalize(BASE, "#reviews"));
    }

    @Test
    void removesDefaultPortsButKeepsOthers() {
        assertEquals("https://example.ca/", UrlNormalizer.normalize("https://example.ca:443"));
        assertEquals("http://example.ca/", UrlNormalizer.normalize("http://example.ca:80/"));
        assertEquals("http://example.ca:8080/", UrlNormalizer.normalize("http://example.ca:8080/"));
    }

    @Test
    void emptyPathBecomesSlash() {
        assertEquals("https://example.ca/", UrlNormalizer.normalize("https://example.ca"));
    }

    @Test
    void relativeLinkAgainstBaseWithoutPathWorks() {
        // java.net.URI alone would produce "https://example.caabout"
        assertEquals("https://example.ca/about", UrlNormalizer.normalize("https://example.ca", "about"));
    }

    @Test
    void removesTrackingParametersOnly() {
        assertEquals("https://example.ca/a?id=5",
                UrlNormalizer.normalize("https://example.ca/a?utm_source=x&id=5&UTM_medium=y"));
        assertEquals("https://example.ca/a", UrlNormalizer.normalize("https://example.ca/a?utm_source=x"));
    }

    @Test
    void encodesSpaces() {
        assertEquals("https://example.ca/brake%20service", UrlNormalizer.normalize("https://example.ca", "/brake service"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "mailto:service@example.ca", "tel:+15195550100", "javascript:void(0)",
        "data:text/plain,hi", "ftp://example.ca/file.txt", "http://[bad", "https:///no-host"
    })
    void rejectsNonWebAndMalformedLinks(String href) {
        assertNull(UrlNormalizer.normalize(BASE, href));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingHref(String href) {
        assertNull(UrlNormalizer.normalize(BASE, href));
    }

    @Test
    void relativeLinkWithoutBaseIsRejected() {
        assertNull(UrlNormalizer.normalize(null, "/about"));
        assertNull(UrlNormalizer.normalize("not a url", "/about"));
    }

    @ParameterizedTest(name = "{0} in {1} = {2}")
    @CsvSource({
        "https://www.example.ca/a,       example.ca,     true",
        "https://example.ca/a,           example.ca,     true",
        "https://shop.example.ca/a,      example.ca,     true",
        "https://example.ca/a,           www.example.ca, true",
        "https://badexample.ca/a,        example.ca,     false",
        "https://example.ca.evil.com/a,  example.ca,     false",
        "not a url,                      example.ca,     false"
    })
    void checksDomainMembership(String url, String domain, boolean expected) {
        assertEquals(expected, UrlNormalizer.isInDomain(url, domain));
    }

    @Test
    void domainCheckHandlesNulls() {
        assertFalse(UrlNormalizer.isInDomain(null, "example.ca"));
        assertFalse(UrlNormalizer.isInDomain("https://example.ca", null));
    }

    @Test
    void extractsOriginAndPath() {
        String url = "https://WWW.Example.ca:8443/stores?q=1#x";
        assertEquals("https://www.example.ca:8443", UrlNormalizer.originOf(url));
        assertEquals("/stores?q=1", UrlNormalizer.pathAndQuery(url));
        assertNull(UrlNormalizer.originOf("bad"));
        assertEquals("/", UrlNormalizer.pathAndQuery("bad"));
    }

    @Test
    void normalizingTwiceChangesNothing() {
        String once = UrlNormalizer.normalize("HTTPS://Example.ca:443/a/./b/../c?utm_x=1&k=v#f");
        assertEquals("https://example.ca/a/c?k=v", once);
        assertTrue(once.equals(UrlNormalizer.normalize(once)));
    }
}

package ca.carcareplus.crawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RobotsTxtParserTest {

    private static final String AGENT = WebCrawler.USER_AGENT;

    private static RobotsTxtParser parse(String content) {
        return RobotsTxtParser.parse(content, AGENT);
    }

    @Test
    void emptyOrMissingFileAllowsEverything() {
        assertTrue(parse(null).isAllowed("/anything"));
        assertTrue(parse("").isAllowed("/anything"));
        assertTrue(RobotsTxtParser.allowAll().isAllowed("/"));
    }

    @Test
    void disallowSlashBlocksEverything() {
        RobotsTxtParser robots = parse("User-agent: *\nDisallow: /\n");
        assertFalse(robots.isAllowed("/"));
        assertFalse(robots.isAllowed("/stores/windsor"));
        assertFalse(RobotsTxtParser.disallowAll().isAllowed("/x"));
    }

    @Test
    void emptyDisallowAllowsEverything() {
        assertTrue(parse("User-agent: *\nDisallow:\n").isAllowed("/private"));
    }

    @Test
    void blocksOnlyMatchingPrefix() {
        RobotsTxtParser robots = parse("User-agent: *\nDisallow: /admin\n");
        assertFalse(robots.isAllowed("/admin"));
        assertFalse(robots.isAllowed("/admin/users"));
        assertFalse(robots.isAllowed("/administrator")); // prefix match, as in the standard
        assertTrue(robots.isAllowed("/stores"));
        assertTrue(robots.isAllowed(null)); // null means "/"
    }

    @Test
    void longestMatchingRuleWins() {
        RobotsTxtParser robots = parse("""
                User-agent: *
                Disallow: /stores
                Allow: /stores/windsor
                """);
        assertTrue(robots.isAllowed("/stores/windsor-on"));
        assertFalse(robots.isAllowed("/stores/london-on"));
    }

    @Test
    void allowWinsATie() {
        RobotsTxtParser robots = parse("User-agent: *\nDisallow: /page\nAllow: /page\n");
        assertTrue(robots.isAllowed("/page"));
    }

    @ParameterizedTest(name = "{0} blocked={1}")
    @CsvSource({
        "/en/search,          true",
        "/search?q=tires,     true",
        "/stores?q=1,         true",
        "/stores,             false",
        "/stores?city=x,      false"
    })
    void wildcardRulesFromFountainTireStyleFile(String path, boolean blocked) {
        RobotsTxtParser robots = parse("User-agent: *\nDisallow: */search\nDisallow: *?q\n");
        assertEquals(!blocked, robots.isAllowed(path));
    }

    @Test
    void dollarAnchorsTheEnd() {
        RobotsTxtParser robots = parse("User-agent: *\nDisallow: /*.pdf$\n");
        assertFalse(robots.isAllowed("/guides/brakes.pdf"));
        assertTrue(robots.isAllowed("/guides/brakes.pdf?download=1"));
        assertTrue(robots.isAllowed("/guides/brakes.html"));
    }

    @Test
    void groupNamingOurCrawlerReplacesWildcardGroup() {
        RobotsTxtParser robots = parse("""
                User-agent: CarCarePlus-COMP8547-StudentCrawler
                Disallow: /

                User-agent: *
                Allow: /
                """);
        assertFalse(robots.isAllowed("/stores"));

        RobotsTxtParser other = RobotsTxtParser.parse("User-agent: CarCarePlus-COMP8547-StudentCrawler\nDisallow: /\n"
                + "User-agent: *\nAllow: /\n", "SomeOtherBot/2.0");
        assertTrue(other.isAllowed("/stores"));
    }

    @Test
    void rulesForOtherBotsDoNotAffectUs() {
        // Same shape as kaltire.com: AI crawlers are blocked, everyone else is allowed
        RobotsTxtParser robots = parse("""
                User-agent: *
                Content-Signal: search=yes,ai-train=no,use=reference
                Allow: /

                User-agent: ClaudeBot
                Disallow: /

                User-agent: GPTBot
                Disallow: /
                """);
        assertTrue(robots.isAllowed("/en/services/"));
    }

    @Test
    void severalUserAgentLinesShareOneGroup() {
        RobotsTxtParser robots = parse("""
                User-agent: SomeBot
                User-agent: *
                Disallow: /private
                """);
        assertFalse(robots.isAllowed("/private/page"));
    }

    @Test
    void readsCrawlDelayInMilliseconds() {
        assertEquals(10_000, parse("User-agent: *\nCrawl-delay: 10\n").crawlDelayMillis());
        assertEquals(1_500, parse("User-agent: *\nCrawl-delay: 1.5\n").crawlDelayMillis());
        assertEquals(0, parse("User-agent: *\nCrawl-delay: soon\n").crawlDelayMillis());
        assertEquals(0, parse("User-agent: OtherBot\nCrawl-delay: 30\n").crawlDelayMillis());
    }

    @Test
    void handlesCommentsCaseAndWindowsLineEndings() {
        RobotsTxtParser robots = parse("# site rules\r\nUSER-AGENT: *   # everyone\r\nDISALLOW: /tmp # temp\r\n");
        assertFalse(robots.isAllowed("/tmp/file"));
        assertTrue(robots.isAllowed("/home"));
    }

    @Test
    void rulesBeforeAnyUserAgentAreIgnored() {
        assertTrue(parse("Disallow: /\nUser-agent: *\nAllow: /\n").isAllowed("/x"));
    }

    @ParameterizedTest(name = "\"{0}\" matches \"{1}\" = {2}")
    @CsvSource({
        "a*c,    abc,     true",
        "a*c,    ac,      true",
        "a*c,    abcbc,   true",
        "a*c,    abd,     false",
        "*,      '',      true",
        "**a,    ba,      true",
        "/x*y*z, /xAAyBBz, true",
        "/x*y*z, /xAAyBB,  false"
    })
    void wildcardMatchAlgorithm(String pattern, String text, boolean expected) {
        assertEquals(expected, RobotsTxtParser.wildcardMatch(pattern, text));
    }
}

package ca.carcareplus.crawler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a website's robots.txt and answers "may our crawler fetch this path?".
 *
 * Follows the robots exclusion standard (RFC 9309), which is how Google reads robots.txt:
 *  - The file is split into groups. Each group starts with one or more "User-agent:" lines
 *    followed by "Allow:", "Disallow:" and "Crawl-delay:" lines.
 *  - We use the group(s) naming our crawler; if none do, the "User-agent: *" group(s).
 *  - The rule with the LONGEST matching pattern wins. If an Allow and a Disallow rule
 *    are equally long, Allow wins. No matching rule means allowed.
 *  - Patterns match from the start of the path. "*" matches any characters and a final
 *    "$" means "the path must end here". Example: "/*.pdf$" blocks "/guide.pdf".
 *
 * Unknown lines (Sitemap:, Host:, Content-Signal:, ...) are ignored.
 */
public final class RobotsTxtParser {

    /** RFC 9309 lets crawlers ignore anything after the first 500 KiB. */
    public static final int MAX_ROBOTS_CHARS = 500 * 1024;

    private record Rule(String pattern, boolean allow) {
    }

    /** One "User-agent" block of the file. */
    private static final class Group {
        final List<String> agents = new ArrayList<>();
        final List<Rule> rules = new ArrayList<>();
        long crawlDelayMillis = 0;

        boolean isWildcard() {
            return agents.contains("*");
        }

        boolean namesAgent(String productToken) {
            for (String agent : agents) {
                if (!agent.equals("*") && productToken.startsWith(agent)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Rule> rules;
    private final long crawlDelayMillis;

    private RobotsTxtParser(List<Rule> rules, long crawlDelayMillis) {
        this.rules = List.copyOf(rules);
        this.crawlDelayMillis = crawlDelayMillis;
    }

    /** Rules for a site with no robots.txt (HTTP 404): everything is allowed. */
    public static RobotsTxtParser allowAll() {
        return new RobotsTxtParser(List.of(), 0);
    }

    /** Rules used when robots.txt cannot be read (server error, timeout): nothing is allowed. */
    public static RobotsTxtParser disallowAll() {
        return new RobotsTxtParser(List.of(new Rule("/", false)), 0);
    }

    /**
     * Parses robots.txt content for one crawler.
     *
     * @param content   the file's text; null or blank means "no rules"
     * @param userAgent our full User-Agent, e.g. "CarCarePlus-COMP8547-StudentCrawler/1.0 (...)"
     */
    public static RobotsTxtParser parse(String content, String userAgent) {
        if (content == null || content.isBlank()) {
            return allowAll();
        }
        if (content.length() > MAX_ROBOTS_CHARS) {
            content = content.substring(0, MAX_ROBOTS_CHARS);
        }

        String productToken = productToken(userAgent);
        List<Group> specific = new ArrayList<>();
        List<Group> wildcard = new ArrayList<>();
        for (Group group : parseGroups(content)) {
            if (group.namesAgent(productToken)) {
                specific.add(group);
            } else if (group.isWildcard()) {
                wildcard.add(group);
            }
        }

        // Groups naming us replace the "*" groups completely; several matching groups are merged
        List<Group> chosen = specific.isEmpty() ? wildcard : specific;
        List<Rule> rules = new ArrayList<>();
        long delay = 0;
        for (Group group : chosen) {
            rules.addAll(group.rules);
            delay = Math.max(delay, group.crawlDelayMillis);
        }
        return new RobotsTxtParser(rules, delay);
    }

    /** "CarCarePlus-Crawler/1.0 (info)" -> "carcareplus-crawler" */
    private static String productToken(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "*";
        }
        String token = userAgent.trim().split("[/\\s]", 2)[0];
        return token.toLowerCase(Locale.ROOT);
    }

    private static List<Group> parseGroups(String content) {
        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean previousLineWasUserAgent = false;

        for (String rawLine : content.split("\\r?\\n|\\r")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            int colon = line.indexOf(':');
            if (line.isEmpty() || colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            if (key.equals("user-agent")) {
                // Consecutive User-agent lines share one group; one after rules starts a new group
                if (current == null || !previousLineWasUserAgent) {
                    current = new Group();
                    groups.add(current);
                }
                if (!value.isEmpty()) {
                    current.agents.add(value.toLowerCase(Locale.ROOT));
                }
                previousLineWasUserAgent = true;
                continue;
            }
            previousLineWasUserAgent = false;
            if (current == null) {
                continue; // rules before any User-agent line belong to nobody
            }
            switch (key) {
                // An empty "Disallow:" means "nothing is disallowed", so it adds no rule
                case "allow", "disallow" -> {
                    if (!value.isEmpty()) {
                        current.rules.add(new Rule(value, key.equals("allow")));
                    }
                }
                case "crawl-delay" -> current.crawlDelayMillis = parseDelayMillis(value);
                default -> {
                    // Sitemap, Host, Content-Signal and other extensions are not needed here
                }
            }
        }
        return groups;
    }

    private static long parseDelayMillis(String seconds) {
        try {
            double value = Double.parseDouble(seconds);
            return value > 0 ? Math.round(value * 1000) : 0;
        } catch (NumberFormatException e) {
            return 0; // invalid value: ignore the directive
        }
    }

    /**
     * May the crawler fetch this path?
     *
     * @param pathAndQuery path plus optional query, e.g. "/stores?q=1"; null or "" means "/"
     */
    public boolean isAllowed(String pathAndQuery) {
        String path = (pathAndQuery == null || pathAndQuery.isEmpty()) ? "/" : pathAndQuery;
        Rule best = null;
        for (Rule rule : rules) {
            if (!matches(rule.pattern(), path)) {
                continue;
            }
            boolean longer = best == null || rule.pattern().length() > best.pattern().length();
            boolean tieWonByAllow = best != null
                    && rule.pattern().length() == best.pattern().length() && rule.allow();
            if (longer || tieWonByAllow) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /** Crawl-delay requested by the site, in milliseconds (0 if none). */
    public long crawlDelayMillis() {
        return crawlDelayMillis;
    }

    /**
     * Robots patterns are prefix matches unless they end with "$".
     * A prefix match is the same as an exact match of pattern + "*".
     */
    static boolean matches(String pattern, String path) {
        if (pattern.endsWith("$")) {
            return wildcardMatch(pattern.substring(0, pattern.length() - 1), path);
        }
        return wildcardMatch(pattern + "*", path);
    }

    /**
     * Classic two-pointer wildcard matching where '*' matches any sequence.
     *
     * When characters disagree we go back to the most recent '*' and let it swallow one
     * more character. Worst case O(n * m), usually close to O(n + m)
     * (n = path length, m = pattern length). Memory O(1).
     */
    static boolean wildcardMatch(String pattern, String text) {
        int p = 0;          // position in pattern
        int t = 0;          // position in text
        int star = -1;      // position of the last '*' seen in pattern
        int starText = 0;   // text position that '*' is currently matched up to

        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                starText = t;               // '*' starts by matching nothing
            } else if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) {
                p++;
                t++;
            } else if (star != -1) {
                p = star + 1;               // retry just after the '*'
                t = ++starText;             // with '*' swallowing one more character
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;                            // trailing '*'s can match nothing
        }
        return p == pattern.length();
    }
}

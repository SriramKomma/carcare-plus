package ca.carcareplus.crawler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ca.carcareplus.exceptions.CrawlException;
import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Settings for crawling one source website, read from config/sources.txt.
 *
 * Line format (fields separated by '|'):
 * <pre>
 * sourceName | allowedDomain | maxDepth | maxPages | delayMillis | pathPrefixes | seedUrls
 * </pre>
 * pathPrefixes and seedUrls are comma-separated; pathPrefixes "*" means "any path".
 *
 * @param sourceName    display name, e.g. "Fountain Tire"
 * @param allowedDomain links outside this domain are never followed
 * @param maxDepth      BFS levels to follow from the seeds (0 = seeds only)
 * @param maxPages      stop after this many pages are saved
 * @param delayMillis   pause before each request (never less than MIN_DELAY_MILLIS)
 * @param pathPrefixes  only follow links whose path starts with one of these (empty = all)
 * @param seedUrls      starting URLs (normalized; seeds are always fetched)
 */
public record CrawlConfig(
        String sourceName,
        String allowedDomain,
        int maxDepth,
        int maxPages,
        long delayMillis,
        List<String> pathPrefixes,
        List<String> seedUrls) {

    public static final int MAX_DEPTH_LIMIT = 5;
    public static final int MAX_PAGES_LIMIT = 500;
    /** Politeness floor: at most one request per second, whatever the config says. */
    public static final long MIN_DELAY_MILLIS = 1000;

    private static final int FIELD_COUNT = 7;

    public CrawlConfig {
        if (sourceName == null || sourceName.isBlank()) {
            throw new InvalidInputException("Source name is required.");
        }
        if (allowedDomain == null || allowedDomain.isBlank()) {
            throw new InvalidInputException("Allowed domain is required for " + sourceName + ".");
        }
        if (maxDepth < 0 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new InvalidInputException("maxDepth must be between 0 and " + MAX_DEPTH_LIMIT + ".");
        }
        if (maxPages < 1 || maxPages > MAX_PAGES_LIMIT) {
            throw new InvalidInputException("maxPages must be between 1 and " + MAX_PAGES_LIMIT + ".");
        }
        sourceName = sourceName.trim();
        allowedDomain = allowedDomain.trim().toLowerCase(Locale.ROOT);
        delayMillis = Math.max(delayMillis, MIN_DELAY_MILLIS);

        List<String> prefixes = new ArrayList<>();
        if (pathPrefixes != null) {
            for (String prefix : pathPrefixes) {
                if (prefix == null || prefix.isBlank()) {
                    continue;
                }
                if (!prefix.trim().startsWith("/")) {
                    throw new InvalidInputException("Path prefix must start with '/': " + prefix.trim());
                }
                prefixes.add(prefix.trim());
            }
        }
        pathPrefixes = List.copyOf(prefixes);

        if (seedUrls == null || seedUrls.isEmpty()) {
            throw new InvalidInputException("At least one seed URL is required for " + sourceName + ".");
        }
        List<String> seeds = new ArrayList<>();
        for (String seed : seedUrls) {
            String normalized = UrlNormalizer.normalize(seed);
            if (normalized == null) {
                throw new InvalidInputException("Invalid seed URL: " + seed);
            }
            if (!UrlNormalizer.isInDomain(normalized, allowedDomain)) {
                throw new InvalidInputException("Seed URL " + seed + " is outside " + allowedDomain + ".");
            }
            seeds.add(normalized);
        }
        seedUrls = List.copyOf(seeds);
    }

    /** True if links to this URL may be followed (seeds are not checked against prefixes). */
    public boolean isPathAllowed(String url) {
        if (pathPrefixes.isEmpty()) {
            return true;
        }
        String path = UrlNormalizer.pathAndQuery(url);
        for (String prefix : pathPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Copy of this config with a different page limit (e.g. a small test crawl). */
    public CrawlConfig withMaxPages(int newMaxPages) {
        return new CrawlConfig(sourceName, allowedDomain, maxDepth, newMaxPages,
                delayMillis, pathPrefixes, seedUrls);
    }

    /**
     * Parses one line of sources.txt.
     *
     * @throws InvalidInputException with a readable reason if the line is malformed
     */
    public static CrawlConfig fromLine(String line) {
        if (line == null || line.isBlank()) {
            throw new InvalidInputException("Configuration line is empty.");
        }
        String[] fields = line.split("\\|", -1);
        if (fields.length != FIELD_COUNT) {
            throw new InvalidInputException("Expected " + FIELD_COUNT
                    + " fields separated by '|' but found " + fields.length + ".");
        }
        String prefixField = fields[5].trim();
        List<String> prefixes = prefixField.equals("*") ? List.of() : splitList(prefixField);
        return new CrawlConfig(
                fields[0],
                fields[1],
                parseNumber(fields[2], "maxDepth"),
                parseNumber(fields[3], "maxPages"),
                parseNumber(fields[4], "delayMillis"),
                prefixes,
                splitList(fields[6]));
    }

    /**
     * Reads every source from a config file. Blank lines and lines starting with '#' are skipped.
     *
     * @throws CrawlException if the file cannot be read, a line is invalid,
     *                        or two sources have the same name
     */
    public static List<CrawlConfig> loadFile(Path file) throws CrawlException {
        if (file == null) {
            throw new InvalidInputException("Config file path is required.");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CrawlException("Cannot read crawl configuration file " + file + ": " + e.getMessage(), e);
        }

        List<CrawlConfig> configs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            CrawlConfig config;
            try {
                config = fromLine(line);
            } catch (InvalidInputException e) {
                throw new CrawlException(file + " line " + (i + 1) + ": " + e.getMessage());
            }
            if (!names.add(config.sourceName().toLowerCase(Locale.ROOT))) {
                throw new CrawlException(file + " line " + (i + 1)
                        + ": duplicate source name '" + config.sourceName() + "'.");
            }
            configs.add(config);
        }
        return configs;
    }

    private static int parseNumber(String text, String fieldName) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new InvalidInputException(fieldName + " must be a whole number, but was '" + text.trim() + "'.");
        }
    }

    private static List<String> splitList(String text) {
        List<String> values = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }
}

package ca.carcareplus.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.CrawledPage;

/**
 * Saves downloaded HTML to disk so parsing can be repeated without crawling again.
 *
 * Layout:
 * <pre>
 * data/raw-pages/fountain-tire/index.tsv            one line per saved page
 * data/raw-pages/fountain-tire/3f2a9c1b7d4e8a60.html
 * </pre>
 * index.tsv columns (tab-separated): file, url, depth, status, contentType, fetchedAt.
 * File names come from a hash of the URL, so the same URL always maps to the same file.
 */
public class RawPageStore {

    static final String INDEX_FILE = "index.tsv";
    private static final Logger LOG = Logger.getLogger(RawPageStore.class.getName());
    /** Only file names we generated are read back, so index.tsv cannot point outside the folder. */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[0-9a-f]{16}\\.html");

    private final Path baseDir;

    public RawPageStore(Path baseDir) {
        if (baseDir == null) {
            throw new InvalidInputException("Raw page folder is required.");
        }
        this.baseDir = baseDir;
    }

    /** Folder for one source, e.g. "Fountain Tire" -> data/raw-pages/fountain-tire. */
    public Path sourceDir(String sourceName) {
        return baseDir.resolve(slug(sourceName));
    }

    /**
     * Writes the page's HTML and appends a line to the source's index.
     * Saving the same URL again overwrites the HTML file.
     *
     * @return path of the HTML file
     * @throws StorageException if the folder or files cannot be written
     */
    public Path save(CrawledPage page) throws StorageException {
        if (page == null) {
            throw new InvalidInputException("Cannot save a null page.");
        }
        Path dir = sourceDir(page.sourceName());
        String fileName = fileNameFor(page.url());
        String indexLine = String.join("\t",
                fileName,
                singleLine(page.url()),
                String.valueOf(page.depth()),
                String.valueOf(page.statusCode()),
                singleLine(page.contentType()),
                page.fetchedAt().toString()) + System.lineSeparator();
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName), page.html(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(INDEX_FILE), indexLine, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new StorageException("Could not save " + page.url() + " in " + dir + ": " + e.getMessage(), e);
        }
        return dir.resolve(fileName);
    }

    /**
     * Loads all saved pages of one source in the order they were first saved.
     * Damaged index lines and missing HTML files are skipped with a warning.
     *
     * @return pages, or an empty list if nothing was saved for this source
     * @throws StorageException if the index exists but cannot be read
     */
    public List<CrawledPage> loadSource(String sourceName) throws StorageException {
        Path dir = sourceDir(sourceName);
        Path index = dir.resolve(INDEX_FILE);
        if (!Files.exists(index)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Could not read " + index + ": " + e.getMessage(), e);
        }

        // Same URL saved twice: keep one entry (LinkedHashMap keeps first-saved order)
        Map<String, CrawledPage> byUrl = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            CrawledPage page = readIndexLine(dir, sourceName, lines.get(i), i + 1);
            if (page != null) {
                byUrl.put(page.url(), page);
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    /** Parses one index line and loads its HTML; null (with a warning) if anything is wrong. */
    private static CrawledPage readIndexLine(Path dir, String sourceName, String line, int lineNumber) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 6 || !SAFE_FILE_NAME.matcher(parts[0]).matches()) {
            LOG.warning(() -> dir.resolve(INDEX_FILE) + " line " + lineNumber + " is damaged; skipped.");
            return null;
        }
        Path htmlFile = dir.resolve(parts[0]);
        try {
            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            return new CrawledPage(parts[1], sourceName, Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]), parts[4], html, Instant.parse(parts[5]));
        } catch (IOException e) {
            LOG.warning(() -> "Missing or unreadable file " + htmlFile + "; skipped.");
        } catch (NumberFormatException | DateTimeParseException | InvalidInputException e) {
            LOG.warning(() -> dir.resolve(INDEX_FILE) + " line " + lineNumber + " has bad values; skipped.");
        }
        return null;
    }

    /** "Fountain Tire" -> "fountain-tire"; never empty. */
    static String slug(String text) {
        if (text == null) {
            return "unknown";
        }
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "unknown" : slug;
    }

    /** First 16 hex digits of the URL's SHA-256 hash, plus ".html". */
    static String fileNameFor(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8) + ".html";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }

    /** Tabs and line breaks would break the index format. */
    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("[\\t\\r\\n]+", " ");
    }
}

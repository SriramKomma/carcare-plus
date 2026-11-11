package ca.carcareplus.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Logger;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;

/**
 * Search frequency feature: counts how often each word has been searched for,
 * and remembers the counts between runs in a small text file.
 *
 * Data structure: HashMap&lt;term, count&gt;. Recording a search happens far more
 * often than showing the statistics, so recording must be cheap: O(1) on average.
 * Sorting is only done when the user asks for it:
 *   topSearches(k)   O(u log k) with the TopK heap (u = distinct terms)
 *   history()        O(u log u) by copying into a TreeMap (alphabetical)
 *
 * File format (data/search-history.txt), one term per line:  term TAB count
 */
public class SearchFrequency {

    private static final Logger LOG = Logger.getLogger(SearchFrequency.class.getName());

    /** A term and how many times it was searched. */
    public record TermCount(String term, int count) {
    }

    private final Path file;
    private final Map<String, Integer> counts = new HashMap<>();

    /** @param file where the counts are saved, e.g. data/search-history.txt */
    public SearchFrequency(Path file) {
        if (file == null) {
            throw new InvalidInputException("Search history file path is required.");
        }
        this.file = file;
    }

    // ------------------------------------------------------------- counting

    /**
     * Counts one search for each term. Terms are normalized with the Tokenizer;
     * stop words and empty terms are not counted.
     *
     * @return the terms that were counted
     */
    public List<String> recordAll(Collection<String> terms) {
        List<String> recorded = new ArrayList<>();
        if (terms != null) {
            for (String term : terms) {
                String normalized = Tokenizer.normalizeTerm(term);
                if (normalized != null) {
                    counts.merge(normalized, 1, Integer::sum);
                    recorded.add(normalized);
                }
            }
        }
        return recorded;
    }

    /** Counts one search of a single term (same rules as recordAll). */
    public boolean record(String term) {
        return !recordAll(List.of(term == null ? "" : term)).isEmpty();
    }

    /** How often the term was searched; 0 if never. */
    public int count(String term) {
        String normalized = Tokenizer.normalizeTerm(term);
        return normalized == null ? 0 : counts.getOrDefault(normalized, 0);
    }

    /** The k most searched terms, most first; equal counts alphabetically. */
    public List<TermCount> topSearches(int k) {
        List<TermCount> all = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            all.add(new TermCount(entry.getKey(), entry.getValue()));
        }
        Comparator<TermCount> mostFirst = Comparator.comparingInt(TermCount::count).reversed()
                .thenComparing(TermCount::term);
        return TopK.best(all, k, mostFirst);
    }

    /** Every searched term with its count, alphabetically. */
    public SortedMap<String, Integer> history() {
        return new TreeMap<>(counts);
    }

    public int distinctTerms() {
        return counts.size();
    }

    // ----------------------------------------------------------- persistence

    /**
     * Replaces the counts in memory with the saved ones. A missing file means no
     * searches yet. Damaged lines are skipped with a warning, so one bad line
     * never loses the whole history.
     *
     * @throws StorageException only if the file exists but cannot be read
     */
    public void load() throws StorageException {
        counts.clear();
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot read search history " + file + ": " + e.getMessage(), e);
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (!loadLine(line)) {
                int lineNumber = i + 1;
                LOG.warning(() -> file + " line " + lineNumber + " is damaged; skipped.");
            }
        }
    }

    private boolean loadLine(String line) {
        String[] parts = line.split("\t");
        if (parts.length != 2) {
            return false;
        }
        String term = Tokenizer.normalizeTerm(parts[0]);
        int count;
        try {
            count = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (term == null || count <= 0) {
            return false;
        }
        counts.merge(term, count, Integer::sum);
        return true;
    }

    /**
     * Writes all counts, alphabetically. Written to a temporary file first and then
     * moved into place, so a crash cannot leave half a file.
     */
    public void save() throws StorageException {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> entry : history().entrySet()) {
            text.append(entry.getKey()).append('\t').append(entry.getValue()).append(System.lineSeparator());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("Could not save search history to " + file + ": " + e.getMessage(), e);
        }
    }
}

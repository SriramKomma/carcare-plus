package ca.carcareplus.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.search.InvertedIndex;
import ca.carcareplus.search.Tokenizer;

/**
 * Builds the word list (word -> how often it occurs) used by word completion and
 * spell checking.
 *
 * The assignment asks for the spell-check vocabulary to come from "all words in
 * the text files", so fromTextFiles reads the .txt files DocumentStorage writes.
 * fromIndex gives the same words from the inverted index (faster at start-up,
 * because the index already exists); a test checks both agree.
 */
public final class Vocabulary {

    private Vocabulary() {
        // static helper methods only
    }

    /**
     * Counts every term in every .txt file of a folder (e.g. data/parsed-pages/texts).
     * A missing folder gives an empty vocabulary.
     *
     * @throws StorageException if the folder or a file cannot be read
     */
    public static Map<String, Integer> fromTextFiles(Path dir) throws StorageException {
        Map<String, Integer> counts = new HashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.unmodifiableMap(counts);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path file : files) {
                for (String term : Tokenizer.tokenize(Files.readString(file, StandardCharsets.UTF_8))) {
                    counts.merge(term, 1, Integer::sum);
                }
            }
        } catch (IOException e) {
            throw new StorageException("Cannot read text files in " + dir + ": " + e.getMessage(), e);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Every indexed term with its total number of occurrences. */
    public static Map<String, Integer> fromIndex(InvertedIndex index) {
        Map<String, Integer> counts = new HashMap<>();
        for (String term : index.vocabulary()) {
            counts.put(term, index.totalFrequency(term));
        }
        return Collections.unmodifiableMap(counts);
    }
}

package ca.carcareplus.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.query.Trie.WordFrequency;
import ca.carcareplus.search.Tokenizer;

/**
 * Word completion feature: suggests how to finish what the user is typing.
 *
 * Only the LAST word is completed; earlier words are kept, so
 * "battery rep" -> "battery replacement", "battery repair". Suggestions come
 * from words that really occur in the crawled pages, most frequent first.
 */
public class WordCompleter {

    private final Trie trie;

    /** @param vocabulary word -> frequency, from Vocabulary */
    public WordCompleter(Map<String, Integer> vocabulary) {
        if (vocabulary == null) {
            throw new InvalidInputException("WordCompleter needs a vocabulary.");
        }
        this.trie = Trie.of(vocabulary);
    }

    /**
     * @param input what the user has typed so far
     * @param k     most suggestions wanted
     * @return completed inputs, best first; empty if the input is blank, ends with
     *         a space (the last word is finished), or nothing matches
     */
    public List<String> complete(String input, int k) {
        if (input == null || input.isBlank() || Character.isWhitespace(input.charAt(input.length() - 1))) {
            return List.of();
        }
        String text = Tokenizer.simplify(input).trim();
        int lastSpace = text.lastIndexOf(' ');
        String before = lastSpace < 0 ? "" : text.substring(0, lastSpace + 1).replaceAll("\\s+", " ");
        String prefix = text.substring(lastSpace + 1).replaceAll("[^a-z0-9]", "");
        if (prefix.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (WordFrequency candidate : trie.wordsWithPrefix(prefix, k)) {
            result.add(before + candidate.word());
        }
        return result;
    }

    /** The underlying trie (for showing statistics in the demo). */
    public Trie trie() {
        return trie;
    }
}

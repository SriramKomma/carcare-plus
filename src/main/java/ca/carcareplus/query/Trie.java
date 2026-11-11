package ca.carcareplus.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.search.TopK;

/**
 * Prefix tree for word completion.
 *
 * Words with the same beginning share nodes, so "brake", "brakes" and "bracket"
 * store "bra" only once:
 * <pre>
 *   root - b - r - a - k - e*  - s*
 *                    \ c - k - e - t*        (* = a word ends here)
 * </pre>
 * Costs (L = word length, P = prefix length, S = nodes below the prefix):
 *   insert / contains         O(L)
 *   wordsWithPrefix(p, k)     O(P) to walk to the prefix + O(S log k) to collect the best k
 * A plain list of words would need O(W * P) to test every one of W words.
 */
public class Trie {

    /** One completion candidate. */
    public record WordFrequency(String word, int frequency) {
    }

    /** Most frequent first, then alphabetical (so results are the same every run). */
    static final Comparator<WordFrequency> MOST_FREQUENT_FIRST =
            Comparator.comparingInt(WordFrequency::frequency).reversed()
                    .thenComparing(WordFrequency::word);

    private final TrieNode root = new TrieNode();
    private int wordCount;
    private int nodeCount = 1;

    /** Builds a trie from word -> frequency pairs. */
    public static Trie of(Map<String, Integer> words) {
        Trie trie = new Trie();
        for (Map.Entry<String, Integer> entry : words.entrySet()) {
            trie.insert(entry.getKey(), entry.getValue());
        }
        return trie;
    }

    /**
     * Adds a word (lower-cased). Inserting a word again adds to its frequency.
     *
     * @throws InvalidInputException if the word is blank or frequency is below 1
     */
    public void insert(String word, int frequency) {
        if (word == null || word.isBlank()) {
            throw new InvalidInputException("Cannot insert a blank word into the trie.");
        }
        if (frequency < 1) {
            throw new InvalidInputException("Word frequency must be 1 or more: " + frequency);
        }
        TrieNode node = root;
        for (char c : word.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            TrieNode child = node.children.get(c);
            if (child == null) {
                child = new TrieNode();
                node.children.put(c, child);
                nodeCount++;
            }
            node = child;
        }
        if (!node.isWord) {
            node.isWord = true;
            wordCount++;
        }
        node.frequency += frequency;
    }

    public boolean contains(String word) {
        TrieNode node = find(word);
        return node != null && node.isWord;
    }

    /** Frequency of a whole word; 0 if it is not in the trie. */
    public int frequency(String word) {
        TrieNode node = find(word);
        return node != null && node.isWord ? node.frequency : 0;
    }

    /**
     * The k most frequent words starting with the prefix (the prefix itself
     * counts if it is a word). Blank prefix gives an empty list: completing
     * "nothing" would just list the whole vocabulary.
     */
    public List<WordFrequency> wordsWithPrefix(String prefix, int k) {
        if (prefix == null || prefix.isBlank() || k <= 0) {
            return List.of();
        }
        String start = prefix.trim().toLowerCase(Locale.ROOT);
        TrieNode node = find(start);
        if (node == null) {
            return List.of();
        }
        List<WordFrequency> found = new ArrayList<>();
        collect(node, new StringBuilder(start), found);
        return TopK.best(found, k, MOST_FREQUENT_FIRST);
    }

    /** Number of distinct words. */
    public int size() {
        return wordCount;
    }

    /** Number of nodes including the root (shows how much prefix sharing saves). */
    public int nodeCount() {
        return nodeCount;
    }

    // ---------------------------------------------------------------- helpers

    /** Walks down the trie letter by letter; null if the path does not exist. */
    private TrieNode find(String text) {
        if (text == null) {
            return null;
        }
        TrieNode node = root;
        for (char c : text.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            node = node.children.get(c);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /**
     * Depth-first walk below a node, adding every word found. Recursion depth is
     * at most the longest word (30 characters, see Tokenizer), so it is safe.
     */
    private static void collect(TrieNode node, StringBuilder word, List<WordFrequency> found) {
        if (node.isWord) {
            found.add(new WordFrequency(word.toString(), node.frequency));
        }
        for (Map.Entry<Character, TrieNode> child : node.children.entrySet()) {
            word.append(child.getKey());
            collect(child.getValue(), word, found);
            word.deleteCharAt(word.length() - 1); // undo, so the builder can be reused
        }
    }
}

package ca.carcareplus.query;

import java.util.Map;
import java.util.TreeMap;

/**
 * One node of the Trie: one character position of the words passing through it.
 *
 * children is a TreeMap so that:
 * - any character works (letters, digits), without a fixed 26-slot array;
 * - children are visited in alphabetical order for free.
 * A TreeMap step costs O(log c) for c children; c is at most 36 (a-z, 0-9),
 * so in practice it is a constant.
 */
class TrieNode {

    final Map<Character, TrieNode> children = new TreeMap<>();

    /** True if a whole word ends at this node (not just a prefix of a longer word). */
    boolean isWord;

    /** How often the word ending here occurs in the pages; 0 if isWord is false. */
    int frequency;
}

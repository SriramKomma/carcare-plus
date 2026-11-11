package ca.carcareplus.search;

import java.util.List;

/**
 * Something that can suggest correctly spelled words for an unknown word.
 *
 * SearchEngine only depends on this small interface, not on a concrete spell
 * checker. The real implementation (SpellChecker, edit distance) arrives in
 * Phase 8; until then, and in tests, NONE or a simple lambda can be used.
 */
@FunctionalInterface
public interface SpellingSuggester {

    /** Suggests nothing. */
    SpellingSuggester NONE = (word, max) -> List.of();

    /**
     * @param word an indexed-form word (already tokenized) that no page contains
     * @param max  most suggestions wanted
     * @return suggestions, best first; empty if there are none
     */
    List<String> suggest(String word, int max);
}

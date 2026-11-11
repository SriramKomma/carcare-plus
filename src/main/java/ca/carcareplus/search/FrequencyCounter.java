package ca.carcareplus.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceDocument;

/**
 * Frequency count feature: how often does a word occur on a given web page (URL)?
 *
 * Two ways are offered so they can be compared in the report:
 * - countInDocument: asks the inverted index; the positions are already stored,
 *   so the answer costs O(1) on average.
 * - countByScanning: tokenizes the page text again and counts, O(n) for n tokens.
 *   Same answer, used to check the index and to measure the speed difference.
 */
public class FrequencyCounter {

    private final InvertedIndex index;

    public FrequencyCounter(InvertedIndex index) {
        if (index == null) {
            throw new InvalidInputException("FrequencyCounter needs an index.");
        }
        this.index = index;
    }

    /**
     * @return occurrences of the word on the page; 0 if the page does not contain it
     * @throws InvalidInputException if no indexed page has this URL
     */
    public int countInDocument(String word, String url) {
        return index.termFrequency(word, idOf(url));
    }

    /** Same result as countInDocument, by scanning the page text (for comparison). */
    public int countByScanning(String word, String url) {
        String term = Tokenizer.normalizeTerm(word);
        if (term == null) {
            return 0;
        }
        int count = 0;
        for (String token : allTokens(index.document(idOf(url)))) {
            if (token.equals(term)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The k most frequent words on a page, most frequent first; equal counts are
     * ordered alphabetically so the output is the same every run.
     * Cost: O(n) to count with a HashMap + O(u log k) with the TopK heap (u = distinct words).
     *
     * @throws InvalidInputException if no indexed page has this URL or k is negative
     */
    public Map<String, Integer> topTerms(String url, int k) {
        Map<String, Integer> counts = new HashMap<>();
        for (String token : allTokens(index.document(idOf(url)))) {
            counts.merge(token, 1, Integer::sum);
        }
        Comparator<Map.Entry<String, Integer>> mostFrequentFirst =
                Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey());
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : TopK.best(counts.entrySet(), k, mostFrequentFirst)) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private int idOf(String url) {
        Integer id = index.idOfUrl(url);
        if (id == null) {
            throw new InvalidInputException("No indexed page has the URL '" + url + "'.");
        }
        return id;
    }

    /** Tokens of the same text the index uses: title, description, headings, body. */
    private static List<String> allTokens(ServiceDocument doc) {
        List<String> tokens = new ArrayList<>();
        tokens.addAll(Tokenizer.tokenize(doc.getTitle()));
        tokens.addAll(Tokenizer.tokenize(doc.getDescription()));
        tokens.addAll(Tokenizer.tokenize(String.join(" . ", doc.getHeadings())));
        tokens.addAll(Tokenizer.tokenize(doc.getBodyText()));
        return tokens;
    }
}

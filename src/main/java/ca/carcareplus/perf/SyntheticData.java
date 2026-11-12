package ca.carcareplus.perf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.search.Tokenizer;

/**
 * SYNTHETIC data for the performance experiments: made-up words and documents
 * in large numbers, which a real crawl of a few hundred pages cannot give.
 * Nothing here is crawled or real, and the URLs use the reserved ".invalid" domain.
 *
 * A fixed random seed makes every run produce the same data, so timings from
 * different runs compare like with like.
 */
public final class SyntheticData {

    static final String URL_PREFIX = "https://synthetic.carcareplus.invalid/doc";

    private SyntheticData() {
        // static helper methods only
    }

    /**
     * Distinct made-up words of 4 to 10 lower-case letters. Words the Tokenizer
     * would change or drop (stop words, listed plurals) are skipped, so every word
     * is indexed exactly as generated.
     */
    public static List<String> words(int count, long seed) {
        Random random = new Random(seed);
        Set<String> words = new LinkedHashSet<>();
        while (words.size() < count) {
            int length = 4 + random.nextInt(7);
            StringBuilder word = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                word.append((char) ('a' + random.nextInt(26)));
            }
            String w = word.toString();
            if (w.equals(Tokenizer.normalizeTerm(w))) {
                words.add(w);
            }
        }
        return new ArrayList<>(words);
    }

    /**
     * Word -> frequency, where earlier words are more frequent (word i gets about
     * 1000 / (i + 1)), like real text in which a few words are very common.
     */
    public static Map<String, Integer> frequencies(List<String> words) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            result.put(words.get(i), Math.max(1, 1000 / (i + 1)));
        }
        return result;
    }

    /**
     * Documents with ids 0..count-1, a 3-word title and a body of bodyWords words.
     * Words are drawn with a skew towards the start of the list (random^2), so some
     * words appear in many documents and others in few, as in real pages.
     */
    public static List<ServiceDocument> documents(int count, List<String> vocabulary, int bodyWords, long seed) {
        Random random = new Random(seed);
        List<ServiceDocument> docs = new ArrayList<>(count);
        for (int id = 0; id < count; id++) {
            ServiceDocument doc = new ServiceDocument(URL_PREFIX + id, "Synthetic");
            doc.setId(id);
            doc.setTitle(text(random, vocabulary, 3));
            doc.setBodyText(text(random, vocabulary, bodyWords));
            doc.setTestFixture(true);
            docs.add(doc);
        }
        return docs;
    }

    /** Misspellings: each takes a vocabulary word and changes, adds or removes one letter. */
    public static List<String> typos(List<String> vocabulary, int count, long seed) {
        Random random = new Random(seed);
        List<String> typos = new ArrayList<>(count);
        while (typos.size() < count) {
            StringBuilder word = new StringBuilder(vocabulary.get(random.nextInt(vocabulary.size())));
            int position = random.nextInt(word.length());
            char letter = (char) ('a' + random.nextInt(26));
            switch (random.nextInt(3)) {
                case 0 -> word.setCharAt(position, letter);
                case 1 -> word.insert(position, letter);
                default -> word.deleteCharAt(position);
            }
            String typo = word.toString();
            if (typo.equals(Tokenizer.normalizeTerm(typo))) {
                typos.add(typo);
            }
        }
        return typos;
    }

    /** Prefixes of 2 or 3 letters taken from vocabulary words (so most have completions). */
    public static List<String> prefixes(List<String> vocabulary, int count, long seed) {
        Random random = new Random(seed);
        List<String> prefixes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String word = vocabulary.get(random.nextInt(vocabulary.size()));
            prefixes.add(word.substring(0, 2 + random.nextInt(2)));
        }
        return prefixes;
    }

    private static String text(Random random, List<String> vocabulary, int wordCount) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < wordCount; i++) {
            double r = random.nextDouble();
            text.append(vocabulary.get((int) (r * r * vocabulary.size()))).append(' ');
        }
        return text.toString().trim();
    }
}

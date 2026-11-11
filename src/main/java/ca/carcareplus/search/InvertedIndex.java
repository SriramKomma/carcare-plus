package ca.carcareplus.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceDocument;

/**
 * Positional inverted index (inverted indexing feature).
 *
 * Instead of scanning every page for a word (O(N) for N words in all pages), we
 * build once, for every term, the list of pages that contain it and where:
 * <pre>
 *   "battery" -> { 0: [3, 17, 40],   // document 0, token positions 3, 17 and 40
 *                  5: [0] }
 *   "brake"   -> { 2: [8] }
 * </pre>
 * Structure: HashMap&lt;term, HashMap&lt;docId, ArrayList&lt;position&gt;&gt;&gt;.
 *
 * Each document is indexed as one token sequence: title, then description,
 * then headings, then body. We remember where each part starts, so the ranker can
 * tell whether a hit is in the title (worth more) or the body (worth less).
 *
 * Costs (n = tokens in a document, p = documents containing a term):
 *   addDocument     O(n)   one HashMap update per token
 *   lookup(term)    O(1) average to find the term, O(p) to copy the ids
 *   positions       O(1) average
 *   removeDocument  O(u)   u = distinct terms in that document
 */
public class InvertedIndex {

    /** The parts of a document, in the order they are indexed. */
    public enum Field { TITLE, DESCRIPTION, HEADINGS, BODY }

    /** What the index remembers about one document. */
    private record IndexedDocument(ServiceDocument document, int[] fieldStarts, int length, Set<String> terms) {
    }

    private final Map<String, Map<Integer, List<Integer>>> postings = new HashMap<>();
    private final Map<Integer, IndexedDocument> documents = new HashMap<>();
    private final Map<String, Integer> idByUrl = new HashMap<>();

    // ------------------------------------------------------------- building

    /** Builds an index of many documents. */
    public static InvertedIndex of(Collection<ServiceDocument> docs) {
        InvertedIndex index = new InvertedIndex();
        for (ServiceDocument doc : docs) {
            index.addDocument(doc);
        }
        return index;
    }

    /**
     * Adds a document. If a document with the same id is already indexed, it is
     * replaced (removed first), so re-indexing never counts words twice.
     *
     * @throws InvalidInputException if the document is null or has no id yet
     *         (ids come from DocumentStorage)
     */
    public void addDocument(ServiceDocument doc) {
        if (doc == null || !doc.hasId()) {
            throw new InvalidInputException("Only stored documents (with an id) can be indexed.");
        }
        int id = doc.getId();
        removeDocument(id);

        List<String> tokens = new ArrayList<>();
        int[] fieldStarts = new int[Field.values().length];
        for (Field field : Field.values()) {
            fieldStarts[field.ordinal()] = tokens.size();
            tokens.addAll(Tokenizer.tokenize(textOf(doc, field)));
        }

        Set<String> terms = new HashSet<>();
        for (int position = 0; position < tokens.size(); position++) {
            String term = tokens.get(position);
            terms.add(term);
            postings.computeIfAbsent(term, t -> new HashMap<>())
                    .computeIfAbsent(id, d -> new ArrayList<>())
                    .add(position);
        }
        documents.put(id, new IndexedDocument(doc, fieldStarts, tokens.size(), terms));
        idByUrl.put(doc.getUrl(), id);
    }

    /**
     * Removes a document from the index. Unknown ids are ignored.
     *
     * @return true if the document was indexed
     */
    public boolean removeDocument(int id) {
        IndexedDocument old = documents.remove(id);
        if (old == null) {
            return false;
        }
        for (String term : old.terms()) {
            Map<Integer, List<Integer>> docsWithTerm = postings.get(term);
            docsWithTerm.remove(id);
            if (docsWithTerm.isEmpty()) {
                postings.remove(term); // keep the vocabulary free of words no page has any more
            }
        }
        idByUrl.remove(old.document().getUrl());
        return true;
    }

    // -------------------------------------------------------------- lookups

    /**
     * Ids of documents containing the term. The term is normalized with the
     * Tokenizer first, so "Tyres" finds pages that say "tires".
     *
     * @return sorted ids; empty if the term is unknown or is a stop word
     */
    public Set<Integer> lookup(String term) {
        Map<Integer, List<Integer>> docs = postingsOf(term);
        return docs == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(docs.keySet()));
    }

    /**
     * Documents containing ALL the terms (AND search). Starts from the term with
     * the fewest documents and keeps only ids that every other term also has, so
     * the work is bounded by the shortest list.
     */
    public Set<Integer> lookupAll(Collection<String> terms) {
        List<Set<Integer>> sets = new ArrayList<>();
        for (String term : terms) {
            sets.add(lookup(term));
        }
        if (sets.isEmpty()) {
            return Set.of();
        }
        sets.sort((a, b) -> Integer.compare(a.size(), b.size()));
        Set<Integer> result = new TreeSet<>(sets.get(0));
        for (int i = 1; i < sets.size() && !result.isEmpty(); i++) {
            result.retainAll(sets.get(i));
        }
        return Collections.unmodifiableSet(result);
    }

    /** Documents containing AT LEAST ONE of the terms (OR search). */
    public Set<Integer> lookupAny(Collection<String> terms) {
        Set<Integer> result = new TreeSet<>();
        for (String term : terms) {
            result.addAll(lookup(term));
        }
        return Collections.unmodifiableSet(result);
    }

    /** Token positions of the term in one document, ascending; empty if none. */
    public List<Integer> positions(String term, int docId) {
        Map<Integer, List<Integer>> docs = postingsOf(term);
        List<Integer> positions = docs == null ? null : docs.get(docId);
        return positions == null ? List.of() : Collections.unmodifiableList(positions);
    }

    /** How many times the term occurs in one document. O(1) average. */
    public int termFrequency(String term, int docId) {
        return positions(term, docId).size();
    }

    /** How many times the term occurs in one part (title, body, ...) of a document. */
    public int termFrequency(String term, int docId, Field field) {
        IndexedDocument doc = documents.get(docId);
        if (doc == null) {
            return 0;
        }
        int count = 0;
        for (int position : positions(term, docId)) {
            if (fieldAt(doc, position) == field) {
                count++;
            }
        }
        return count;
    }

    /** Number of documents that contain the term. */
    public int documentFrequency(String term) {
        Map<Integer, List<Integer>> docs = postingsOf(term);
        return docs == null ? 0 : docs.size();
    }

    /** Total occurrences of the term in all documents (used to rank word suggestions). */
    public int totalFrequency(String term) {
        Map<Integer, List<Integer>> docs = postingsOf(term);
        int total = 0;
        if (docs != null) {
            for (List<Integer> positions : docs.values()) {
                total += positions.size();
            }
        }
        return total;
    }

    /**
     * True if the terms appear next to each other, in this order, inside one part of
     * the document, e.g. "brake pads". Checks: for each position i of the first term,
     * is the second term at i+1, the third at i+2, ...? Stop words were removed when
     * indexing, so "brake and pads" also counts as the phrase "brake pads".
     */
    public boolean containsPhrase(int docId, List<String> terms) {
        IndexedDocument doc = documents.get(docId);
        if (doc == null || terms == null || terms.isEmpty()) {
            return false;
        }
        List<List<Integer>> positionLists = new ArrayList<>();
        for (String term : terms) {
            List<Integer> positions = positions(term, docId);
            if (positions.isEmpty()) {
                return false;
            }
            positionLists.add(positions);
        }
        for (int start : positionLists.get(0)) {
            if (phraseStartsAt(doc, positionLists, start)) {
                return true;
            }
        }
        return false;
    }

    private boolean phraseStartsAt(IndexedDocument doc, List<List<Integer>> positionLists, int start) {
        Field field = fieldAt(doc, start);
        for (int i = 1; i < positionLists.size(); i++) {
            int wanted = start + i;
            // binarySearch: position lists are sorted, so this is O(log p) instead of O(p)
            if (Collections.binarySearch(positionLists.get(i), wanted) < 0 || fieldAt(doc, wanted) != field) {
                return false;
            }
        }
        return true;
    }

    // --------------------------------------------------------- document info

    /** The indexed document with this id, or null. */
    public ServiceDocument document(int docId) {
        IndexedDocument doc = documents.get(docId);
        return doc == null ? null : doc.document();
    }

    /** Id of the indexed document with this URL, or null. */
    public Integer idOfUrl(String url) {
        return url == null ? null : idByUrl.get(url.trim());
    }

    /** Number of tokens indexed for a document (0 if unknown). */
    public int documentLength(int docId) {
        IndexedDocument doc = documents.get(docId);
        return doc == null ? 0 : doc.length();
    }

    public int documentCount() {
        return documents.size();
    }

    /** Every indexed term, alphabetically (for word completion and spell checking). */
    public Set<String> vocabulary() {
        return Collections.unmodifiableSet(new TreeSet<>(postings.keySet()));
    }

    // ---------------------------------------------------------------- helpers

    private Map<Integer, List<Integer>> postingsOf(String term) {
        String normalized = Tokenizer.normalizeTerm(term);
        return normalized == null ? null : postings.get(normalized);
    }

    /** Which part of the document a token position belongs to. */
    private static Field fieldAt(IndexedDocument doc, int position) {
        Field result = Field.TITLE;
        for (Field field : Field.values()) {
            if (position >= doc.fieldStarts()[field.ordinal()]) {
                result = field; // the last part that starts at or before the position
            }
        }
        return result;
    }

    private static String textOf(ServiceDocument doc, Field field) {
        return switch (field) {
            case TITLE -> doc.getTitle();
            case DESCRIPTION -> doc.getDescription();
            case HEADINGS -> String.join(" . ", doc.getHeadings());
            case BODY -> doc.getBodyText();
        };
    }
}

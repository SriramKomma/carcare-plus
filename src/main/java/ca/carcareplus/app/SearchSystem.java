package ca.carcareplus.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.query.SpellChecker;
import ca.carcareplus.query.SymptomMatcher;
import ca.carcareplus.query.Vocabulary;
import ca.carcareplus.query.WordCompleter;
import ca.carcareplus.search.FrequencyCounter;
import ca.carcareplus.search.InvertedIndex;
import ca.carcareplus.search.SearchEngine;
import ca.carcareplus.search.SearchFrequency;
import ca.carcareplus.storage.DocumentStorage;

/**
 * Everything the search side needs, built once at start-up:
 * <pre>
 *   documents.json  -> DocumentStorage.loadAll   -> documents
 *   documents       -> InvertedIndex             (inverted indexing)
 *   texts/*.txt     -> Vocabulary                -> SpellChecker  (spell checking)
 *                                                -> WordCompleter (word completion)
 *   search-history  -> SearchFrequency.load      (search frequency)
 *   symptoms.txt    -> SymptomMatcher            ("won't start" -> BATTERY)
 *   all of these    -> SearchEngine              (page ranking, frequency count)
 * </pre>
 * Building is O(N) for N words in all pages. After that, each search only uses
 * the in-memory structures.
 */
public final class SearchSystem {

    /** Symptom rules; read from the working folder like config/categories.txt. */
    public static final Path SYMPTOMS_FILE = Path.of("config", "symptoms.txt");

    private final List<ServiceDocument> documents;
    private final InvertedIndex index;
    private final SpellChecker spellChecker;
    private final WordCompleter completer;
    private final SearchFrequency searchFrequency;
    private final FrequencyCounter frequencyCounter;
    private final SearchEngine engine;
    private final SymptomMatcher symptoms;

    private SearchSystem(List<ServiceDocument> documents, Map<String, Integer> vocabulary,
                         SearchFrequency searchFrequency, SymptomMatcher symptoms) {
        this.documents = documents;
        this.symptoms = symptoms;
        this.index = InvertedIndex.of(documents);
        this.spellChecker = new SpellChecker(vocabulary);
        this.completer = new WordCompleter(vocabulary);
        this.searchFrequency = searchFrequency;
        this.frequencyCounter = new FrequencyCounter(index);
        this.engine = new SearchEngine(index, searchFrequency, spellChecker);
    }

    /**
     * @param parsedDir   folder written by ParsePipeline (documents.json + texts/)
     * @param historyFile search history file (created on first save)
     * Symptom rules come from {@link #SYMPTOMS_FILE}; without that file, problem
     * descriptions simply fall back to a normal word search.
     *
     * @throws StorageException if saved data exists but cannot be read
     */
    public static SearchSystem load(Path parsedDir, Path historyFile) throws StorageException {
        return load(parsedDir, historyFile, SYMPTOMS_FILE);
    }

    /**
     * @param symptomsFile symptoms.txt to use; a missing file means "no symptom rules"
     * @throws StorageException if saved data or the symptoms file exists but cannot be read
     */
    public static SearchSystem load(Path parsedDir, Path historyFile, Path symptomsFile) throws StorageException {
        if (parsedDir == null || historyFile == null || symptomsFile == null) {
            throw new InvalidInputException("Parsed pages folder, history file and symptoms file are required.");
        }
        DocumentStorage storage = new DocumentStorage(parsedDir);
        List<ServiceDocument> documents = storage.loadAll();
        // R8: the spell-check vocabulary comes from the text files
        Map<String, Integer> vocabulary = Vocabulary.fromTextFiles(storage.textDir());
        SearchFrequency frequency = new SearchFrequency(historyFile);
        frequency.load();
        return new SearchSystem(documents, vocabulary, frequency, loadSymptoms(symptomsFile));
    }

    private static SymptomMatcher loadSymptoms(Path file) throws StorageException {
        if (!java.nio.file.Files.exists(file)) {
            return SymptomMatcher.fromLines(List.of());
        }
        try {
            return SymptomMatcher.loadFile(file);
        } catch (CarCareException e) {
            // A broken rules file is a mistake to fix, not something to ignore silently
            throw new StorageException(e.getMessage(), e);
        }
    }

    /** Writes the search counts so they survive a restart. */
    public void saveHistory() throws StorageException {
        searchFrequency.save();
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    public List<ServiceDocument> documents() {
        return documents;
    }

    public InvertedIndex index() {
        return index;
    }

    public SearchEngine engine() {
        return engine;
    }

    public SpellChecker spellChecker() {
        return spellChecker;
    }

    public WordCompleter completer() {
        return completer;
    }

    public SearchFrequency searchFrequency() {
        return searchFrequency;
    }

    public SymptomMatcher symptoms() {
        return symptoms;
    }

    public FrequencyCounter frequencyCounter() {
        return frequencyCounter;
    }
}

package ca.carcareplus.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.models.ServiceDocument;

/**
 * Keeps all parsed documents, gives each one an id, skips duplicates, and saves
 * them to disk so the search engine can start without parsing again.
 *
 * Layout:
 * <pre>
 * data/parsed-pages/documents.json     every document (Gson, pretty-printed)
 * data/parsed-pages/texts/0.txt        plain text of document 0 (title, description,
 * data/parsed-pages/texts/1.txt        headings, body): the "text files" the
 * ...                                  spell checker builds its vocabulary from
 * </pre>
 *
 * Duplicates (checked in addIfNew):
 * - same URL                      -> DUPLICATE_URL       (HashMap lookup, O(1))
 * - same text under another URL   -> DUPLICATE_CONTENT   (HashMap lookup by content hash, O(1))
 *   e.g. "/stores/windsor" and "/stores/windsor/index.html" showing the same page.
 *
 * Typical use:
 * <pre>
 * DocumentStorage storage = new DocumentStorage(Path.of("data", "parsed-pages"));
 * storage.loadAll();                    // what was saved last time (empty on first run)
 * storage.addIfNew(parsedDocument);     // for each newly parsed page
 * storage.saveAll();
 * </pre>
 */
public class DocumentStorage {

    static final String DOCUMENTS_FILE = "documents.json";
    static final String TEXT_DIR = "texts";
    static final int FORMAT_VERSION = 1;

    private static final Logger LOG = Logger.getLogger(DocumentStorage.class.getName());
    private static final Pattern TEXT_FILE_NAME = Pattern.compile("\\d+\\.txt");

    /** Result of addIfNew, so the caller can count and report what happened. */
    public enum AddResult { ADDED, DUPLICATE_URL, DUPLICATE_CONTENT }

    /** The whole documents.json file. */
    record StoredFile(int formatVersion, String savedAt, List<StoredDocument> documents) {
    }

    private final Path dir;
    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()        // readable in an editor and in the demo
            .disableHtmlEscaping()      // keep "&" and "<" as they are instead of &
            .create();

    // LinkedHashMap keeps insertion order, so ids and file order stay stable
    private final Map<String, ServiceDocument> byUrl = new LinkedHashMap<>();
    private final Map<String, String> urlByContentHash = new HashMap<>();
    private int nextId = 0;

    public DocumentStorage(Path dir) {
        if (dir == null) {
            throw new InvalidInputException("Parsed pages folder is required.");
        }
        this.dir = dir;
    }

    // ---------------------------------------------------------------- in memory

    /**
     * Adds a document unless it is a duplicate. A new document gets the next free id
     * (the storage owns ids, so any id already set on the document is replaced).
     */
    public AddResult addIfNew(ServiceDocument doc) {
        if (doc == null) {
            throw new InvalidInputException("Cannot store a null document.");
        }
        if (byUrl.containsKey(doc.getUrl())) {
            return AddResult.DUPLICATE_URL;
        }
        String hash = doc.getContentHash();
        if (!hash.isEmpty() && urlByContentHash.containsKey(hash)) {
            LOG.fine(() -> doc.getUrl() + " has the same text as " + urlByContentHash.get(hash) + "; skipped.");
            return AddResult.DUPLICATE_CONTENT;
        }
        doc.setId(nextId);
        remember(doc);
        return AddResult.ADDED;
    }

    /** All documents in the order they were added. Read-only. */
    public List<ServiceDocument> documents() {
        return Collections.unmodifiableList(new ArrayList<>(byUrl.values()));
    }

    public int size() {
        return byUrl.size();
    }

    /** Folder holding one .txt file per document. */
    public Path textDir() {
        return dir.resolve(TEXT_DIR);
    }

    /** Path of the .txt file for a document id, e.g. texts/7.txt. */
    public Path textFile(int id) {
        return textDir().resolve(id + ".txt");
    }

    // -------------------------------------------------------------------- load

    /**
     * Replaces what is in memory with the contents of documents.json.
     * A missing file is normal on the first run and gives an empty list.
     * A single bad entry (no URL, repeated URL or id) is skipped with a warning.
     *
     * @return the loaded documents
     * @throws StorageException if the file cannot be read or is not valid JSON
     *                          in our format; the message says how to recover
     */
    public List<ServiceDocument> loadAll() throws StorageException {
        byUrl.clear();
        urlByContentHash.clear();
        nextId = 0;

        Path file = dir.resolve(DOCUMENTS_FILE);
        if (!Files.exists(file)) {
            LOG.info(() -> "No saved documents yet (" + file + " not found).");
            return documents();
        }

        StoredFile stored = readFile(file);
        if (stored.documents() == null) {
            return documents();
        }

        Set<String> seenUrls = new HashSet<>();
        Set<Integer> usedIds = new HashSet<>();
        List<ServiceDocument> withoutId = new ArrayList<>();
        int entry = 0;
        for (StoredDocument storedDoc : stored.documents()) {
            entry++;
            ServiceDocument doc;
            try {
                if (storedDoc == null) {
                    throw new InvalidInputException("entry is empty");
                }
                doc = storedDoc.toDocument();
            } catch (InvalidInputException e) {
                warnSkipped(file, entry, e.getMessage());
                continue;
            }
            if (!seenUrls.add(doc.getUrl())) {
                warnSkipped(file, entry, "URL already loaded: " + doc.getUrl());
            } else if (doc.hasId() && !usedIds.add(doc.getId())) {
                warnSkipped(file, entry, "id " + doc.getId() + " already used");
            } else if (!doc.hasId()) {
                withoutId.add(doc); // gets an id after all saved ids are known
            } else {
                remember(doc);
            }
        }
        // Entries without an id (e.g. edited by hand) are numbered after the highest saved id
        for (ServiceDocument doc : withoutId) {
            doc.setId(nextId);
            remember(doc);
        }
        LOG.info(() -> "Loaded " + byUrl.size() + " documents from " + file + ".");
        return documents();
    }

    private StoredFile readFile(Path file) throws StorageException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        StoredFile stored;
        try {
            stored = gson.fromJson(json, StoredFile.class);
        } catch (JsonParseException e) {
            throw damaged(file, "it is not valid JSON (" + e.getMessage() + ")", e);
        }
        if (stored == null) {
            throw damaged(file, "it is empty", null);
        }
        if (stored.formatVersion() != FORMAT_VERSION) {
            throw damaged(file, "format version is " + stored.formatVersion()
                    + " but this program reads version " + FORMAT_VERSION, null);
        }
        return stored;
    }

    private static StorageException damaged(Path file, String reason, Throwable cause) {
        return new StorageException("Saved documents in " + file + " cannot be loaded because " + reason
                + ". Delete the file and run the parser again to rebuild it.", cause);
    }

    private static void warnSkipped(Path file, int entry, String reason) {
        LOG.warning(() -> file + " document #" + entry + " skipped: " + reason + ".");
    }

    // -------------------------------------------------------------------- save

    /**
     * Writes documents.json and one text file per document.
     *
     * documents.json is first written to a temporary file and then moved over the
     * old one, so a crash half-way through never leaves a half-written file behind.
     * Text files of documents that no longer exist are deleted.
     *
     * @throws StorageException if anything cannot be written
     */
    public void saveAll() throws StorageException {
        List<StoredDocument> storedDocs = new ArrayList<>();
        for (ServiceDocument doc : byUrl.values()) {
            storedDocs.add(StoredDocument.from(doc));
        }
        String json = gson.toJson(new StoredFile(FORMAT_VERSION, Instant.now().toString(), storedDocs));

        Path file = dir.resolve(DOCUMENTS_FILE);
        Path temp = dir.resolve(DOCUMENTS_FILE + ".tmp");
        try {
            Files.createDirectories(textDir());
            writeTextFiles();
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            moveReplacing(temp, file);
        } catch (IOException e) {
            throw new StorageException("Could not save documents in " + dir + ": " + e.getMessage(), e);
        }
        LOG.info(() -> "Saved " + byUrl.size() + " documents to " + file + ".");
    }

    private void writeTextFiles() throws IOException {
        Set<String> keep = new HashSet<>();
        for (ServiceDocument doc : byUrl.values()) {
            Path textFile = textFile(doc.getId());
            Files.writeString(textFile, plainText(doc), StandardCharsets.UTF_8);
            keep.add(textFile.getFileName().toString());
        }
        // Remove text files of documents that are gone. Only names we create ("12.txt")
        // are touched, so nothing else a person put in the folder is deleted.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(textDir())) {
            for (Path old : files) {
                String name = old.getFileName().toString();
                if (TEXT_FILE_NAME.matcher(name).matches() && !keep.contains(name)) {
                    Files.delete(old);
                }
            }
        }
    }

    /** Text of one document for the spell checker: title, description, headings, body; one per line. */
    static String plainText(ServiceDocument doc) {
        List<String> lines = new ArrayList<>();
        lines.add(doc.getTitle());
        lines.add(doc.getDescription());
        lines.addAll(doc.getHeadings());
        lines.add(doc.getBodyText());
        lines.removeIf(String::isEmpty);
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // some file systems cannot do an atomic move; a normal replace is the best we can do
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Puts a document (that already has an id) into the lookup maps. */
    private void remember(ServiceDocument doc) {
        byUrl.put(doc.getUrl(), doc);
        if (!doc.getContentHash().isEmpty()) {
            urlByContentHash.putIfAbsent(doc.getContentHash(), doc.getUrl());
        }
        nextId = Math.max(nextId, doc.getId() + 1);
    }
}

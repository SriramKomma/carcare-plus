package ca.carcareplus.visits;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;

/**
 * The saved visit requests, by reference ("CC-1001"). With a file
 * (data/visits/visits.json, git-ignored: it holds drivers' names and phone numbers)
 * every change is written straight away, through a temporary file so a crash
 * never leaves half a file. Without a file (tests) it only keeps them in memory.
 *
 * Not thread-safe: WebServer calls it while holding its shared lock.
 */
public final class VisitBook {

    static final int FIRST_NUMBER = 1001;
    private static final String PREFIX = "CC-";
    private static final Type LIST_OF_REQUESTS = new TypeToken<List<VisitRequest>>() { }.getType();

    private final Path file;
    private final Map<String, VisitRequest> byId = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private int nextNumber = FIRST_NUMBER;

    /** Only in memory. */
    public VisitBook() {
        this.file = null;
    }

    private VisitBook(Path file) {
        this.file = file;
    }

    /** Reads the file if it exists; a missing file means no requests yet. */
    public static VisitBook open(Path file) throws StorageException {
        if (file == null) {
            throw new InvalidInputException("Visits file path is required.");
        }
        VisitBook book = new VisitBook(file);
        if (Files.exists(file)) {
            try {
                List<VisitRequest> list = book.gson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        LIST_OF_REQUESTS);
                if (list != null) {
                    list.stream().filter(r -> r != null && r.id() != null).forEach(book::remember);
                }
            } catch (IOException | JsonParseException e) {
                throw new StorageException("Could not read visit requests from " + file + ": " + e.getMessage(), e);
            }
        }
        return book;
    }

    /** Gives the request the next reference, saves it and returns the saved copy. */
    public VisitRequest add(VisitRequest request) throws StorageException {
        VisitRequest saved = new VisitRequest(PREFIX + nextNumber, request.createdAt(), request.shopUrl(),
                request.shopTitle(), request.shopPhone(), request.shopEmail(), request.slot(), request.slotLabel(),
                request.exactTime(), request.problem(), request.service(), request.vehicle(), request.name(),
                request.phone(), request.email(), request.note(), VisitRequest.REQUESTED);
        remember(saved);
        save();
        return saved;
    }

    /** @return the request, or null if there is none with this reference */
    public VisitRequest get(String id) {
        return id == null ? null : byId.get(id.trim().toUpperCase(Locale.ROOT));
    }

    /** The requests with these references, in the order given; unknown references are skipped. */
    public List<VisitRequest> find(Collection<String> ids) {
        List<VisitRequest> found = new ArrayList<>();
        for (String id : ids) {
            VisitRequest request = get(id);
            if (request != null && !found.contains(request)) {
                found.add(request);
            }
        }
        return found;
    }

    /** Marks the request cancelled (cancelling twice is fine). */
    public VisitRequest cancel(String id) throws StorageException {
        VisitRequest request = get(id);
        if (request == null) {
            throw new InvalidInputException("No visit request " + id + ".");
        }
        if (!request.isCancelled()) {
            request = request.withStatus(VisitRequest.CANCELLED);
            byId.put(request.id(), request);
            save();
        }
        return request;
    }

    public int size() {
        return byId.size();
    }

    private void remember(VisitRequest request) {
        byId.put(request.id(), request);
        if (request.id().startsWith(PREFIX)) {
            try {
                nextNumber = Math.max(nextNumber, Integer.parseInt(request.id().substring(PREFIX.length())) + 1);
            } catch (NumberFormatException e) {
                // a hand-edited reference; it does not affect numbering
            }
        }
    }

    private void save() throws StorageException {
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(new ArrayList<>(byId.values())), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StorageException("Could not save visit requests to " + file + ": " + e.getMessage(), e);
        }
    }
}

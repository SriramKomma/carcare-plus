package ca.carcareplus.crawler;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import ca.carcareplus.crawler.ContactScraper.Contact;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;

/**
 * Saves what ContactScraper found in data/contacts/contacts.json (a JSON list),
 * keyed by the shop's OpenStreetMap URL. Shops already in the file are not visited
 * again, so a stopped run can simply be started again.
 */
public final class ContactStore {

    private static final Type LIST_OF_CONTACTS = new TypeToken<List<Contact>>() { }.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public ContactStore(Path file) {
        if (file == null) {
            throw new InvalidInputException("Contacts file path is required.");
        }
        this.file = file;
    }

    /** Shop URL -> contact; empty if the file does not exist yet. */
    public Map<String, Contact> load() throws StorageException {
        Map<String, Contact> contacts = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return contacts;
        }
        try {
            List<Contact> list = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_OF_CONTACTS);
            if (list != null) {
                for (Contact contact : list) {
                    if (contact != null && contact.shopUrl() != null) {
                        // through the constructor again, so missing lists become empty lists
                        contacts.put(contact.shopUrl(), new Contact(contact.shopUrl(), contact.website(),
                                contact.phones(), contact.emails(), contact.sourcePage(), contact.checkedAt(),
                                contact.note()));
                    }
                }
            }
            return contacts;
        } catch (IOException | JsonParseException e) {
            throw new StorageException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Writes all contacts (to a temporary file first, so a crash never leaves half a file). */
    public void save(Map<String, Contact> contacts) throws StorageException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(new ArrayList<>(contacts.values()), LIST_OF_CONTACTS),
                    StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}

package ca.carcareplus.dispatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.exceptions.StorageException;
import ca.carcareplus.validation.DataValidator;

/**
 * The repairers, read from config/repairers.txt:
 * <pre>
 * id | name | phone | area | specialties
 * sriram | Sriram Komma | 519 555 0101 | Windsor | all
 * </pre>
 * Blank lines and lines starting with # are skipped. A bad line stops loading with
 * an error naming the line, so a typo is found when the server starts, not during
 * the demo. Phones are checked with the Phase 4 regex (DataValidator).
 */
public final class RepairerDirectory {

    /** Short names only, so they are safe in an address (?as=sriram). */
    static final Pattern ID = Pattern.compile("^[a-z0-9-]{1,20}$");
    static final int MAX_TEXT = 60;
    static final String ALL = "All types of repairs";

    private final Map<String, Repairer> byId;

    private RepairerDirectory(Map<String, Repairer> byId) {
        this.byId = byId;
    }

    /** No repairers (the live demo then says so). */
    public static RepairerDirectory empty() {
        return new RepairerDirectory(new LinkedHashMap<>());
    }

    /** A missing file means no repairers; an unreadable or invalid one is an error. */
    public static RepairerDirectory load(Path file) throws StorageException {
        if (!Files.exists(file)) {
            return empty();
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new StorageException("Could not read repairers from " + file + ": " + e.getMessage(), e);
        }
    }

    /** @throws InvalidInputException naming the line number if a line is invalid */
    public static RepairerDirectory parse(List<String> lines) {
        Map<String, Repairer> byId = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int lineNumber = i + 1;
            String[] parts = line.split("\\|", -1);
            if (parts.length != 5) {
                throw bad(lineNumber, "expected 5 parts (id | name | phone | area | specialties), found " + parts.length);
            }
            String id = parts[0].trim().toLowerCase(Locale.ROOT);
            String name = parts[1].trim();
            String phone = DataValidator.normalizePhone(parts[2].trim());
            String area = parts[3].trim();
            String specialties = parts[4].trim();
            if (!ID.matcher(id).matches()) {
                throw bad(lineNumber, "id '" + id + "' must be 1-20 of a-z, 0-9 or -");
            }
            if (byId.containsKey(id)) {
                throw bad(lineNumber, "id '" + id + "' is used twice");
            }
            if (name.isEmpty() || name.length() > MAX_TEXT) {
                throw bad(lineNumber, "name must be 1-" + MAX_TEXT + " characters");
            }
            if (phone == null) {
                throw bad(lineNumber, "'" + parts[2].trim() + "' is not a valid Canadian phone number");
            }
            if (area.isEmpty() || area.length() > MAX_TEXT) {
                throw bad(lineNumber, "area must be 1-" + MAX_TEXT + " characters");
            }
            if (specialties.isEmpty() || specialties.equalsIgnoreCase("all")) {
                specialties = ALL;
            }
            byId.put(id, new Repairer(id, name, phone, area, specialties));
        }
        return new RepairerDirectory(byId);
    }

    private static InvalidInputException bad(int lineNumber, String problem) {
        return new InvalidInputException("repairers.txt line " + lineNumber + ": " + problem + ".");
    }

    /** In file order. */
    public List<Repairer> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    /** @return the repairer, or null if there is none with this id */
    public Repairer get(String id) {
        return id == null ? null : byId.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Who sent an SMS: "+15195550101" (how Twilio writes it) and "(519) 555-0101" are the
     * same number once normalized.
     *
     * @return the repairer with this phone, or null
     */
    public Repairer byPhone(String phone) {
        String number = DataValidator.normalizePhone(phone == null ? "" : phone.trim());
        if (number == null) {
            return null;
        }
        return byId.values().stream().filter(r -> r.phone().equals(number)).findFirst().orElse(null);
    }

    public int size() {
        return byId.size();
    }
}

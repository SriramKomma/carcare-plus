package ca.carcareplus.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.search.Tokenizer;

/**
 * Turns a problem described in the driver's own words ("my car won't start")
 * into the service that fixes it (BATTERY), using rules from config/symptoms.txt.
 *
 * Why: drivers know the symptom, not the name of the repair. The search index
 * only knows service words ("battery", "brake"), so without this step a search
 * for "squealing when I stop" finds nothing.
 *
 * How a symptom matches: both the symptom and the user's text go through the
 * search Tokenizer (lower case, "won't" -> "wont", plurals folded, stop words
 * dropped). The symptom matches when every one of its words is in the user's
 * words, in any order. The user's words go into a HashSet, so each check is O(1).
 *
 * Score: each matched symptom adds its number of words to its category, so the
 * specific "brake pedal soft" (3) beats a vague one-word match (1). Categories
 * are returned best first; equal scores keep the ServiceCategory order.
 *
 * Cost: for S symptoms of at most k words, one call is O(n + S * k) for n typed
 * words -- a few hundred set lookups, instant.
 */
public final class SymptomMatcher {

    /**
     * One category that fits the description.
     *
     * @param category the service that fixes the problem
     * @param score    total words of the matched symptoms (higher = more sure)
     * @param symptoms the symptoms that matched, as written in symptoms.txt
     */
    public record Match(ServiceCategory category, int score, List<String> symptoms) {
        public Match {
            symptoms = List.copyOf(symptoms);
        }
    }

    /** One rule: the text as written (for showing to the user) and its tokens (for matching). */
    private record Symptom(String text, List<String> words) {
    }

    private final Map<ServiceCategory, List<Symptom>> rules;

    private SymptomMatcher(Map<ServiceCategory, List<Symptom>> rules) {
        this.rules = rules;
    }

    // ------------------------------------------------------------- loading rules

    /**
     * Reads rules from a file such as config/symptoms.txt.
     *
     * @throws CarCareException if the file cannot be read or a line is invalid
     */
    public static SymptomMatcher loadFile(Path file) throws CarCareException {
        if (file == null) {
            throw new InvalidInputException("Symptoms file path is required.");
        }
        try {
            return fromLines(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CarCareException("Cannot read symptoms file " + file + ": " + e.getMessage(), e);
        } catch (InvalidInputException e) {
            throw new CarCareException(file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the rules from lines in the symptoms.txt format
     * ("CATEGORY | symptom, symptom, ..."). Blank lines and '#' lines are skipped.
     *
     * @throws InvalidInputException naming the line number if a line is invalid
     */
    public static SymptomMatcher fromLines(List<String> lines) {
        if (lines == null) {
            throw new InvalidInputException("Symptom lines are required.");
        }
        Map<ServiceCategory, List<Symptom>> rules = new EnumMap<>(ServiceCategory.class);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                parseRule(line, rules);
            } catch (InvalidInputException e) {
                throw new InvalidInputException("line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new SymptomMatcher(rules);
    }

    private static void parseRule(String line, Map<ServiceCategory, List<Symptom>> rules) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidInputException("expected 'CATEGORY | symptom, symptom, ...'.");
        }
        ServiceCategory category;
        try {
            category = ServiceCategory.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("unknown category '" + parts[0].trim() + "'.");
        }
        if (rules.containsKey(category)) {
            throw new InvalidInputException("category " + category + " is listed twice.");
        }
        List<Symptom> symptoms = new ArrayList<>();
        for (String raw : parts[1].split(",")) {
            String text = raw.trim();
            if (text.isEmpty()) {
                continue;
            }
            List<String> words = Tokenizer.tokenize(text);
            if (words.isEmpty()) {
                // e.g. "the" -- only stop words, so it would match every description
                throw new InvalidInputException("symptom '" + text + "' has no searchable words.");
            }
            for (Symptom earlier : symptoms) {
                if (earlier.words().equals(words)) {
                    // e.g. "won't start" and "wont start": the Tokenizer makes them the same,
                    // and counting both would double the score
                    throw new InvalidInputException("symptom '" + text + "' is the same as '" + earlier.text() + "'.");
                }
            }
            symptoms.add(new Symptom(text, List.copyOf(words)));
        }
        if (symptoms.isEmpty()) {
            throw new InvalidInputException("category " + category + " has no symptoms.");
        }
        rules.put(category, Collections.unmodifiableList(symptoms));
    }

    // ---------------------------------------------------------------- matching

    /**
     * Categories that fit the description, best first. Empty when nothing matches
     * (the caller then runs a normal word search).
     */
    public List<Match> match(String description) {
        List<Match> matches = new ArrayList<>();
        Set<String> typed = new HashSet<>(Tokenizer.tokenize(description));
        if (typed.isEmpty()) {
            return matches;
        }
        for (Map.Entry<ServiceCategory, List<Symptom>> rule : rules.entrySet()) {
            int score = 0;
            List<String> found = new ArrayList<>();
            for (Symptom symptom : rule.getValue()) {
                if (typed.containsAll(symptom.words())) {
                    score += symptom.words().size();
                    found.add(symptom.text());
                }
            }
            if (score > 0) {
                matches.add(new Match(rule.getKey(), score, found));
            }
        }
        // EnumMap iterates in enum order and sort is stable, so ties keep that order
        matches.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return matches;
    }

    /** The best category, or null when no symptom matches. */
    public ServiceCategory bestCategory(String description) {
        List<Match> matches = match(description);
        return matches.isEmpty() ? null : matches.get(0).category();
    }

    /** Total number of symptoms across all categories (for tests and the stats line). */
    public int symptomCount() {
        int count = 0;
        for (List<Symptom> symptoms : rules.values()) {
            count += symptoms.size();
        }
        return count;
    }
}

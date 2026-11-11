package ca.carcareplus.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.ServiceDocument;
import ca.carcareplus.models.VehicleType;

/**
 * Tags a parsed page with service categories (BATTERY, BRAKES, ...) and vehicle
 * types (EV, GAS), using keyword rules read from config/categories.txt.
 *
 * Scoring: every time a keyword appears in the title or a heading it adds
 * {@value #HEADING_POINTS} points to its category; in the description or body text
 * it adds {@value #BODY_POINTS}. A category is given when its score reaches
 * {@value #MIN_SCORE}. So "Battery Replacement" as a heading is enough, but one
 * passing mention of "battery" deep in the body text is not.
 *
 * Vehicle types come from the categories themselves (ServiceCategory knows which
 * vehicles it applies to): EV_CHARGING -> EV, ENGINE -> GAS, BRAKES -> both.
 * A page with no category gets no vehicle type, which means "relevant to both".
 *
 * Cost: for K keywords and text length n, about O(K * n) per page.
 */
public class ServiceCategorizer {

    static final int HEADING_POINTS = 3;
    static final int BODY_POINTS = 1;
    static final int MIN_SCORE = 2;

    /** Keywords per category, already in lower case and normalized like page text. */
    private final Map<ServiceCategory, List<String>> keywords;

    private ServiceCategorizer(Map<ServiceCategory, List<String>> keywords) {
        this.keywords = keywords;
    }

    // ------------------------------------------------------------- loading rules

    /**
     * Reads rules from a file such as config/categories.txt.
     *
     * @throws CarCareException if the file cannot be read or a line is invalid
     */
    public static ServiceCategorizer loadFile(Path file) throws CarCareException {
        if (file == null) {
            throw new InvalidInputException("Categories file path is required.");
        }
        try {
            return fromLines(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CarCareException("Cannot read categories file " + file + ": " + e.getMessage(), e);
        } catch (InvalidInputException e) {
            throw new CarCareException(file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the rules from lines in the categories.txt format.
     * Blank lines and lines starting with '#' are skipped.
     *
     * @throws InvalidInputException naming the line number if a line is invalid
     */
    public static ServiceCategorizer fromLines(List<String> lines) {
        // EnumMap: an array indexed by the enum's ordinal, so lookups are O(1)
        Map<ServiceCategory, List<String>> rules = new EnumMap<>(ServiceCategory.class);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                parseRule(line, rules);
            } catch (InvalidInputException e) {
                throw new InvalidInputException("line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new ServiceCategorizer(rules);
    }

    private static void parseRule(String line, Map<ServiceCategory, List<String>> rules) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidInputException("expected 'CATEGORY | keyword, keyword, ...'.");
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
        List<String> words = new ArrayList<>();
        for (String keyword : parts[1].split(",")) {
            String normalized = normalize(keyword);
            if (!normalized.isEmpty() && !words.contains(normalized)) {
                words.add(normalized);
            }
        }
        if (words.isEmpty()) {
            throw new InvalidInputException("category " + category + " has no keywords.");
        }
        rules.put(category, Collections.unmodifiableList(words));
    }

    // ---------------------------------------------------------------- tagging

    /**
     * Works out the categories of a document (does not change the document).
     */
    public Set<ServiceCategory> categorize(ServiceDocument document) {
        Map<ServiceCategory, Integer> scores = score(document);
        Set<ServiceCategory> result = EnumSet.noneOf(ServiceCategory.class);
        for (Map.Entry<ServiceCategory, Integer> entry : scores.entrySet()) {
            if (entry.getValue() >= MIN_SCORE) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Sets the document's categories and vehicle types.
     * Called by HTMLParser after the text fields are filled.
     */
    public void tag(ServiceDocument document) {
        Set<ServiceCategory> categories = categorize(document);
        Set<VehicleType> vehicles = EnumSet.noneOf(VehicleType.class);
        for (ServiceCategory category : categories) {
            vehicles.addAll(category.vehicleTypes());
        }
        document.setCategories(categories);
        document.setVehicleTypes(vehicles);
    }

    /**
     * Points per category (only categories with at least one hit are included).
     * Package-private so tests can check the arithmetic.
     */
    Map<ServiceCategory, Integer> score(ServiceDocument document) {
        // The source's own name is removed first: otherwise every page of
        // "Fountain Tire" would score for FLAT_TIRE because of the word "Tire".
        String brand = normalize(document.getSourceName());
        String headingText = withoutBrand(
                normalize(document.getTitle() + " . " + String.join(" . ", document.getHeadings())), brand);
        String bodyText = withoutBrand(
                normalize(document.getDescription() + " . " + document.getBodyText()), brand);

        Map<ServiceCategory, Integer> scores = new EnumMap<>(ServiceCategory.class);
        for (Map.Entry<ServiceCategory, List<String>> rule : keywords.entrySet()) {
            int points = 0;
            for (String keyword : rule.getValue()) {
                points += HEADING_POINTS * countWholeWord(headingText, keyword);
                points += BODY_POINTS * countWholeWord(bodyText, keyword);
            }
            if (points > 0) {
                scores.put(rule.getKey(), points);
            }
        }
        return scores;
    }

    /** Read-only view of the loaded rules (used by tests and the demo). */
    public Map<ServiceCategory, List<String>> rules() {
        return Collections.unmodifiableMap(keywords);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Lower case; every character that is not a letter or digit becomes a space;
     * runs of spaces become one space; one space added at both ends.
     * Example: "Brake-Pads, Rotors!" -> " brake pads rotors ".
     * The padding lets countWholeWord search for " keyword " safely.
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String words = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return words.isEmpty() ? "" : " " + words + " ";
    }

    /**
     * Counts whole-word occurrences of keyword in text; both come from normalize(),
     * so " brake " matches inside " rear brake pads " but not inside " brakes ".
     */
    static int countWholeWord(String text, String keyword) {
        if (keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = text.indexOf(keyword);
        while (from >= 0) {
            count++;
            // keyword ends with a space; step back one so the next word can share it
            from = text.indexOf(keyword, from + keyword.length() - 1);
        }
        return count;
    }

    private static String withoutBrand(String text, String brand) {
        // brand = " fountain tire "; replacing with " " keeps neighbouring words apart.
        // A loop, because two brand names in a row share a space and one replace() pass
        // would miss the second. Each pass makes the text shorter, so it always ends.
        if (brand.isEmpty() || brand.equals(" unknown ")) {
            return text;
        }
        String result = text;
        while (result.contains(brand)) {
            result = result.replace(brand, " ");
        }
        return result;
    }
}

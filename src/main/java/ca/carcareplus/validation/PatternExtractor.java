package ca.carcareplus.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

import ca.carcareplus.models.ServiceDocument;

/**
 * Regex pattern finding (one of the project features): pulls phone numbers,
 * emails, postal codes and EV charger details out of a page's text.
 *
 * How each extract method works:
 * 1. matcher.find() in a loop finds every place in the text that fits the pattern.
 * 2. Each match is re-checked and normalized with DataValidator, so
 *    "604.859.2555" and "(604) 859-2555" become the same string.
 * 3. A LinkedHashSet removes duplicates but keeps the order of first appearance
 *    (a page usually shows its main phone number first).
 *
 * Cost: one pass over the text per pattern, roughly O(n) for text length n.
 */
public final class PatternExtractor {

    /** File endings that look like an email's top-level domain, e.g. "logo@2x.png". */
    private static final Set<String> IMAGE_ENDINGS = Set.of("png", "jpg", "jpeg", "gif", "svg", "webp");

    private PatternExtractor() {
        // static helper methods only
    }

    /** @return phone numbers as "(XXX) XXX-XXXX", no duplicates, in order of appearance */
    public static List<String> extractPhones(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = RegexPatterns.PHONE_IN_TEXT.matcher(text);
            while (m.find()) {
                found.add(DataValidator.formatPhone(m));
            }
        }
        return new ArrayList<>(found);
    }

    /** @return emails in lower case, no duplicates; image names like "logo@2x.png" are skipped */
    public static List<String> extractEmails(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = RegexPatterns.EMAIL_IN_TEXT.matcher(text);
            while (m.find()) {
                String email = DataValidator.normalizeEmail(m.group());
                if (email != null && !looksLikeImageFile(email)) {
                    found.add(email);
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** @return postal codes as "A1A 1A1", no duplicates */
    public static List<String> extractPostalCodes(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = RegexPatterns.POSTAL_CODE_IN_TEXT.matcher(text);
            while (m.find()) {
                found.add(m.group(1) + " " + m.group(2));
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * @return connector types with one standard spelling each:
     *         "CCS", "CHAdeMO", "J1772" or "NACS"; no duplicates
     */
    public static List<String> extractConnectorTypes(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = RegexPatterns.CONNECTOR_IN_TEXT.matcher(text);
            while (m.find()) {
                found.add(standardConnectorName(m.group(1)));
            }
        }
        return new ArrayList<>(found);
    }

    /** @return charging power ratings such as "350 kW", no duplicates */
    public static List<String> extractPowerRatings(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = RegexPatterns.POWER_KW_IN_TEXT.matcher(text);
            while (m.find()) {
                found.add(Integer.parseInt(m.group(1)) + " kW"); // parseInt drops leading zeros
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * Fills the contact fields of a parsed document from its body text.
     * The province is taken from the first postal code, unless the parser
     * already set one. Called by the HTML parser pipeline (Phase 5).
     */
    public static void fillContactDetails(ServiceDocument document) {
        String text = document.getBodyText();
        document.setPhones(extractPhones(text));
        document.setEmails(extractEmails(text));
        List<String> postalCodes = extractPostalCodes(text);
        document.setPostalCodes(postalCodes);
        if (document.getProvince().isEmpty() && !postalCodes.isEmpty()) {
            document.setProvince(DataValidator.provinceForPostalCode(postalCodes.get(0)));
        }
    }

    private static String standardConnectorName(String raw) {
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CCS")) {
            return "CCS";
        }
        if (upper.equals("CHADEMO")) {
            return "CHAdeMO";
        }
        if (upper.contains("1772")) {
            return "J1772";
        }
        return "NACS";
    }

    private static boolean looksLikeImageFile(String email) {
        String ending = email.substring(email.lastIndexOf('.') + 1);
        return IMAGE_ENDINGS.contains(ending);
    }
}

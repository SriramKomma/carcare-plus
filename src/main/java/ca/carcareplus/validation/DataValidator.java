package ca.carcareplus.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Regex data validation (one of the project features).
 *
 * "isValid..." methods answer yes/no and never throw, so they are safe to call on
 * anything (null, empty, very long text). "normalize..." methods turn a valid
 * value into one standard spelling so duplicates can be detected, e.g.
 * "604.859.2555" and "+1 (604) 859-2555" both become "(604) 859-2555".
 *
 * All patterns live in RegexPatterns. Validation uses matcher.matches(), which
 * requires the WHOLE string to fit the pattern.
 */
public final class DataValidator {

    private DataValidator() {
        // static helper methods only
    }

    // -------------------------------------------------------------- validation

    public static boolean isValidPostalCode(String text) {
        return fits(RegexPatterns.POSTAL_CODE_EXACT, text);
    }

    public static boolean isValidCanadianPhone(String text) {
        return fits(RegexPatterns.PHONE_EXACT, text);
    }

    /**
     * Email check: the regex, plus two rules that are awkward to write as regex:
     * no ".." anywhere, and the local part must not start or end with ".".
     */
    public static boolean isValidEmail(String text) {
        if (!fits(RegexPatterns.EMAIL_EXACT, text)) {
            return false;
        }
        String email = text.trim();
        String local = email.substring(0, email.indexOf('@'));
        return !email.contains("..") && !local.startsWith(".") && !local.endsWith(".");
    }

    /**
     * Two-step URL check.
     * Step 1 (regex) rejects obvious junk quickly: must start with http:// or https://
     * and contain no spaces. Step 2 (java.net.URI) checks the parts properly,
     * e.g. a port above 65535 or a host like "-bad-.ca".
     */
    public static boolean isValidUrl(String text) {
        if (!fits(RegexPatterns.URL_EXACT, text)) {
            return false;
        }
        try {
            URI uri = new URI(text.trim());
            String host = uri.getHost();
            int port = uri.getPort();
            return host != null && host.contains(".") && port <= 65535;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Checks and cleans what the user typed in the search box.
     *
     * @return the query trimmed, with runs of spaces collapsed to one space
     * @throws InvalidInputException with a message that can be shown to the user
     */
    public static String validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new InvalidInputException("Please enter something to search for.");
        }
        String cleaned = query.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > RegexPatterns.MAX_QUERY_LENGTH) {
            throw new InvalidInputException("Search text is too long (maximum "
                    + RegexPatterns.MAX_QUERY_LENGTH + " characters).");
        }
        if (!RegexPatterns.QUERY_EXACT.matcher(cleaned).matches()) {
            throw new InvalidInputException(
                    "Search text may only contain letters, digits, spaces, ' and -.");
        }
        return cleaned;
    }

    /**
     * Checks the optional location the user typed: a postal code ("n9b3p4") or a
     * place name ("Windsor", "Abbotsford, BC").
     *
     * @return "" for blank input (search anywhere), the postal code in "A1A 1A1"
     *         style, or the place name trimmed with runs of spaces collapsed
     * @throws InvalidInputException with a message that can be shown to the user
     */
    public static String validateLocation(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        String cleaned = location.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > RegexPatterns.MAX_LOCATION_LENGTH) {
            throw new InvalidInputException("Location is too long (maximum "
                    + RegexPatterns.MAX_LOCATION_LENGTH + " characters).");
        }
        // Digits only appear in postal codes, so "N9B 3P" is a broken postal code, not a city
        if (cleaned.chars().anyMatch(Character::isDigit)) {
            String postalCode = normalizePostalCode(cleaned);
            if (postalCode == null) {
                throw new InvalidInputException("'" + cleaned
                        + "' is not a valid Canadian postal code (example: N9B 3P4).");
            }
            return postalCode;
        }
        if (!RegexPatterns.PLACE_NAME_EXACT.matcher(cleaned).matches()) {
            throw new InvalidInputException(
                    "Location may only contain letters, spaces and . , ' - (or be a postal code).");
        }
        return cleaned;
    }

    // ----------------------------------------------------------- normalization

    /**
     * @return the phone as "(XXX) XXX-XXXX", or null if it is not a valid phone number
     */
    public static String normalizePhone(String text) {
        if (!isValidCanadianPhone(text)) {
            return null;
        }
        Matcher m = RegexPatterns.PHONE_EXACT.matcher(text.trim());
        m.matches(); // always true here; needed so the groups are filled
        return formatPhone(m);
    }

    /**
     * Builds "(XXX) XXX-XXXX" from a matcher of PHONE_EXACT or PHONE_IN_TEXT.
     * The area code is in group 1 (written with brackets) or group 2 (without).
     */
    static String formatPhone(Matcher m) {
        String area = m.group(1) != null ? m.group(1) : m.group(2);
        return "(" + area + ") " + m.group(3) + "-" + m.group(4);
    }

    /**
     * @return the postal code in Canada Post style "A1A 1A1", or null if invalid
     */
    public static String normalizePostalCode(String text) {
        if (!isValidPostalCode(text)) {
            return null;
        }
        Matcher m = RegexPatterns.POSTAL_CODE_EXACT.matcher(text.trim());
        m.matches();
        return (m.group(1) + " " + m.group(2)).toUpperCase(Locale.ROOT);
    }

    /** @return the email in lower case, or null if invalid */
    public static String normalizeEmail(String text) {
        return isValidEmail(text) ? text.trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Province from the first letter of a postal code (Canada Post's
     * "forward sortation area" letters). X is shared by two territories:
     * X0A, X0B and X0C are Nunavut, the other X codes are Northwest Territories.
     *
     * @return two-letter province code such as "ON", or "" if the code is invalid
     */
    public static String provinceForPostalCode(String postalCode) {
        String code = normalizePostalCode(postalCode);
        if (code == null) {
            return "";
        }
        return switch (code.charAt(0)) {
            case 'A' -> "NL";
            case 'B' -> "NS";
            case 'C' -> "PE";
            case 'E' -> "NB";
            case 'G', 'H', 'J' -> "QC";
            case 'K', 'L', 'M', 'N', 'P' -> "ON";
            case 'R' -> "MB";
            case 'S' -> "SK";
            case 'T' -> "AB";
            case 'V' -> "BC";
            case 'X' -> code.startsWith("X0A") || code.startsWith("X0B") || code.startsWith("X0C")
                    ? "NU" : "NT";
            case 'Y' -> "YT";
            default -> ""; // cannot happen: the regex only allows the letters above
        };
    }

    // ----------------------------------------------------------------- helpers

    /** Null-safe, length-capped whole-string match. Leading/trailing spaces are ignored. */
    private static boolean fits(Pattern pattern, String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > RegexPatterns.MAX_VALUE_LENGTH) {
            return false;
        }
        return pattern.matcher(trimmed).matches();
    }
}

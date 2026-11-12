package ca.carcareplus.validation;

import java.util.regex.Pattern;

/**
 * Every regular expression used by CarCare+, compiled once and shared.
 *
 * Why one class?
 * - Pattern.compile is slow compared with matching, so each pattern is built
 *   only once (static final) and reused for every page and every user input.
 * - DataValidator and PatternExtractor use the same rules, so a fix in one
 *   place fixes both.
 *
 * Two versions of most patterns:
 * - "..._EXACT" patterns are used with matcher.matches(): the WHOLE string must
 *   fit (validation of one value the user typed).
 * - "..._IN_TEXT" patterns are used with matcher.find() in a loop: they look for
 *   the value anywhere inside a longer text (pattern finding in page text).
 *
 * Safety against catastrophic backtracking (ReDoS): no nested quantifiers like
 * (a+)+, every repetition has an upper bound ({1,64}), and callers cap the input
 * length before matching. With these rules Java's matcher runs in roughly O(n).
 */
public final class RegexPatterns {

    /** Longest single value (phone, email, URL, postal code) we try to validate. */
    public static final int MAX_VALUE_LENGTH = 300;

    /** Longest search query the user may type. */
    public static final int MAX_QUERY_LENGTH = 100;

    // ---------------------------------------------------------------- postal code
    // Canada Post never uses D, F, I, O, Q, U (they look like other characters),
    // and W and Z are never the FIRST letter.
    //   first letter : [ABCEGHJ-NPRSTVXY]
    //   other letters: [ABCEGHJ-NPRSTV-Z]
    // Format: letter digit letter, optional space or dash, digit letter digit.
    private static final String POSTAL_BODY =
            "([ABCEGHJ-NPRSTVXY]\\d[ABCEGHJ-NPRSTV-Z])[ -]?(\\d[ABCEGHJ-NPRSTV-Z]\\d)";

    /** User input such as "n9b3p4" or "N9B 3P4" (case-insensitive). */
    public static final Pattern POSTAL_CODE_EXACT =
            Pattern.compile("^" + POSTAL_BODY + "$", Pattern.CASE_INSENSITIVE);

    /**
     * Postal codes inside page text. Upper case only on purpose: real pages write
     * them in capitals, and case-insensitive matching would also find random
     * lower-case strings such as "a1b2c3" in product codes.
     */
    public static final Pattern POSTAL_CODE_IN_TEXT =
            Pattern.compile("\\b" + POSTAL_BODY + "\\b");

    // ---------------------------------------------------------------------- phone
    // North American Numbering Plan: area code and exchange cannot start with 0 or 1.
    // Optional country code "+1" or "1". Brackets around the area code must be
    // balanced, so there are two alternatives: "(604)" or "604".
    // Groups: 1 = area code in brackets, 2 = area code without brackets,
    //         3 = exchange (3 digits), 4 = line number (4 digits).
    private static final String PHONE_BODY =
            "(?:\\+?1[-.\\s]?)?"
            + "(?:\\(([2-9]\\d{2})\\)|([2-9]\\d{2}))"
            + "[-.\\s]?([2-9]\\d{2})[-.\\s]?(\\d{4})";

    /** One phone number, e.g. "(604) 859-2555", "+1 604.859.2555", "1-833-231-3226". */
    public static final Pattern PHONE_EXACT = Pattern.compile("^" + PHONE_BODY + "$");

    /**
     * Phone numbers inside text. (?<![\d+]) and (?!\d) make sure we do not cut a
     * 10-digit piece out of a longer number such as an order id.
     */
    public static final Pattern PHONE_IN_TEXT =
            Pattern.compile("(?<![\\d+])" + PHONE_BODY + "(?!\\d)");

    // ---------------------------------------------------------------------- email
    // A practical subset of RFC 5322: local part @ domain . top-level domain.
    private static final String EMAIL_BODY =
            "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}";

    public static final Pattern EMAIL_EXACT = Pattern.compile("^" + EMAIL_BODY + "$");

    public static final Pattern EMAIL_IN_TEXT = Pattern.compile("\\b" + EMAIL_BODY + "\\b");

    // ------------------------------------------------------------------------ URL
    /** Step 1 of URL validation; step 2 is parsing with java.net.URI (see DataValidator). */
    public static final Pattern URL_EXACT = Pattern.compile(
            "^https?://[A-Za-z0-9.-]+(:\\d{1,5})?(/\\S*)?$", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------------- query
    /** Letters, digits, spaces, apostrophes and hyphens, 1 to 100 characters. */
    public static final Pattern QUERY_EXACT =
            Pattern.compile("^[A-Za-z0-9 '\\-]{1," + MAX_QUERY_LENGTH + "}$");

    // ------------------------------------------------------------------- location
    /** Longest city / province name the user may type. */
    public static final int MAX_LOCATION_LENGTH = 50;

    /**
     * A place name such as "Windsor", "St. John's", "Abbotsford, BC" or
     * "Trois-Rivieres": letters, spaces and . , ' - only, starting with a letter.
     * Postal codes are checked separately with POSTAL_CODE_EXACT.
     */
    public static final Pattern PLACE_NAME_EXACT =
            Pattern.compile("^[A-Za-z][A-Za-z .,'\\-]{0," + (MAX_LOCATION_LENGTH - 1) + "}$");

    // -------------------------------------------------------- EV pattern finding
    /**
     * EV charging connector names. "CCS1"/"CCS2" and "J-1772" are common spellings.
     * The brand word "Tesla" is NOT matched: pages often say "Tesla Model 3",
     * which is a car, not a connector. Tesla's connector is listed as NACS.
     */
    public static final Pattern CONNECTOR_IN_TEXT = Pattern.compile(
            "\\b(CCS[12]?|CHAdeMO|J-?1772|NACS)\\b", Pattern.CASE_INSENSITIVE);

    /** Charging power such as "350 kW" or "50kW" (2 or 3 digits). Group 1 = number. */
    public static final Pattern POWER_KW_IN_TEXT = Pattern.compile(
            "\\b(\\d{2,3})\\s?kW\\b", Pattern.CASE_INSENSITIVE);

    private RegexPatterns() {
        // only constants; no objects
    }
}

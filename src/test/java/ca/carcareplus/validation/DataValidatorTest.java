package ca.carcareplus.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;

class DataValidatorTest {

    // ------------------------------------------------------------ postal codes

    @ParameterizedTest
    @ValueSource(strings = {"N9B 3P4", "n9b3p4", "N9B-3P4", "V2S 1A1", "  K1A 0B1  ", "X0A 0H0", "Y1A 6A1"})
    void acceptsValidPostalCodes(String code) {
        assertTrue(DataValidator.isValidPostalCode(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "D1A 1A1",   // D never used
        "W1A 1A1",   // W never first
        "Z1A 1A1",   // Z never first
        "N9O 3P4",   // O never used
        "N9B 3U4",   // U never used
        "N9B  3P4",  // two spaces
        "N9B3P",     // too short
        "N9B 3P45",  // too long
        "123 456",
        "N9B 3P4 extra"
    })
    void rejectsInvalidPostalCodes(String code) {
        assertFalse(DataValidator.isValidPostalCode(code));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void nullAndBlankAreNeverValid(String text) {
        assertFalse(DataValidator.isValidPostalCode(text));
        assertFalse(DataValidator.isValidCanadianPhone(text));
        assertFalse(DataValidator.isValidEmail(text));
        assertFalse(DataValidator.isValidUrl(text));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "n9b3p4,  N9B 3P4",
        "N9B-3P4, N9B 3P4",
        "v2s 1a1, V2S 1A1"
    })
    void normalizesPostalCodes(String input, String expected) {
        assertEquals(expected, DataValidator.normalizePostalCode(input));
    }

    @Test
    void normalizingInvalidPostalCodeGivesNull() {
        assertNull(DataValidator.normalizePostalCode("D1A 1A1"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "A1C 5S7, NL", "B3H 1A1, NS", "C1A 1A1, PE", "E1C 1A1, NB",
        "H2X 1Y4, QC", "N9B 3P4, ON", "M5V 2T6, ON", "R3C 1A1, MB",
        "S4P 1A1, SK", "T2P 1A1, AB", "V2S 1A1, BC", "X0A 0H0, NU",
        "X1A 2P3, NT", "Y1A 6A1, YT"
    })
    void derivesProvinceFromFirstLetter(String code, String province) {
        assertEquals(province, DataValidator.provinceForPostalCode(code));
    }

    @Test
    void provinceOfInvalidCodeIsEmpty() {
        assertEquals("", DataValidator.provinceForPostalCode("D1A 1A1"));
        assertEquals("", DataValidator.provinceForPostalCode(null));
    }

    // ------------------------------------------------------------------ phones

    @ParameterizedTest
    @ValueSource(strings = {
        "(604) 859-2555",
        "604-859-2555",
        "604.859.2555",
        "6048592555",
        "+1 604 859 2555",
        "1-833-231-3226",
        "(604)859-2555"
    })
    void acceptsPhoneFormats(String phone) {
        assertTrue(DataValidator.isValidCanadianPhone(phone));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "123-456-7890",    // area code cannot start with 1
        "023-456-7890",    // ... or 0
        "604-159-2555",    // exchange cannot start with 1
        "604-859-255",     // too short
        "604-859-25555",   // too long
        "(604 859-2555",   // unbalanced bracket
        "604) 859-2555",
        "2-604-859-2555",  // country code must be 1
        "phone 604-859-2555"
    })
    void rejectsInvalidPhones(String phone) {
        assertFalse(DataValidator.isValidCanadianPhone(phone));
    }

    @ParameterizedTest(name = "{0} -> (604) 859-2555")
    @ValueSource(strings = {"604.859.2555", "+1 (604) 859-2555", "6048592555", "1-604-859-2555"})
    void normalizesPhones(String phone) {
        assertEquals("(604) 859-2555", DataValidator.normalizePhone(phone));
    }

    @Test
    void normalizingInvalidPhoneGivesNull() {
        assertNull(DataValidator.normalizePhone("123-456-7890"));
    }

    // ------------------------------------------------------------------ emails

    @ParameterizedTest
    @ValueSource(strings = {"info@example.ca", "first.last+tag@sub.example-site.com", "A_B%C@EXAMPLE.CA"})
    void acceptsValidEmails(String email) {
        assertTrue(DataValidator.isValidEmail(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "plainaddress",
        "@example.ca",
        "info@",
        "info@example",       // no top-level domain
        "info@example.c",     // top-level domain too short
        "in fo@example.ca",   // space
        "info@@example.ca",
        "first..last@example.ca",
        ".info@example.ca",
        "info.@example.ca",
        "info@example..ca"
    })
    void rejectsInvalidEmails(String email) {
        assertFalse(DataValidator.isValidEmail(email));
    }

    @Test
    void normalizesEmailToLowerCase() {
        assertEquals("info@example.ca", DataValidator.normalizeEmail("  Info@Example.CA "));
        assertNull(DataValidator.normalizeEmail("not an email"));
    }

    // -------------------------------------------------------------------- URLs

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.fountaintire.com/stores/abbotsford",
        "http://example.ca",
        "https://example.ca:8080/path?q=1",
        "HTTPS://EXAMPLE.CA/"
    })
    void acceptsValidUrls(String url) {
        assertTrue(DataValidator.isValidUrl(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "www.example.ca",              // no scheme
        "ftp://example.ca",            // wrong scheme
        "https://",                    // no host
        "https://localhost",           // host without a dot
        "https://exa mple.ca",         // space
        "https://example.ca:99999/",   // port too large
        "https://-bad-.ca/"            // host may not start with '-'
    })
    void rejectsInvalidUrls(String url) {
        assertFalse(DataValidator.isValidUrl(url));
    }

    // ------------------------------------------------------------------ query

    @Test
    void validQueryIsTrimmedAndSpacesCollapsed() {
        assertEquals("battery replacement", DataValidator.validateQuery("  battery    replacement "));
        assertEquals("driver's side brake-pads", DataValidator.validateQuery("driver's side brake-pads"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void emptyQueryIsRejected(String query) {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> DataValidator.validateQuery(query));
        assertEquals("Please enter something to search for.", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "battery; DROP TABLE", "tires & rims", "café"})
    void queryWithForbiddenCharactersIsRejected(String query) {
        assertThrows(InvalidInputException.class, () -> DataValidator.validateQuery(query));
    }

    @Test
    void queryOfExactly100CharactersIsAcceptedButNot101() {
        assertEquals(100, DataValidator.validateQuery("a".repeat(100)).length());
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> DataValidator.validateQuery("a".repeat(101)));
        assertTrue(e.getMessage().contains("too long"));
    }

    // ------------------------------------------------------------- robustness

    @Test
    void veryLongInputIsRejectedQuickly() {
        String huge = "a".repeat(1_000_000) + "@example.ca";
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertFalse(DataValidator.isValidEmail(huge));
            assertFalse(DataValidator.isValidUrl("https://" + huge));
            assertFalse(DataValidator.isValidPostalCode(huge));
        });
    }
}

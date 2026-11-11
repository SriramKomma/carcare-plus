package ca.carcareplus.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import ca.carcareplus.models.ServiceDocument;

class PatternExtractorTest {

    // ------------------------------------------------------------------ phones

    @Test
    void findsSeveralPhonesInDifferentFormats() {
        String text = "Call (604) 859-2555 or toll free 1-833-231-3226. Fax: 519.555.0100";
        assertEquals(List.of("(604) 859-2555", "(833) 231-3226", "(519) 555-0100"),
                PatternExtractor.extractPhones(text));
    }

    @Test
    void sameNumberWrittenTwoWaysIsKeptOnce() {
        String text = "Phone 604-859-2555. Again: (604) 859-2555 or +1 604.859.2555";
        assertEquals(List.of("(604) 859-2555"), PatternExtractor.extractPhones(text));
    }

    @Test
    void doesNotCutPhonesOutOfLongerNumbers() {
        assertEquals(List.of(), PatternExtractor.extractPhones("Order #4105550142778 confirmed"));
    }

    @Test
    void ignoresNumbersWithInvalidAreaCode() {
        assertEquals(List.of(), PatternExtractor.extractPhones("Ref 123-456-7890"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void noTextGivesEmptyLists(String text) {
        assertEquals(List.of(), PatternExtractor.extractPhones(text));
        assertEquals(List.of(), PatternExtractor.extractEmails(text));
        assertEquals(List.of(), PatternExtractor.extractPostalCodes(text));
        assertEquals(List.of(), PatternExtractor.extractConnectorTypes(text));
        assertEquals(List.of(), PatternExtractor.extractPowerRatings(text));
    }

    @Test
    void textWithoutContactDetailsGivesEmptyLists() {
        String text = "We fix brakes and batteries. Open Monday to Friday.";
        assertEquals(List.of(), PatternExtractor.extractPhones(text));
        assertEquals(List.of(), PatternExtractor.extractEmails(text));
        assertEquals(List.of(), PatternExtractor.extractPostalCodes(text));
    }

    // ------------------------------------------------------------------ emails

    @Test
    void findsEmailsLowerCasedWithoutDuplicates() {
        String text = "Write to Service@Example.ca or service@example.ca, or sales@example.com.";
        assertEquals(List.of("service@example.ca", "sales@example.com"),
                PatternExtractor.extractEmails(text));
    }

    @Test
    void skipsImageFileNamesThatLookLikeEmails() {
        assertEquals(List.of(), PatternExtractor.extractEmails("<img src=\"logo@2x.png\"> icon@3x.webp"));
    }

    // ------------------------------------------------------------ postal codes

    @Test
    void findsPostalCodesAndNormalizesSeparator() {
        String text = "Abbotsford, BC V2S 1A1. Windsor, ON N9B-3P4. Again V2S1A1.";
        assertEquals(List.of("V2S 1A1", "N9B 3P4"), PatternExtractor.extractPostalCodes(text));
    }

    @Test
    void ignoresImpossiblePostalCodesAndLowerCaseLookalikes() {
        assertEquals(List.of(), PatternExtractor.extractPostalCodes("D1A 1A1, W1A 1A1, part a1b2c3"));
    }

    // ---------------------------------------------------------------------- EV

    @Test
    void findsConnectorTypesWithStandardNames() {
        String text = "Connectors: CCS1, ccs, CHAdeMO, J-1772 and NACS. Tesla Model 3 welcome.";
        assertEquals(List.of("CCS", "CHAdeMO", "J1772", "NACS"),
                PatternExtractor.extractConnectorTypes(text));
    }

    @Test
    void findsPowerRatings() {
        String text = "Up to 350 kW (CCS) and 50kW (CHAdeMO). Another 350KW charger. 7 kW is too small.";
        assertEquals(List.of("350 kW", "50 kW"), PatternExtractor.extractPowerRatings(text));
    }

    // --------------------------------------------------------------- fixture page

    @Test
    void extractsContactDetailsFromStorePageFixture() throws IOException {
        String text = readFixture("/text/store-page-fixture.txt");

        assertEquals(List.of("(604) 555-0142", "(800) 555-0199", "(519) 555-0100"),
                PatternExtractor.extractPhones(text));
        assertEquals(List.of("abbotsford.store@example-tire.ca"), PatternExtractor.extractEmails(text));
        assertEquals(List.of("V2S 1A1", "N9B 3P4"), PatternExtractor.extractPostalCodes(text));
    }

    @Test
    void fillContactDetailsSetsFieldsAndProvinceFromFirstPostalCode() throws IOException {
        ServiceDocument doc = new ServiceDocument("https://www.example-tire.ca/stores/abbotsford", "Example Tire");
        doc.setBodyText(readFixture("/text/store-page-fixture.txt"));

        PatternExtractor.fillContactDetails(doc);

        assertEquals(3, doc.getPhones().size());
        assertEquals(List.of("abbotsford.store@example-tire.ca"), doc.getEmails());
        assertEquals(List.of("V2S 1A1", "N9B 3P4"), doc.getPostalCodes());
        assertEquals("BC", doc.getProvince());
    }

    @Test
    void fillContactDetailsKeepsProvinceSetByParser() {
        ServiceDocument doc = new ServiceDocument("https://www.example.ca/x", "Example");
        doc.setBodyText("Head office N9B 3P4");
        doc.setProvince("BC");

        PatternExtractor.fillContactDetails(doc);

        assertEquals("BC", doc.getProvince());
    }

    @Test
    void largeTextIsScannedQuickly() {
        String page = "Call 604-859-2555 at V2S 1A1, email a@example.ca. ".repeat(20_000); // ~1 MB
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertEquals(1, PatternExtractor.extractPhones(page).size());
            assertEquals(1, PatternExtractor.extractEmails(page).size());
            assertEquals(1, PatternExtractor.extractPostalCodes(page).size());
        });
    }

    private static String readFixture(String resource) throws IOException {
        try (InputStream in = PatternExtractorTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing test resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

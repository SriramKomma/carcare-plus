package ca.carcareplus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.models.ServiceDocument;

class LocationMatcherTest {

    private final ServiceDocument windsorPage;
    private final InvertedIndex index;

    LocationMatcherTest() {
        // invented test page
        windsorPage = new ServiceDocument("https://www.example.ca/stores/windsor", "Example");
        windsorPage.setId(0);
        windsorPage.setTitle("Windsor store");
        windsorPage.setBodyText("456 Sample Rd, Windsor, ON N9B 3P4");
        windsorPage.setPostalCodes(List.of("N9B 3P4"));
        windsorPage.setProvince("ON");
        index = InvertedIndex.of(List.of(windsorPage));
    }

    @ParameterizedTest
    @ValueSource(strings = {"N9B 3P4", "n9b3p4", "N9B 1A1", "ON", "on", "Ontario", " ONTARIO ", "Windsor", "windsor"})
    void matches(String location) {
        assertTrue(LocationMatcher.matches(windsorPage, location, index), location);
    }

    @ParameterizedTest
    @ValueSource(strings = {"N9A 1A1", "V2S 1A1", "BC", "British Columbia", "Calgary", "Windsor Calgary", "", "   "})
    void doesNotMatch(String location) {
        assertFalse(LocationMatcher.matches(windsorPage, location, index), location);
    }

    @Test
    void cityFieldMatchesEvenIfNameIsNotInText() {
        ServiceDocument page = new ServiceDocument("https://www.example.ca/x", "Example");
        page.setId(1);
        page.setCity("Tecumseh");
        assertTrue(LocationMatcher.matches(page, "tecumseh", index));
    }

    @Test
    void nullsGiveFalse() {
        assertFalse(LocationMatcher.matches(null, "ON", index));
        assertFalse(LocationMatcher.matches(windsorPage, null, index));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"Québec, QC", "qc, QC", "Newfoundland and Labrador, NL", "PEI, PE", "Yukon, YT", "on, ON"})
    void provinceNamesAndCodes(String input, String code) {
        assertEquals(code, LocationMatcher.provinceCode(input));
    }

    @Test
    void cityIsNotAProvince() {
        assertNull(LocationMatcher.provinceCode("Windsor"));
    }
}

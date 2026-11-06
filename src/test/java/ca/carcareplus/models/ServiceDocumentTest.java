package ca.carcareplus.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;

class ServiceDocumentTest {

    private ServiceDocument doc;

    @BeforeEach
    void setUp() {
        doc = new ServiceDocument("https://www.example.ca/stores/windsor-on", "Example Tire");
    }

    @Test
    void newDocumentHasSafeDefaults() {
        assertEquals(ServiceDocument.UNASSIGNED_ID, doc.getId());
        assertFalse(doc.hasId());
        assertEquals("", doc.getTitle());
        assertEquals("", doc.getBodyText());
        assertTrue(doc.getHeadings().isEmpty());
        assertTrue(doc.getPhones().isEmpty());
        assertTrue(doc.getCategories().isEmpty());
        assertFalse(doc.isTestFixture());
    }

    @Test
    void rejectsBlankOrNullUrl() {
        assertThrows(InvalidInputException.class, () -> new ServiceDocument(null, "x"));
        assertThrows(InvalidInputException.class, () -> new ServiceDocument("  ", "x"));
    }

    @Test
    void blankSourceBecomesUnknown() {
        assertEquals("unknown", new ServiceDocument("https://a.ca", " ").getSourceName());
    }

    @Test
    void nullTextBecomesEmptyAndTextIsTrimmed() {
        doc.setTitle(null);
        doc.setDescription("  Brake repair in Windsor  ");
        assertEquals("", doc.getTitle());
        assertEquals("Brake repair in Windsor", doc.getDescription());
    }

    @Test
    void provinceIsUpperCased() {
        doc.setProvince(" on ");
        assertEquals("ON", doc.getProvince());
    }

    @Test
    void listsDropNullAndBlankEntries() {
        doc.setHeadings(Arrays.asList("Brakes", null, "  ", " Batteries "));
        assertEquals(List.of("Brakes", "Batteries"), doc.getHeadings());
    }

    @Test
    void nullListBecomesEmpty() {
        doc.setPhones(null);
        assertTrue(doc.getPhones().isEmpty());
    }

    @Test
    void gettersAreReadOnly() {
        doc.setPhones(List.of("(519) 555-0100"));
        assertThrows(UnsupportedOperationException.class, () -> doc.getPhones().add("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> doc.getCategories().add(ServiceCategory.BRAKES));
    }

    @Test
    void settersCopyTheCallersList() {
        List<String> phones = new ArrayList<>(List.of("(519) 555-0100"));
        doc.setPhones(phones);
        phones.add("(519) 555-0199");
        assertEquals(1, doc.getPhones().size());
    }

    @Test
    void categoriesIgnoreNullAndDuplicates() {
        doc.setCategories(Arrays.asList(ServiceCategory.BRAKES, null, ServiceCategory.BRAKES));
        doc.addCategory(null);
        assertEquals(1, doc.getCategories().size());
        assertTrue(doc.hasCategory(ServiceCategory.BRAKES));
        assertFalse(doc.hasCategory(ServiceCategory.FUEL));
        assertFalse(doc.hasCategory(null));
    }

    @Test
    void documentWithoutVehicleTypesAppliesToBoth() {
        assertTrue(doc.appliesTo(VehicleType.EV));
        assertTrue(doc.appliesTo(VehicleType.GAS));
        assertFalse(doc.appliesTo(null));
    }

    @Test
    void evOnlyDocumentDoesNotApplyToGas() {
        doc.addVehicleType(VehicleType.EV);
        assertTrue(doc.appliesTo(VehicleType.EV));
        assertFalse(doc.appliesTo(VehicleType.GAS));
    }

    @Test
    void idMustNotBeNegative() {
        doc.setId(7);
        assertEquals(7, doc.getId());
        assertTrue(doc.hasId());
        assertThrows(InvalidInputException.class, () -> doc.setId(-5));
    }

    @Test
    void equalityIsBasedOnUrl() {
        ServiceDocument sameUrl = new ServiceDocument("https://www.example.ca/stores/windsor-on", "Other");
        sameUrl.setTitle("Different title");
        ServiceDocument otherUrl = new ServiceDocument("https://www.example.ca/stores/london-on", "Example Tire");

        assertEquals(doc, sameUrl);
        assertEquals(doc.hashCode(), sameUrl.hashCode());
        assertNotEquals(doc, otherUrl);
        assertNotEquals(doc, null);
    }

    @Test
    void toStringMarksTestFixtures() {
        doc.setTestFixture(true);
        assertTrue(doc.toString().contains("TEST FIXTURE"));
    }
}

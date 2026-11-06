package ca.carcareplus.models;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;

class SearchRequestTest {

    @Test
    void shortcutUsesDefaults() {
        SearchRequest request = SearchRequest.of("  battery  ", VehicleType.GAS);
        assertEquals("battery", request.query());
        assertNull(request.category());
        assertEquals("", request.location());
        assertEquals(SearchRequest.DEFAULT_MAX_RESULTS, request.maxResults());
        assertFalse(request.hasCategory());
        assertFalse(request.hasLocation());
    }

    @Test
    void fullRequestIsTrimmed() {
        SearchRequest request = new SearchRequest("brake pads", VehicleType.EV,
                ServiceCategory.BRAKES, "  Windsor, ON ", 5);
        assertEquals("Windsor, ON", request.location());
        assertTrue(request.hasCategory());
        assertTrue(request.hasLocation());
    }

    @Test
    void nullLocationMeansAnywhere() {
        SearchRequest request = new SearchRequest("tire", VehicleType.GAS, null, null, 10);
        assertEquals("", request.location());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsEmptyQuery(String query) {
        assertThrows(InvalidInputException.class, () -> SearchRequest.of(query, VehicleType.EV));
    }

    @Test
    void rejectsMissingVehicleType() {
        assertThrows(InvalidInputException.class, () -> SearchRequest.of("battery", null));
    }

    @Test
    void rejectsCategoryThatDoesNotFitVehicle() {
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> new SearchRequest("fuel pump", VehicleType.EV, ServiceCategory.FUEL, "", 10));
        assertTrue(error.getMessage().contains("not available"));

        assertThrows(InvalidInputException.class,
                () -> new SearchRequest("charger", VehicleType.GAS, ServiceCategory.EV_CHARGING, "", 10));
    }

    @Test
    void chargingSearchIsAllowedForEv() {
        assertDoesNotThrow(
                () -> new SearchRequest("ccs", VehicleType.EV, ServiceCategory.EV_CHARGING, "", 10));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, SearchRequest.MAX_RESULTS_LIMIT + 1})
    void rejectsOutOfRangeResultCount(int maxResults) {
        assertThrows(InvalidInputException.class,
                () -> new SearchRequest("tire", VehicleType.GAS, null, "", maxResults));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, SearchRequest.MAX_RESULTS_LIMIT})
    void acceptsBoundaryResultCounts(int maxResults) {
        assertDoesNotThrow(() -> new SearchRequest("tire", VehicleType.GAS, null, "", maxResults));
    }
}

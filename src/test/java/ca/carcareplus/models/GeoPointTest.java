package ca.carcareplus.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;

class GeoPointTest {

    @Test
    void distances() {
        GeoPoint windsor = new GeoPoint(42.3149, -83.0364);
        assertEquals(0.0, windsor.distanceKm(windsor), 1e-9);
        assertEquals(111.2, new GeoPoint(42, -83).distanceKm(new GeoPoint(43, -83)), 0.5, "one degree of latitude");
        GeoPoint toronto = new GeoPoint(43.6532, -79.3832);
        assertEquals(windsor.distanceKm(toronto), toronto.distanceKm(windsor), 1e-9, "symmetric");
        assertThrows(InvalidInputException.class, () -> windsor.distanceKm(null));
    }

    @Test
    void rangeIsChecked() {
        assertThrows(InvalidInputException.class, () -> new GeoPoint(91, 0));
        assertThrows(InvalidInputException.class, () -> new GeoPoint(0, -181));
        assertThrows(InvalidInputException.class, () -> new GeoPoint(Double.NaN, 0));
    }

    @Test
    void parseFromText() {
        assertEquals(new GeoPoint(42.3, -83.0), GeoPoint.parse(" 42.3", "-83.0 "));
        assertNull(GeoPoint.parse("", " "));
        assertNull(GeoPoint.parse(null, null));
        assertThrows(InvalidInputException.class, () -> GeoPoint.parse("42.3", ""));
        assertThrows(InvalidInputException.class, () -> GeoPoint.parse("north", "-83"));
        assertThrows(InvalidInputException.class, () -> GeoPoint.parse("95", "-83"));
    }

    @Test
    void searchRequestRadiusIsChecked() {
        GeoPoint here = new GeoPoint(42.3, -83.0);
        assertEquals(10, new SearchRequest("repair", VehicleType.GAS, null, "", 10, here, 10).radiusKm());
        assertThrows(InvalidInputException.class, () -> new SearchRequest("repair", VehicleType.GAS, null, "", 10, here, 0));
        assertThrows(InvalidInputException.class, () -> new SearchRequest("repair", VehicleType.GAS, null, "", 10, here, 201));
        assertEquals(false, SearchRequest.of("repair", VehicleType.GAS).hasNear());
    }
}

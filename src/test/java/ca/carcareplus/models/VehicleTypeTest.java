package ca.carcareplus.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ca.carcareplus.exceptions.InvalidInputException;

class VehicleTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"ev", "EV", "  Ev  ", "electric", "ELECTRIC"})
    void recognisesElectricInputs(String input) {
        assertEquals(VehicleType.EV, VehicleType.fromUserInput(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gas", "Gas", "petrol", "diesel", " gasoline "})
    void recognisesGasInputs(String input) {
        assertEquals(VehicleType.GAS, VehicleType.fromUserInput(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingInput(String input) {
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> VehicleType.fromUserInput(input));
        assertTrue(error.getMessage().contains("required"));
    }

    @Test
    void rejectsUnknownInputWithHelpfulMessage() {
        InvalidInputException error = assertThrows(InvalidInputException.class,
                () -> VehicleType.fromUserInput("hybrid"));
        assertTrue(error.getMessage().contains("hybrid"));
        assertTrue(error.getMessage().contains("EV or Gas"));
    }

    @Test
    void everyTypeHasDisplayName() {
        for (VehicleType type : VehicleType.values()) {
            assertFalse(type.displayName().isBlank());
        }
    }
}

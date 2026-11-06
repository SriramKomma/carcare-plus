package ca.carcareplus.models;

import java.util.Locale;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * The two vehicle branches of CarCare+. The choice decides which service
 * categories are shown (for example, EV charging is never shown for gas cars).
 */
public enum VehicleType {

    EV("Electric Vehicle (EV)"),
    GAS("Gas Vehicle (Petrol/Diesel)");

    private final String displayName;

    VehicleType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Converts what a user typed into a VehicleType, ignoring case and spaces.
     * Accepts: "ev", "electric" -> EV; "gas", "petrol", "diesel", "gasoline" -> GAS.
     *
     * @throws InvalidInputException if the text is null, blank or not recognised
     */
    public static VehicleType fromUserInput(String input) {
        if (input == null || input.isBlank()) {
            throw new InvalidInputException("Vehicle type is required (EV or Gas).");
        }
        return switch (input.trim().toLowerCase(Locale.ROOT)) {
            case "ev", "electric" -> EV;
            case "gas", "petrol", "diesel", "gasoline" -> GAS;
            default -> throw new InvalidInputException(
                    "Unknown vehicle type '" + input.trim() + "'. Please enter EV or Gas.");
        };
    }
}

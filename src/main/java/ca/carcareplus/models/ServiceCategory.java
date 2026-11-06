package ca.carcareplus.models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * Kinds of help a driver can look for.
 *
 * Each category knows:
 *  - which vehicle types it applies to (ENGINE and FUEL are gas-only; EV_* are EV-only)
 *  - whether it is a repair category (EV_CHARGING is a separate "Find Charging Stations"
 *    option in the EV branch, not a repair).
 */
public enum ServiceCategory {

    FLAT_TIRE("Flat tire / puncture", true, VehicleType.EV, VehicleType.GAS),
    BATTERY("Battery problem", true, VehicleType.EV, VehicleType.GAS),
    ELECTRICAL("Electrical problem", true, VehicleType.EV, VehicleType.GAS),
    ENGINE("Engine issue", true, VehicleType.GAS),
    BRAKES("Brake problem", true, VehicleType.EV, VehicleType.GAS),
    WIPERS("Wiper problem", true, VehicleType.EV, VehicleType.GAS),
    BREAKDOWN_TOWING("Vehicle breakdown / towing", true, VehicleType.EV, VehicleType.GAS),
    OVERHEATING_COOLING("Overheating / cooling system", true, VehicleType.EV, VehicleType.GAS),
    FUEL("Fuel problem", true, VehicleType.GAS),
    EV_MAINTENANCE("EV maintenance (motor, high-voltage system)", true, VehicleType.EV),
    EV_CHARGING("EV charging station", false, VehicleType.EV),
    OTHER("Other / custom description", true, VehicleType.EV, VehicleType.GAS);

    private final String displayName;
    private final boolean repair;
    // EnumSet: a compact bit-set; contains() is O(1)
    private final Set<VehicleType> vehicleTypes;

    ServiceCategory(String displayName, boolean repair, VehicleType... vehicleTypes) {
        this.displayName = displayName;
        this.repair = repair;
        this.vehicleTypes = Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(vehicleTypes)));
    }

    public String displayName() {
        return displayName;
    }

    public boolean isRepair() {
        return repair;
    }

    public Set<VehicleType> vehicleTypes() {
        return vehicleTypes;
    }

    public boolean appliesTo(VehicleType vehicleType) {
        return vehicleType != null && vehicleTypes.contains(vehicleType);
    }

    /**
     * Repair categories to show in the menu for one vehicle type, in declaration order.
     *
     * @throws InvalidInputException if vehicleType is null
     */
    public static List<ServiceCategory> repairCategoriesFor(VehicleType vehicleType) {
        if (vehicleType == null) {
            throw new InvalidInputException("Vehicle type is required to list repair categories.");
        }
        List<ServiceCategory> result = new ArrayList<>();
        for (ServiceCategory category : values()) {
            if (category.isRepair() && category.appliesTo(vehicleType)) {
                result.add(category);
            }
        }
        return Collections.unmodifiableList(result);
    }
}

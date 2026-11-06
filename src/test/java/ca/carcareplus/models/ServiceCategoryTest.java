package ca.carcareplus.models;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;

class ServiceCategoryTest {

    @Test
    void gasCarsNeverSeeEvCategories() {
        List<ServiceCategory> gas = ServiceCategory.repairCategoriesFor(VehicleType.GAS);
        assertFalse(gas.contains(ServiceCategory.EV_CHARGING));
        assertFalse(gas.contains(ServiceCategory.EV_MAINTENANCE));
        assertTrue(gas.contains(ServiceCategory.ENGINE));
        assertTrue(gas.contains(ServiceCategory.FUEL));
    }

    @Test
    void evRepairListExcludesEngineFuelAndCharging() {
        List<ServiceCategory> ev = ServiceCategory.repairCategoriesFor(VehicleType.EV);
        assertFalse(ev.contains(ServiceCategory.ENGINE));
        assertFalse(ev.contains(ServiceCategory.FUEL));
        // charging is its own menu option, not a repair
        assertFalse(ev.contains(ServiceCategory.EV_CHARGING));
        assertTrue(ev.contains(ServiceCategory.EV_MAINTENANCE));
        assertTrue(ev.contains(ServiceCategory.BATTERY));
    }

    @Test
    void chargingAppliesOnlyToEv() {
        assertTrue(ServiceCategory.EV_CHARGING.appliesTo(VehicleType.EV));
        assertFalse(ServiceCategory.EV_CHARGING.appliesTo(VehicleType.GAS));
        assertFalse(ServiceCategory.EV_CHARGING.isRepair());
    }

    @Test
    void appliesToNullIsFalse() {
        assertFalse(ServiceCategory.BRAKES.appliesTo(null));
    }

    @Test
    void repairCategoriesForNullThrows() {
        assertThrows(InvalidInputException.class, () -> ServiceCategory.repairCategoriesFor(null));
    }

    @Test
    void repairListIsReadOnly() {
        List<ServiceCategory> gas = ServiceCategory.repairCategoriesFor(VehicleType.GAS);
        assertThrows(UnsupportedOperationException.class, () -> gas.add(ServiceCategory.EV_CHARGING));
    }

    @Test
    void everyCategoryIsComplete() {
        for (ServiceCategory category : ServiceCategory.values()) {
            assertFalse(category.displayName().isBlank(), category + " needs a display name");
            assertFalse(category.vehicleTypes().isEmpty(), category + " needs a vehicle type");
        }
    }
}

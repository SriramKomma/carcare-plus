package ca.carcareplus.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.VehicleType;

/**
 * The ready-made problems in the "What's wrong with your car?" drop-down.
 *
 * Why: most drivers have one of a few dozen everyday problems. Picking one is
 * faster than typing, and it can never be misspelled or misunderstood.
 *
 * How it plugs in: each problem has a short phrase written in the words of
 * config/symptoms.txt ("dead battery"). The page sends that phrase as the search
 * text, so SymptomMatcher finds the service exactly as if the driver had typed it,
 * and the "Looks like: Battery problem" note still appears. CommonProblemsTest
 * checks that every phrase really leads to the category written next to it.
 *
 * A problem is offered only for vehicles its category applies to, so engine and
 * fuel problems are not shown for an electric car (ServiceCategory knows this).
 */
public final class CommonProblems {

    /**
     * One choice in the drop-down.
     *
     * @param id       stable name used by the page (e.g. the "Flat tire" tile picks "flat-tire")
     * @param group    heading the choice is listed under
     * @param label    what the driver reads
     * @param phrase   what is searched; must match a symptom in config/symptoms.txt
     * @param category the service the phrase must lead to
     */
    public record Problem(String id, String group, String label, String phrase, ServiceCategory category) {
    }

    private static final List<Problem> ALL = List.of(
            // Starting and battery
            p("wont-start", "Starting & battery", "Car won't start", "won't start", ServiceCategory.BATTERY),
            p("dead-battery", "Starting & battery", "Dead battery", "dead battery", ServiceCategory.BATTERY),
            p("clicking-key", "Starting & battery", "Clicking sound when I turn the key", "clicking key", ServiceCategory.BATTERY),
            p("slow-crank", "Starting & battery", "Engine cranks slowly", "slow crank", ServiceCategory.BATTERY),
            p("battery-drains", "Starting & battery", "Battery keeps draining", "battery drains", ServiceCategory.BATTERY),
            p("jump-start", "Starting & battery", "Need a jump start / boost", "jump start", ServiceCategory.BATTERY),
            p("battery-light", "Starting & battery", "Battery warning light is on", "battery light", ServiceCategory.BATTERY),

            // Tires and steering
            p("flat-tire", "Tires & steering", "Flat tire", "flat tire", ServiceCategory.FLAT_TIRE),
            p("puncture", "Tires & steering", "Puncture / nail in the tire", "puncture nail tire", ServiceCategory.FLAT_TIRE),
            p("tire-leak", "Tires & steering", "Tire is slowly losing air", "tire leak", ServiceCategory.FLAT_TIRE),
            p("tire-pressure-light", "Tires & steering", "Tire pressure light is on", "tire pressure light", ServiceCategory.FLAT_TIRE),
            p("tire-bulge", "Tires & steering", "Bulge on the tire", "bulge tire", ServiceCategory.FLAT_TIRE),
            p("pulls-side", "Tires & steering", "Car pulls to one side", "pulls to one side", ServiceCategory.FLAT_TIRE),
            p("wheel-shakes", "Tires & steering", "Steering wheel shakes", "steering wheel shakes", ServiceCategory.FLAT_TIRE),
            p("highway-vibration", "Tires & steering", "Vibration at highway speed", "vibration highway", ServiceCategory.FLAT_TIRE),

            // Brakes
            p("squealing-brakes", "Brakes", "Brakes squeal or squeak", "squealing brakes", ServiceCategory.BRAKES),
            p("grinding-brakes", "Brakes", "Grinding noise when braking", "grinding noise", ServiceCategory.BRAKES),
            p("spongy-pedal", "Brakes", "Brake pedal feels soft / spongy", "brake pedal soft spongy pedal", ServiceCategory.BRAKES),
            p("pedal-floor", "Brakes", "Brake pedal goes to the floor", "pedal goes floor", ServiceCategory.BRAKES),
            p("longer-stop", "Brakes", "Car takes longer to stop", "takes longer stop", ServiceCategory.BRAKES),
            p("shakes-braking", "Brakes", "Car shakes when braking", "shakes when braking", ServiceCategory.BRAKES),
            p("brake-light", "Brakes", "Brake warning light is on", "brake light", ServiceCategory.BRAKES),

            // Engine and transmission (gas cars)
            p("check-engine", "Engine & transmission", "Check engine light is on", "check engine light", ServiceCategory.ENGINE),
            p("misfire", "Engine & transmission", "Engine misfiring", "misfire", ServiceCategory.ENGINE),
            p("rough-idle", "Engine & transmission", "Rough or shaky idle", "rough idle", ServiceCategory.ENGINE),
            p("stalling", "Engine & transmission", "Engine stalls", "stalling", ServiceCategory.ENGINE),
            p("knocking", "Engine & transmission", "Knocking noise from the engine", "knocking noise", ServiceCategory.ENGINE),
            p("loss-power", "Engine & transmission", "Loss of power when accelerating", "loss power", ServiceCategory.ENGINE),
            p("oil-leak", "Engine & transmission", "Oil leak", "oil leak", ServiceCategory.ENGINE),
            p("oil-light", "Engine & transmission", "Oil warning light is on", "oil light", ServiceCategory.ENGINE),
            p("exhaust-smoke", "Engine & transmission", "Smoke from the exhaust", "smoke exhaust", ServiceCategory.ENGINE),
            p("loud-exhaust", "Engine & transmission", "Loud exhaust", "loud exhaust", ServiceCategory.ENGINE),
            p("transmission-slipping", "Engine & transmission", "Transmission slipping", "transmission slipping", ServiceCategory.ENGINE),
            p("hard-shifting", "Engine & transmission", "Hard or rough gear shifting", "hard shifting", ServiceCategory.ENGINE),

            // Overheating, heating and A/C
            p("overheating", "Overheating, heat & A/C", "Car is overheating", "overheating", ServiceCategory.OVERHEATING_COOLING),
            p("temp-high", "Overheating, heat & A/C", "Temperature gauge is high", "temperature gauge high", ServiceCategory.OVERHEATING_COOLING),
            p("steam-hood", "Overheating, heat & A/C", "Steam from under the hood", "steam hood", ServiceCategory.OVERHEATING_COOLING),
            p("coolant-leak", "Overheating, heat & A/C", "Coolant leak (green fluid)", "coolant leak", ServiceCategory.OVERHEATING_COOLING),
            p("sweet-smell", "Overheating, heat & A/C", "Sweet smell from the car", "sweet smell", ServiceCategory.OVERHEATING_COOLING),
            p("no-heat", "Overheating, heat & A/C", "Heater not working", "heater not working", ServiceCategory.OVERHEATING_COOLING),
            p("ac-warm", "Overheating, heat & A/C", "A/C blowing warm air", "air conditioning not working", ServiceCategory.OVERHEATING_COOLING),

            // Lights and electrical
            p("headlight-out", "Lights & electrical", "Headlight is out", "headlight out", ServiceCategory.ELECTRICAL),
            p("headlights-not-working", "Lights & electrical", "Headlights not working", "headlights not working", ServiceCategory.ELECTRICAL),
            p("lights-flicker", "Lights & electrical", "Lights flicker", "lights flickering", ServiceCategory.ELECTRICAL),
            p("dim-lights", "Lights & electrical", "Lights are dim", "dim lights", ServiceCategory.ELECTRICAL),
            p("dashboard-lights", "Lights & electrical", "Dashboard warning lights are on", "dashboard lights", ServiceCategory.ELECTRICAL),
            p("power-windows", "Lights & electrical", "Power windows not working", "power windows", ServiceCategory.ELECTRICAL),
            p("radio", "Lights & electrical", "Radio / screen not working", "radio not working", ServiceCategory.ELECTRICAL),
            p("blown-fuse", "Lights & electrical", "Blown fuse", "blown fuse", ServiceCategory.ELECTRICAL),
            p("burning-wires", "Lights & electrical", "Burning smell from wires", "burning smell wires", ServiceCategory.ELECTRICAL),
            p("alternator", "Lights & electrical", "Alternator problem", "alternator", ServiceCategory.ELECTRICAL),
            p("starter", "Lights & electrical", "Starter motor problem", "starter motor", ServiceCategory.ELECTRICAL),

            // Wipers and windshield
            p("wipers-not-working", "Wipers & windshield", "Wipers not working", "wipers not working", ServiceCategory.WIPERS),
            p("wipers-streak", "Wipers & windshield", "Wipers leave streaks", "wipers streak", ServiceCategory.WIPERS),
            p("wiper-squeak", "Wipers & windshield", "Wipers squeak", "wiper squeak", ServiceCategory.WIPERS),
            p("washer", "Wipers & windshield", "Windshield washer not spraying", "windshield washer", ServiceCategory.WIPERS),
            p("cracked-windshield", "Wipers & windshield", "Cracked windshield", "cracked windshield", ServiceCategory.WIPERS),
            p("chipped-windshield", "Wipers & windshield", "Chipped windshield", "chipped windshield", ServiceCategory.WIPERS),

            // Fuel (gas cars)
            p("out-of-gas", "Fuel", "Ran out of gas", "ran out fuel", ServiceCategory.FUEL),
            p("gas-smell", "Fuel", "Smell of gas", "gas smell", ServiceCategory.FUEL),
            p("fuel-leak", "Fuel", "Fuel leak", "fuel leak", ServiceCategory.FUEL),
            p("poor-mileage", "Fuel", "Poor gas mileage", "poor mileage", ServiceCategory.FUEL),
            p("fuel-gauge", "Fuel", "Fuel gauge not working", "fuel gauge", ServiceCategory.FUEL),
            p("hard-start-cold", "Fuel", "Hard to start when cold", "hard start cold", ServiceCategory.FUEL),

            // Electric cars
            p("wont-charge", "Electric car", "Car won't charge", "won't charge", ServiceCategory.EV_MAINTENANCE),
            p("charge-port", "Electric car", "Charging port problem", "charging port", ServiceCategory.EV_MAINTENANCE),
            p("reduced-range", "Electric car", "Range has dropped", "reduced range", ServiceCategory.EV_MAINTENANCE),
            p("high-voltage", "Electric car", "High-voltage warning", "high voltage warning", ServiceCategory.EV_MAINTENANCE),
            p("motor-noise", "Electric car", "Noise from the electric motor", "motor noise", ServiceCategory.EV_MAINTENANCE),
            p("regen", "Electric car", "Regenerative braking not working", "regen not working", ServiceCategory.EV_MAINTENANCE),
            p("need-charge", "Electric car", "Need to charge now", "need charge", ServiceCategory.EV_CHARGING),
            p("out-of-charge", "Electric car", "Ran out of charge", "ran out charge", ServiceCategory.EV_CHARGING),

            // Breakdown and roadside
            p("need-tow", "Breakdown & roadside", "Broke down, need a tow", "broke down need tow", ServiceCategory.BREAKDOWN_TOWING),
            p("stranded", "Breakdown & roadside", "Stranded on the road", "stranded", ServiceCategory.BREAKDOWN_TOWING),
            p("accident", "Breakdown & roadside", "Had an accident", "accident", ServiceCategory.BREAKDOWN_TOWING),
            p("locked-out", "Breakdown & roadside", "Locked out / keys locked inside", "keys locked", ServiceCategory.BREAKDOWN_TOWING),
            p("ditch", "Breakdown & roadside", "Stuck in a ditch or snow", "ditch", ServiceCategory.BREAKDOWN_TOWING),
            p("wont-move", "Breakdown & roadside", "Car won't move", "won't move", ServiceCategory.BREAKDOWN_TOWING));

    /**
     * The most frequent problems, most frequent first. The drop-down lists them again
     * in a "Common problems" group at the top, so most drivers never scroll.
     * Only those that suit the vehicle are shown (check-engine for gas, dashboard lights for EV).
     */
    private static final List<String> COMMON_IDS = List.of(
            "wont-start", "dead-battery", "flat-tire", "squealing-brakes", "check-engine",
            "dashboard-lights", "overheating", "headlight-out", "wont-charge", "need-charge", "need-tow");

    private CommonProblems() {
    }

    private static Problem p(String id, String group, String label, String phrase, ServiceCategory category) {
        return new Problem(id, group, label, phrase, category);
    }

    /**
     * Position in the "Common problems" group (0 = most common), or -1 if the
     * problem is not one of the common ones. O(c) for c common problems.
     */
    public static int commonRank(Problem problem) {
        return problem == null ? -1 : COMMON_IDS.indexOf(problem.id());
    }

    /** The common problems for one vehicle, most frequent first. */
    public static List<Problem> commonFor(VehicleType vehicle) {
        List<Problem> result = new ArrayList<>();
        for (String id : COMMON_IDS) {
            for (Problem problem : forVehicle(vehicle)) {
                if (problem.id().equals(id)) {
                    result.add(problem);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Every problem, in drop-down order (for tests). */
    public static List<Problem> all() {
        return ALL;
    }

    /**
     * The problems that make sense for one vehicle, in drop-down order. O(n) for n problems.
     *
     * @throws InvalidInputException if vehicle is null
     */
    public static List<Problem> forVehicle(VehicleType vehicle) {
        if (vehicle == null) {
            throw new InvalidInputException("Vehicle type is required to list common problems.");
        }
        List<Problem> result = new ArrayList<>();
        for (Problem problem : ALL) {
            if (problem.category().appliesTo(vehicle)) {
                result.add(problem);
            }
        }
        return Collections.unmodifiableList(result);
    }
}

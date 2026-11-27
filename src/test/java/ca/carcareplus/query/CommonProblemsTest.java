package ca.carcareplus.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.ServiceCategory;
import ca.carcareplus.models.VehicleType;

class CommonProblemsTest {

    private static SymptomMatcher realRules;

    @BeforeAll
    static void loadRealRules() throws CarCareException {
        // The same file the web page uses, so a change to symptoms.txt that breaks a
        // drop-down choice fails here
        realRules = SymptomMatcher.loadFile(Path.of("config", "symptoms.txt"));
    }

    /** What WebApi does: the best match among the categories that suit the vehicle. */
    private static ServiceCategory understood(String phrase, VehicleType vehicle) {
        for (SymptomMatcher.Match match : realRules.match(phrase)) {
            if (match.category().appliesTo(vehicle)) {
                return match.category();
            }
        }
        return null;
    }

    @Test
    void everyPhraseLeadsToItsOwnCategoryForEveryVehicleItIsShownFor() {
        for (VehicleType vehicle : VehicleType.values()) {
            for (CommonProblems.Problem problem : CommonProblems.forVehicle(vehicle)) {
                assertEquals(problem.category(), understood(problem.phrase(), vehicle),
                        problem.label() + " (" + vehicle + ") searched as '" + problem.phrase() + "'");
            }
        }
    }

    @Test
    void idsAreUniqueAndTextsAreFilledIn() {
        Set<String> ids = new HashSet<>();
        for (CommonProblems.Problem problem : CommonProblems.all()) {
            assertTrue(ids.add(problem.id()), "duplicate id " + problem.id());
            assertTrue(problem.id().matches("[a-z0-9-]+"), problem.id());
            assertFalse(problem.group().isBlank());
            assertFalse(problem.label().isBlank());
            assertFalse(problem.phrase().isBlank());
        }
    }

    @Test
    void everyRepairCategoryHasAtLeastOneChoice() {
        Set<ServiceCategory> covered = new HashSet<>();
        for (CommonProblems.Problem problem : CommonProblems.all()) {
            covered.add(problem.category());
        }
        for (ServiceCategory category : ServiceCategory.values()) {
            if (category != ServiceCategory.OTHER) {   // "Other" is the free-text box itself
                assertTrue(covered.contains(category), "no drop-down choice for " + category);
            }
        }
    }

    @Test
    void electricCarsDoNotSeeEngineOrFuelProblems() {
        List<CommonProblems.Problem> ev = CommonProblems.forVehicle(VehicleType.EV);
        assertTrue(ev.stream().noneMatch(p -> p.category() == ServiceCategory.ENGINE || p.category() == ServiceCategory.FUEL));
        assertTrue(ev.stream().anyMatch(p -> p.id().equals("wont-charge")));
    }

    @Test
    void gasCarsDoNotSeeElectricCarProblems() {
        List<CommonProblems.Problem> gas = CommonProblems.forVehicle(VehicleType.GAS);
        assertTrue(gas.stream().noneMatch(p -> p.category().name().startsWith("EV_")));
        assertTrue(gas.stream().anyMatch(p -> p.id().equals("check-engine")));
    }

    @Test
    void commonProblemsFollowTheVehicleAndTheirOrder() {
        List<String> ev = CommonProblems.commonFor(VehicleType.EV).stream().map(CommonProblems.Problem::id).toList();
        List<String> gas = CommonProblems.commonFor(VehicleType.GAS).stream().map(CommonProblems.Problem::id).toList();
        assertEquals(List.of("wont-start", "dead-battery", "flat-tire", "squealing-brakes", "dashboard-lights",
                "overheating", "headlight-out", "wont-charge", "need-charge", "need-tow"), ev);
        assertEquals(List.of("wont-start", "dead-battery", "flat-tire", "squealing-brakes", "check-engine",
                "dashboard-lights", "overheating", "headlight-out", "need-tow"), gas);
    }

    @Test
    void everyCommonIdIsARealProblem() {
        long common = CommonProblems.all().stream().filter(p -> CommonProblems.commonRank(p) >= 0).count();
        assertEquals(11, common, "a misspelled id in COMMON_IDS would silently drop a common problem");
        assertEquals(0, CommonProblems.commonRank(CommonProblems.all().get(0)));
        assertEquals(-1, CommonProblems.commonRank(null));
    }

    @Test
    void vehicleIsRequired() {
        assertThrows(InvalidInputException.class, () -> CommonProblems.forVehicle(null));
    }
}

package ca.carcareplus.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 1 smoke test: proves Maven compiles main + test code and that
 * JUnit 5 is discovered and run by Surefire.
 */
class MainTest {

    @Test
    void bannerContainsAppName() {
        assertTrue(Main.banner().contains("CarCare+"),
                "Banner should mention the application name");
    }

    @Test
    void runsOnJava21OrNewer() {
        int javaVersion = Runtime.version().feature();
        assertTrue(javaVersion >= 21,
                "Expected Java 21+, but tests are running on Java " + javaVersion);
    }
}

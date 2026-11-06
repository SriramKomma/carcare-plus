package ca.carcareplus.app;

/**
 * Entry point of CarCare+.
 *
 * Phase 1: only prints a banner so we can confirm the build and run setup works.
 * The console menu (ConsoleMenu) is connected here in Phase 11.
 */
public class Main {

    static final String APP_NAME = "CarCare+";

    public static void main(String[] args) {
        System.out.println(banner());
        System.out.println("Running on Java " + Runtime.version().feature());
    }

    /** Returns the start-up banner. Kept as a method so it can be unit tested. */
    static String banner() {
        return APP_NAME + " - Intelligent Roadside Assistance and Service Discovery";
    }
}

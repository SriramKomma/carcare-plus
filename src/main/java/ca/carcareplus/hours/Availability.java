package ca.carcareplus.hours;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Whether a place is open at a given moment, worked out from its opening hours.
 *
 * This is NOT live availability (free repair bays, booking slots): no source we
 * use publishes that. UNKNOWN means the hours are missing or in a format we do
 * not support, so the page says "call ahead" instead of guessing.
 *
 * @param state open, closing within the hour, closed, or unknown
 * @param label text for the page, e.g. "Open now · closes 5:00 PM"
 */
public record Availability(State state, String label) {

    public enum State { OPEN, CLOSING_SOON, CLOSED, UNKNOWN }

    /** Ontario's time zone (Eastern). The far north-west uses Central time; we ignore that. */
    public static final ZoneId ONTARIO = ZoneId.of("America/Toronto");

    public static final Availability UNKNOWN_HOURS = new Availability(State.UNKNOWN, "Hours unknown – call ahead");
    public static final Availability UNSUPPORTED_HOURS = new Availability(State.UNKNOWN, "See hours");

    /** Open now (including "closing soon"). */
    public boolean isOpen() {
        return state == State.OPEN || state == State.CLOSING_SOON;
    }

    /** Status for an OpenStreetMap opening_hours text at the clock's current Ontario time. */
    public static Availability of(String openingHours, Clock clock) {
        if (openingHours == null || openingHours.isBlank()) {
            return UNKNOWN_HOURS;
        }
        OpeningHours hours = OpeningHours.parse(openingHours);
        return hours == null ? UNSUPPORTED_HOURS : hours.availability(LocalDateTime.now(clock.withZone(ONTARIO)));
    }
}

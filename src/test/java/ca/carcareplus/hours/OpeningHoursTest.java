package ca.carcareplus.hours;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Opening hours and open/closed status. 2026-11-16 is a Monday; all times are
 * Ontario local time.
 */
class OpeningHoursTest {

    private static LocalDateTime at(String dayAndTime) {
        return LocalDateTime.parse(dayAndTime);
    }

    private static String label(String hours, String when) {
        return OpeningHours.parse(hours).availability(at(when)).label();
    }

    @ParameterizedTest(name = "{0} at {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            // a normal weekday shop
            "Mo-Fr 08:00-17:00; Sa 09:00-13:00 | 2026-11-16T10:00 | Open now · closes 5:00 PM",
            "Mo-Fr 08:00-17:00; Sa 09:00-13:00 | 2026-11-16T16:15 | Closes soon · 5:00 PM",
            "Mo-Fr 08:00-17:00; Sa 09:00-13:00 | 2026-11-16T17:00 | Closed · opens tomorrow 8:00 AM",
            "Mo-Fr 08:00-17:00; Sa 09:00-13:00 | 2026-11-16T07:30 | Closed · opens 8:00 AM",
            "Mo-Fr 08:00-17:00; Sa 09:00-13:00 | 2026-11-21T14:00 | Closed · opens Mon 8:00 AM",
            "Mo-Fr 08:00-17:00; Sa 09:00-13:00 | 2026-11-22T12:00 | Closed · opens tomorrow 8:00 AM",
            // lunch break, day lists, every day
            "Mo,We,Fr 09:00-12:00,13:00-17:00 | 2026-11-16T12:30 | Closed · opens 1:00 PM",
            "Mo,We,Fr 09:00-12:00,13:00-17:00 | 2026-11-17T10:00 | Closed · opens tomorrow 9:00 AM",
            "09:00-21:00 | 2026-11-22T09:00 | Open now · closes 9:00 PM",
            // around the clock and past midnight
            "24/7 | 2026-11-18T03:00 | Open 24 hours",
            "Mo-Su 00:00-24:00 | 2026-11-18T03:00 | Open 24 hours",
            "Mo-Su 18:00-02:00 | 2026-11-16T23:30 | Open now · closes tomorrow 2:00 AM",
            "Mo-Su 18:00-02:00 | 2026-11-17T01:30 | Closes soon · 2:00 AM",
            "Fr 20:00-03:00 | 2026-11-21T02:00 | Closes soon · 3:00 AM",
            // a later rule replaces an earlier one for its days
            "Mo-Sa 08:00-18:00; Sa 09:00-12:00 | 2026-11-21T13:00 | Closed · opens Mon 8:00 AM",
            "Mo-Su 08:00-18:00; Su off | 2026-11-22T10:00 | Closed · opens tomorrow 8:00 AM",
            "Mo-Fr 08:00-18:00; PH off | 2026-11-16T09:00 | Open now · closes 6:00 PM",
            // day range that wraps around the week
            "Sa-Mo 10:00-16:00 | 2026-11-23T11:00 | Open now · closes 4:00 PM",
            "Sa-Mo 10:00-16:00 | 2026-11-18T11:00 | Closed · opens Sat 10:00 AM"
    })
    void statusAtAGivenTime(String hours, String when, String expected) {
        assertEquals(expected, label(hours.trim(), when.trim()));
    }

    @Test
    void closedEveryDayHasNoNextOpening() {
        assertEquals("Closed", label("Mo-Su off", "2026-11-16T10:00"));
    }

    @Test
    void openAndClosingSoonCountAsOpen() {
        OpeningHours hours = OpeningHours.parse("Mo-Fr 08:00-17:00");
        assertTrue(hours.availability(at("2026-11-16T16:30")).isOpen());
        assertEquals(Availability.State.CLOSING_SOON, hours.availability(at("2026-11-16T16:30")).state());
        assertFalse(hours.availability(at("2026-11-16T17:30")).isOpen());
        assertTrue(hours.isOpen(at("2026-11-16T08:00")), "opening minute is open");
        assertFalse(hours.isOpen(at("2026-11-16T17:00")), "closing minute is closed");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "Jan-Mar Mo-Fr 08:00-17:00", "sunrise-sunset", "Mo-Fr 08:00-17:00 \"by appointment\"",
            "Mo[1] 09:00-12:00", "Mo-Fr", "Mo-Fr 8am-5pm", "25:00-26:00", "Mo-Fr 08:75-17:00", "Week 01-10 Mo 09:00-12:00"})
    void unsupportedOrBrokenHoursGiveNull(String text) {
        assertNull(OpeningHours.parse(text), "never guess: " + text);
    }

    @Test
    void weeklyScheduleIsReadable() {
        assertEquals(List.of("Mon: 8:00 a.m. – 5:00 p.m.".replace("a.m.", "AM").replace("p.m.", "PM"),
                        "Tue: 8:00 AM – 5:00 PM", "Wed: 8:00 AM – 5:00 PM", "Thu: 8:00 AM – 5:00 PM",
                        "Fri: 8:00 AM – 5:00 PM", "Sat: 9:00 AM – 1:00 PM", "Sun: Closed"),
                OpeningHours.parse("Mo-Fr 08:00-17:00; Sa 09:00-13:00").weeklySchedule());
        assertEquals("Wed: Open 24 hours", OpeningHours.parse("24/7").weeklySchedule().get(2));
        assertEquals("Tue: 12:00 AM – 2:00 AM", OpeningHours.parse("Mo 18:00-02:00").weeklySchedule().get(1),
                "the part after midnight shows on the next day");
    }

    @Test
    void clockFormat() {
        assertEquals("12:00 AM", OpeningHours.clock(0));
        assertEquals("8:30 AM", OpeningHours.clock(510));
        assertEquals("12:00 PM", OpeningHours.clock(720));
        assertEquals("11:59 PM", OpeningHours.clock(1439));
        assertEquals("12:00 AM", OpeningHours.clock(1440));
    }

    @Test
    void availabilityUsesOntarioTime() {
        // 15:30 UTC on Monday 2026-11-16 = 10:30 in Toronto (EST, UTC-5)
        Clock clock = Clock.fixed(ZonedDateTime.parse("2026-11-16T15:30:00Z").toInstant(), java.time.ZoneOffset.UTC);
        assertEquals("Open now · closes 5:00 PM", Availability.of("Mo-Fr 08:00-17:00", clock).label());
        assertEquals(Availability.UNKNOWN_HOURS, Availability.of("", clock));
        assertEquals(Availability.UNSUPPORTED_HOURS, Availability.of("sunrise-sunset", clock));
        assertFalse(Availability.UNKNOWN_HOURS.isOpen(), "unknown is never shown as open");
    }
}

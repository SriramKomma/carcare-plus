package ca.carcareplus.visits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import ca.carcareplus.hours.OpeningHours;

/** Times offered for a visit. 2026-11-16 is a Monday. */
class VisitSlotsTest {

    private static final LocalDateTime MONDAY_10_30 = LocalDateTime.parse("2026-11-16T10:30");

    private static List<String> labels(VisitSlots.Day day) {
        return day.slots().stream().map(VisitSlots.Slot::label).toList();
    }

    @Test
    void knownHoursGiveATimeEveryHalfHourWhileOpen() {
        VisitSlots slots = VisitSlots.of(OpeningHours.parse("Mo-Fr 08:00-17:00"), MONDAY_10_30, 2);

        assertTrue(slots.hoursKnown());
        assertEquals(List.of("Today", "Tomorrow", "Wed 18 Nov"), slots.days().stream().map(VisitSlots.Day::label).toList());
        List<String> today = labels(slots.days().get(0));
        assertEquals("10:30 AM", today.get(0));
        assertEquals("4:30 PM", today.get(today.size() - 1), "the shop must be open 30 minutes after the start");
        assertEquals(13, today.size());
        assertEquals(18, slots.days().get(1).slots().size(), "8:00 AM to 4:30 PM");
        // Wednesday only until 10:30 AM (48 hours from now): last start 10:00 AM
        assertEquals(List.of("8:00 AM", "8:30 AM", "9:00 AM", "9:30 AM", "10:00 AM"), labels(slots.days().get(2)));
        assertEquals("2026-11-16T10:30", slots.days().get(0).slots().get(0).value());
    }

    @Test
    void startsAreRoundedUpToTheHalfHour() {
        VisitSlots slots = VisitSlots.of(OpeningHours.parse("Mo-Fr 08:00-17:00"), LocalDateTime.parse("2026-11-16T10:31"), 2);
        assertEquals("11:00 AM", slots.days().get(0).slots().get(0).label(), "10:45 is not on the half hour");
    }

    @Test
    void closedDaysHaveNoTimes() {
        // Friday 18:00: closed for the rest of Friday and the whole weekend
        assertTrue(VisitSlots.of(OpeningHours.parse("Mo-Fr 08:00-17:00"), LocalDateTime.parse("2026-11-20T18:00"), 2).isEmpty());
    }

    @Test
    void unknownHoursOfferMorningOrAfternoon() {
        VisitSlots slots = VisitSlots.of(null, MONDAY_10_30, 2);

        assertFalse(slots.hoursKnown());
        assertEquals(List.of("Morning (9 AM – 12 PM)", "Afternoon (1 PM – 5 PM)"), labels(slots.days().get(0)));
        assertEquals(2, slots.days().get(1).slots().size());
        assertEquals(List.of("Morning (9 AM – 12 PM)"), labels(slots.days().get(2)),
                "Wednesday afternoon is more than 48 hours away");
        assertEquals("2026-11-16T13:00", slots.days().get(0).slots().get(1).value());
    }

    @Test
    void lateInTheDayTodayHasNoParts() {
        VisitSlots slots = VisitSlots.of(null, LocalDateTime.parse("2026-11-16T16:45"), 2);
        assertEquals("Tomorrow", slots.days().get(0).label());
    }

    @Test
    void onlyOfferedTimesCanBeFound() {
        VisitSlots slots = VisitSlots.of(OpeningHours.parse("Mo-Fr 08:00-17:00"), MONDAY_10_30, 2);
        assertEquals("2:00 PM", slots.find("2026-11-17T14:00").label());
        assertEquals("Tomorrow, 2:00 PM", slots.describe("2026-11-17T14:00"));
        assertNull(slots.find("2026-11-17T18:00"), "after closing");
        assertNull(slots.find("2026-11-16T09:00"), "already past");
        assertNull(slots.find("2026-11-17T14:15"), "not on the half hour");
    }

    @Test
    void dayLabels() {
        LocalDate monday = LocalDate.parse("2026-11-16");
        assertEquals("Today", VisitSlots.dayLabel(monday, monday));
        assertEquals("Tomorrow", VisitSlots.dayLabel(monday.plusDays(1), monday));
        assertEquals("Sat 2 Jan", VisitSlots.dayLabel(LocalDate.parse("2027-01-02"), LocalDate.parse("2026-12-31")));
    }
}

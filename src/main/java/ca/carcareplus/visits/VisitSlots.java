package ca.carcareplus.visits;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ca.carcareplus.hours.OpeningHours;

/**
 * The times a driver can pick for a non-priority visit: from now until
 * {@code days} days later.
 *
 * Known hours: a start time every {@value #STEP_MINUTES} minutes inside the shop's
 * opening windows (OpeningHours.openingsWithin), so the shop is open for at least
 * {@value #STEP_MINUTES} minutes after the chosen time.
 *
 * Unknown hours: we never guess hours, so the driver picks a part of the day
 * (morning or afternoon) and the shop confirms the exact time ({@link #hoursKnown()} is false).
 */
public final class VisitSlots {

    static final int STEP_MINUTES = 30;

    /** Unknown hours: [start, end) minutes and the label of each part of the day. */
    private static final int[][] PARTS = {{9 * 60, 12 * 60}, {13 * 60, 17 * 60}};
    private static final String[] PART_LABELS = {"Morning (9 AM – 12 PM)", "Afternoon (1 PM – 5 PM)"};

    /** One time to choose. value is "2026-11-19T14:00"; label is "2:00 PM". */
    public record Slot(String value, String label) {
    }

    /** The times on one date. label is "Today", "Tomorrow" or "Fri 21 Nov". */
    public record Day(LocalDate date, String label, List<Slot> slots) {
    }

    private final boolean hoursKnown;
    private final List<Day> days;

    private VisitSlots(boolean hoursKnown, List<Day> days) {
        this.hoursKnown = hoursKnown;
        this.days = List.copyOf(days);
    }

    /**
     * @param hours the shop's parsed hours, or null if unknown or unsupported
     * @param now   Ontario local time
     */
    public static VisitSlots of(OpeningHours hours, LocalDateTime now, int days) {
        Map<LocalDate, List<Slot>> byDate = new LinkedHashMap<>();
        if (hours != null) {
            for (OpeningHours.Window window : hours.openingsWithin(now, days)) {
                int first = (window.start() + STEP_MINUTES - 1) / STEP_MINUTES * STEP_MINUTES;
                for (int t = first; t + STEP_MINUTES <= window.end(); t += STEP_MINUTES) {
                    byDate.computeIfAbsent(window.date(), d -> new ArrayList<>())
                            .add(new Slot(value(window.date(), t), OpeningHours.clock(t)));
                }
            }
        } else {
            int nowMinute = now.getHour() * 60 + now.getMinute();
            for (int offset = 0; offset <= days; offset++) {
                LocalDate date = now.toLocalDate().plusDays(offset);
                for (int p = 0; p < PARTS.length; p++) {
                    boolean stillTimeToday = offset > 0 || PARTS[p][1] - STEP_MINUTES >= nowMinute;
                    boolean withinLastDay = offset < days || PARTS[p][0] <= nowMinute;
                    if (stillTimeToday && withinLastDay) {
                        byDate.computeIfAbsent(date, d -> new ArrayList<>())
                                .add(new Slot(value(date, PARTS[p][0]), PART_LABELS[p]));
                    }
                }
            }
        }
        List<Day> list = new ArrayList<>();
        byDate.forEach((date, slots) -> list.add(new Day(date, dayLabel(date, now.toLocalDate()), slots)));
        return new VisitSlots(hours != null, list);
    }

    public boolean hoursKnown() {
        return hoursKnown;
    }

    public List<Day> days() {
        return days;
    }

    public boolean isEmpty() {
        return days.isEmpty();
    }

    /** The slot with this value, or null if it is not offered (so a page cannot send any time it likes). */
    public Slot find(String value) {
        for (Day day : days) {
            for (Slot slot : day.slots()) {
                if (slot.value().equals(value)) {
                    return slot;
                }
            }
        }
        return null;
    }

    /** "Today, 2:00 PM", "Fri 21 Nov, Morning (9 AM – 12 PM)". */
    public String describe(String value) {
        for (Day day : days) {
            for (Slot slot : day.slots()) {
                if (slot.value().equals(value)) {
                    return day.label() + ", " + slot.label();
                }
            }
        }
        return value;
    }

    static String dayLabel(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "Today";
        }
        if (date.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.CANADA) + " " + date.getDayOfMonth() + " "
                + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.CANADA).replace(".", "");
    }

    private static String value(LocalDate date, int minute) {
        return date + "T" + String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }
}

package ca.carcareplus.hours;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Weekly opening hours in OpenStreetMap's opening_hours format, and "is it open now?".
 *
 * Supported (covers the vast majority of shops):
 * <pre>
 *   24/7
 *   Mo-Fr 08:00-17:00; Sa 09:00-13:00          days, day ranges, several rules
 *   Mo,We,Fr 09:00-12:00,13:00-17:00           day lists, several time ranges
 *   Mo-Su 18:00-02:00                          past midnight (continues next day)
 *   Su off   /   Su closed                     closed days
 *   09:00-17:00                                no days = every day
 *   Mo-Fr 08:00-18:00; PH off                  public-holiday rules are ignored
 * </pre>
 * A later rule replaces earlier ones for the days it names, as in the OSM
 * specification ("Mo-Sa 08:00-18:00; Sa 09:00-12:00" = Saturday 09:00-12:00).
 *
 * Anything else (months, week numbers, sunrise, "Mo[1]", comments) makes
 * parse() return null: an unknown answer is better than a wrong "Open now".
 *
 * Data structure: for each of the 7 days, a list of [start, end) minute ranges
 * (0 = midnight, 1440 = next midnight). Checking a time is O(r) for r ranges.
 */
public final class OpeningHours {

    static final int DAY_MINUTES = 24 * 60;
    /** "Closes soon" when closing within this many minutes. */
    static final int CLOSING_SOON_MINUTES = 60;

    private static final List<String> DAY_CODES = List.of("mo", "tu", "we", "th", "fr", "sa", "su");
    private static final Pattern DAY_SELECTOR = Pattern.compile(
            "^((?:mo|tu|we|th|fr|sa|su|ph)(?:-(?:mo|tu|we|th|fr|sa|su))?)(?:,((?:mo|tu|we|th|fr|sa|su|ph)(?:-(?:mo|tu|we|th|fr|sa|su))?))*");
    private static final Pattern TIME_RANGE = Pattern.compile("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$");

    /** ranges[day] = list of {start, end} in minutes; day 0 = Monday. */
    private final List<List<int[]>> ranges;
    private final String text;

    private OpeningHours(List<List<int[]>> ranges, String text) {
        this.ranges = ranges;
        this.text = text;
    }

    /** @return the hours, or null if the text is blank or uses a feature we do not support */
    public static OpeningHours parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.trim().toLowerCase(Locale.ROOT).replace("||", ";").replaceAll("\\s+", " ");
        List<List<int[]>> week = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            week.add(new ArrayList<>());
        }
        List<int[]> spill = new ArrayList<>(); // {day, start, end} parts after midnight
        boolean anyRule = false;
        for (String rawRule : cleaned.split(";")) {
            String rule = rawRule.trim();
            if (rule.isEmpty()) {
                continue;
            }
            if (rule.equals("24/7")) {
                for (List<int[]> day : week) {
                    day.clear();
                    day.add(new int[] {0, DAY_MINUTES});
                }
                anyRule = true;
                continue;
            }
            boolean[] days = new boolean[7];
            String rest = rule;
            Matcher selector = DAY_SELECTOR.matcher(rule);
            boolean onlyHolidays = false;
            if (selector.find() && (selector.end() == rule.length() || rule.charAt(selector.end()) == ' ')) {
                String dayText = rule.substring(0, selector.end());
                rest = rule.substring(selector.end()).trim();
                boolean anyDay = false;
                for (String part : dayText.split(",")) {
                    if (part.equals("ph")) {
                        continue;
                    }
                    String[] ends = part.split("-");
                    int from = DAY_CODES.indexOf(ends[0]);
                    int to = ends.length == 2 ? DAY_CODES.indexOf(ends[1]) : from;
                    for (int d = from; ; d = (d + 1) % 7) { // "Sa-Mo" wraps around the week
                        days[d] = true;
                        anyDay = true;
                        if (d == to) {
                            break;
                        }
                    }
                }
                onlyHolidays = !anyDay;
            } else {
                Arrays.fill(days, true);
            }
            if (onlyHolidays) {
                continue; // "PH off": we do not know the holiday calendar
            }
            List<int[]> times = new ArrayList<>();
            if (!rest.isEmpty() && !rest.equals("off") && !rest.equals("closed")) {
                for (String part : rest.split(",")) {
                    Matcher m = TIME_RANGE.matcher(part.trim());
                    if (!m.matches()) {
                        return null; // unsupported syntax
                    }
                    int start = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                    int end = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
                    if (start >= DAY_MINUTES || end > 2 * DAY_MINUTES || m.group(2).compareTo("60") >= 0) {
                        return null;
                    }
                    if (end <= start) {
                        end += DAY_MINUTES; // "22:00-02:00" ends the next day
                    }
                    times.add(new int[] {start, end});
                }
            } else if (rest.isEmpty()) {
                return null; // days without times or "off" ("Mo-Fr") is not meaningful
            }
            for (int d = 0; d < 7; d++) {
                if (!days[d]) {
                    continue;
                }
                week.get(d).clear();
                for (int[] t : times) {
                    week.get(d).add(new int[] {t[0], Math.min(t[1], DAY_MINUTES)});
                    if (t[1] > DAY_MINUTES) {
                        spill.add(new int[] {(d + 1) % 7, 0, t[1] - DAY_MINUTES});
                    }
                }
            }
            anyRule = true;
        }
        if (!anyRule) {
            return null;
        }
        for (int[] s : spill) {
            week.get(s[0]).add(new int[] {s[1], s[2]});
        }
        for (List<int[]> day : week) {
            day.sort((a, b) -> Integer.compare(a[0], b[0]));
        }
        return new OpeningHours(week, text.trim());
    }

    /** The original text, e.g. "Mo-Fr 08:00-17:00". */
    public String text() {
        return text;
    }

    public boolean isOpen(LocalDateTime time) {
        int day = time.getDayOfWeek().getValue() - 1;
        int minute = time.getHour() * 60 + time.getMinute();
        return rangeAt(day, minute) != null;
    }

    private int[] rangeAt(int day, int minute) {
        for (int[] r : ranges.get(day)) {
            if (r[0] <= minute && minute < r[1]) {
                return r;
            }
        }
        return null;
    }

    /** Open or closed at the given local time (Ontario time for our shops), with a label. */
    public Availability availability(LocalDateTime now) {
        int day = now.getDayOfWeek().getValue() - 1;
        int minute = now.getHour() * 60 + now.getMinute();
        int[] open = rangeAt(day, minute);
        if (open != null) {
            // Follow ranges that continue past midnight into the next days
            int closesDay = day;
            int closesAt = open[1];
            int minutesLeft = open[1] - minute;
            for (int step = 0; closesAt == DAY_MINUTES && step < 7; step++) {
                int next = (closesDay + 1) % 7;
                int[] continued = rangeAt(next, 0);
                if (continued == null) {
                    break;
                }
                closesDay = next;
                closesAt = continued[1];
                minutesLeft += continued[1];
            }
            if (closesAt == DAY_MINUTES) {
                return new Availability(Availability.State.OPEN, "Open 24 hours");
            }
            String when = (closesDay == day ? "" : dayLabel(day, closesDay) + " ") + clock(closesAt);
            return minutesLeft <= CLOSING_SOON_MINUTES
                    ? new Availability(Availability.State.CLOSING_SOON, "Closes soon · " + when)
                    : new Availability(Availability.State.OPEN, "Open now · closes " + when);
        }
        for (int step = 0; step <= 7; step++) {
            int d = (day + step) % 7;
            for (int[] r : ranges.get(d)) {
                if (step > 0 || r[0] > minute) {
                    String when = (step == 0 ? "" : dayLabel(day, d) + " ") + clock(r[0]);
                    return new Availability(Availability.State.CLOSED, "Closed · opens " + when);
                }
            }
        }
        return new Availability(Availability.State.CLOSED, "Closed");
    }

    /** One line per day, Monday first: "Mon: 8:00 AM – 5:00 PM", "Sun: Closed". */
    public List<String> weeklySchedule() {
        List<String> lines = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            List<String> parts = new ArrayList<>();
            for (int[] r : ranges.get(d)) {
                parts.add(r[0] == 0 && r[1] == DAY_MINUTES ? "Open 24 hours" : clock(r[0]) + " – " + clock(r[1]));
            }
            lines.add(DayOfWeek.of(d + 1).getDisplayName(TextStyle.SHORT, Locale.CANADA) + ": "
                    + (parts.isEmpty() ? "Closed" : String.join(", ", parts)));
        }
        return lines;
    }

    private static String dayLabel(int today, int day) {
        return day == (today + 1) % 7 ? "tomorrow" : DayOfWeek.of(day + 1).getDisplayName(TextStyle.SHORT, Locale.CANADA);
    }

    /** 510 -> "8:30 AM", 1440 -> "12:00 AM" (midnight). */
    static String clock(int minutes) {
        int m = minutes % DAY_MINUTES;
        int hour = m / 60;
        String suffix = hour < 12 ? "AM" : "PM";
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        return h12 + ":" + String.format(Locale.ROOT, "%02d", m % 60) + " " + suffix;
    }
}

package ca.carcareplus.visits;

import java.util.ArrayList;
import java.util.List;

/**
 * One saved non-priority visit request.
 *
 * CarCare+ does not deliver it: the driver sends {@link #message()} to the shop
 * from their own email, text or phone, and the shop confirms the time.
 *
 * @param id        reference shown to the driver, e.g. "CC-1001"
 * @param createdAt when it was saved (ISO, Ontario time)
 * @param slot      the chosen start, "2026-11-20T14:00"
 * @param slotLabel "Tomorrow, 2:00 PM"
 * @param exactTime false when the shop's hours are unknown (morning / afternoon only)
 * @param status    {@link #REQUESTED} or {@link #CANCELLED}
 */
public record VisitRequest(String id, String createdAt, String shopUrl, String shopTitle, String shopPhone,
        String shopEmail, String slot, String slotLabel, boolean exactTime, String problem, String service,
        String vehicle, String name, String phone, String email, String note, String status) {

    public static final String REQUESTED = "REQUESTED";
    public static final String CANCELLED = "CANCELLED";

    public VisitRequest withStatus(String newStatus) {
        return new VisitRequest(id, createdAt, shopUrl, shopTitle, shopPhone, shopEmail, slot, slotLabel, exactTime,
                problem, service, vehicle, name, phone, email, note, newStatus);
    }

    public boolean isCancelled() {
        return CANCELLED.equals(status);
    }

    /** "Repair request CC-1001: Brake problem, Tomorrow, 2:00 PM" */
    public String subject() {
        return "Repair request " + id + ": " + (blank(service) ? "car repair" : service) + ", " + slotLabel;
    }

    /** The text the driver sends to the shop. */
    public String message() {
        List<String> lines = new ArrayList<>();
        lines.add("Hello " + shopTitle + ",");
        lines.add("");
        lines.add("I found your shop on CarCare+ and would like to book a repair. It is not urgent.");
        lines.add("");
        lines.add("Reference: " + id);
        lines.add("Preferred time: " + slotLabel + (exactTime ? "" : " (please suggest an exact time)"));
        if (!blank(problem) || !blank(service)) {
            lines.add("Problem: " + (blank(problem) ? service
                    : blank(service) ? problem : problem + " (" + service + ")"));
        }
        if (!blank(vehicle)) {
            lines.add("Vehicle: " + vehicle);
        }
        if (!blank(note)) {
            lines.add("Note: " + note);
        }
        lines.add("");
        lines.add("Name: " + name);
        if (!blank(phone)) {
            lines.add("Phone: " + phone);
        }
        if (!blank(email)) {
            lines.add("Email: " + email);
        }
        lines.add("");
        lines.add("Could you confirm this time, or suggest another one in the next 2 days? Thank you.");
        return String.join("\n", lines);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}

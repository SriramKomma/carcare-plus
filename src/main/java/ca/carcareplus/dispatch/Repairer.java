package ca.carcareplus.dispatch;

/**
 * One repairer who can receive repair requests (a line of config/repairers.txt).
 *
 * @param id          short stable name used by the page, e.g. "sriram"
 * @param name        what the customer reads, e.g. "Sriram Komma"
 * @param phone       already normalized to "(XXX) XXX-XXXX"
 * @param area        city the repairer works in
 * @param specialties e.g. "All types of repairs"
 */
public record Repairer(String id, String name, String phone, String area, String specialties) {
}

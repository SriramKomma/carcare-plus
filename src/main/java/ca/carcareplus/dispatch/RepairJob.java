package ca.carcareplus.dispatch;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One repair request sent to several repairers at once ("JOB-1").
 *
 * Immutable: every change makes a new copy (withStatus, withDeclined, ...). JobBoard
 * swaps the copy in while holding its lock, so a reader never sees half a change.
 *
 * <pre>
 *            first accept                  repairer: "done"
 *   OPEN ─────────────────────▶ ASSIGNED ───────────────────▶ DONE
 *    │
 *    ├── every invited repairer declines ──▶ NO_TAKERS
 *    ├── customer cancels ─────────────────▶ CANCELLED
 *    └── nobody answers in time ───────────▶ EXPIRED
 * </pre>
 *
 * @param invited     ids of the repairers the request was sent to, in the order picked
 * @param declined    ids of the invited repairers who said no
 * @param assignedTo  id of the repairer who won the job, or null
 * @param assignedAt  when it was accepted, or null
 * @param closedAt    when it reached NO_TAKERS, CANCELLED, EXPIRED or DONE, or null
 */
public record RepairJob(String id, Instant createdAt, Instant expiresAt,
                        String customerName, String customerPhone, String area, String vehicle,
                        String problem, String service,
                        List<String> invited, Set<String> declined,
                        Status status, String assignedTo, Instant assignedAt, Instant closedAt) {

    public enum Status {
        OPEN, ASSIGNED, DONE, NO_TAKERS, CANCELLED, EXPIRED;

        /** True when nothing more can happen to the job. */
        public boolean isFinal() {
            return this == DONE || this == NO_TAKERS || this == CANCELLED || this == EXPIRED;
        }
    }

    public RepairJob {
        invited = List.copyOf(invited);
        declined = Set.copyOf(declined);
    }

    public boolean isInvited(String repairerId) {
        return invited.contains(repairerId);
    }

    public boolean hasDeclined(String repairerId) {
        return declined.contains(repairerId);
    }

    RepairJob withDeclined(String repairerId) {
        Set<String> more = new LinkedHashSet<>(declined);
        more.add(repairerId);
        return new RepairJob(id, createdAt, expiresAt, customerName, customerPhone, area, vehicle, problem, service,
                invited, more, status, assignedTo, assignedAt, closedAt);
    }

    RepairJob withAssigned(String repairerId, Instant now) {
        return new RepairJob(id, createdAt, expiresAt, customerName, customerPhone, area, vehicle, problem, service,
                invited, declined, Status.ASSIGNED, repairerId, now, null);
    }

    /** For the final states (NO_TAKERS, CANCELLED, EXPIRED, DONE). */
    RepairJob withClosed(Status finalStatus, Instant now) {
        return new RepairJob(id, createdAt, expiresAt, customerName, customerPhone, area, vehicle, problem, service,
                invited, declined, finalStatus, assignedTo, assignedAt, now);
    }
}

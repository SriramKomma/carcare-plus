package ca.carcareplus.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.carcareplus.exceptions.InvalidInputException;

/**
 * First-come, first-served dispatch: one request goes to several repairers, and
 * the first one to accept gets the job (like a ride request sent to many drivers).
 *
 * <h2>The lock: why two repairers can never both win</h2>
 * Two repairers may press "Accept" in the same millisecond; the web server then
 * runs both requests at the same time on two threads. accept() is synchronized,
 * so only one thread at a time can be inside it. The first one in sees OPEN,
 * assigns the job and leaves; the second one then sees ASSIGNED and loses. The
 * check ("is it still open?") and the change ("assign it to me") happen as one
 * step that no other thread can get between. Without synchronized, both could
 * read OPEN before either wrote ASSIGNED, and both would think they had won.
 * JobBoardTest starts many threads that accept at once and checks that exactly
 * one wins.
 *
 * <h2>How windows notice changes: the version number</h2>
 * Every change adds 1 to {@code version}. Each open page asks about every half
 * second "anything newer than version 17?". If not, the answer is tiny. This is
 * called polling; it was chosen over a held-open connection (Server-Sent Events)
 * because browsers allow only 6 open connections per site across ALL windows, and
 * the demo opens 5 or more windows.
 *
 * <h2>Online / offline</h2>
 * A repairer counts as online while their page keeps asking (seen in the last
 * {@link #ONLINE_FOR}). Closing the window simply stops the questions.
 *
 * <h2>Time limits</h2>
 * A request nobody accepts within {@link #ANSWER_TIME} becomes EXPIRED. There is no
 * timer thread: every call first tidies up (expires old requests, marks silent
 * repairers offline), and the pages call often, so it happens on time.
 *
 * Jobs are kept in memory only: restarting the server clears them, which is what a
 * demo needs (and no customer phone numbers end up in a file).
 */
public final class JobBoard {

    /** How long repairers have to accept before the request expires. */
    public static final Duration ANSWER_TIME = Duration.ofMinutes(3);
    /** A repairer whose page has not asked for this long is shown as offline. */
    public static final Duration ONLINE_FOR = Duration.ofSeconds(4);
    /** Finished jobs beyond this many are forgotten, oldest first (memory stays small). */
    static final int MAX_KEPT = 200;
    static final String PREFIX = "JOB-";

    /** What a customer sends; checked by the caller (DispatchApi) before it gets here. */
    public record JobRequest(String customerName, String customerPhone, String area, String vehicle,
                             String problem, String service, List<String> invited) {
    }

    /**
     * The result of accept/reject/finish.
     *
     * @param ok      true if the action happened (for accept: you won the job)
     * @param job     the job as it is now
     * @param message why it did not happen, or "" when ok
     */
    public record Outcome(boolean ok, RepairJob job, String message) {
    }

    /** Everything a page needs, copied while holding the lock so it is consistent. */
    public record Snapshot(long version, Instant now, List<RepairJob> jobs, Set<String> online) {

        /** @return the job, or null */
        public RepairJob job(String id) {
            return jobs.stream().filter(j -> j.id().equals(id)).findFirst().orElse(null);
        }
    }

    /**
     * Told about every change to a job, whichever way it happened (web page, SMS reply,
     * time limit). SmsDispatcher uses it to text the repairers.
     *
     * Called while the board's lock is held, so it must be quick and must not wait for
     * the network: it should only hand the work to another thread.
     */
    public interface Listener {
        /** @param before the job before the change, or null for a new job */
        void jobChanged(RepairJob before, RepairJob after);
    }

    private final Clock clock;
    private Listener listener = (before, after) -> { };
    /** In posting order, oldest first. */
    private final Map<String, RepairJob> jobs = new LinkedHashMap<>();
    private final Map<String, Instant> lastSeen = new HashMap<>();
    private final Set<String> online = new LinkedHashSet<>();
    private long version = 1;
    private int nextNumber = 1;

    public JobBoard(Clock clock) {
        this.clock = clock;
    }

    /** Sends a new request to the invited repairers. */
    public synchronized RepairJob post(JobRequest request) {
        tidy();
        if (request.invited() == null || request.invited().isEmpty()) {
            throw new InvalidInputException("Pick at least one repairer.");
        }
        Instant now = clock.instant();
        RepairJob job = new RepairJob(PREFIX + nextNumber++, now, now.plus(ANSWER_TIME),
                request.customerName(), request.customerPhone(), request.area(), request.vehicle(),
                request.problem(), request.service(), List.copyOf(new LinkedHashSet<>(request.invited())), Set.of(),
                RepairJob.Status.OPEN, null, null, null);
        forgetOldJobs();
        store(null, job);
        return job;
    }

    /**
     * The repairer accepts. Only the first accept on an OPEN job wins; everyone after
     * that gets ok = false and a message saying why.
     */
    public synchronized Outcome accept(String jobId, String repairerId) {
        tidy();
        RepairJob job = find(jobId);
        if (!job.isInvited(repairerId)) {
            return new Outcome(false, job, "This request was not sent to you.");
        }
        if (repairerId.equals(job.assignedTo())) {
            return new Outcome(true, job, ""); // pressed twice: still yours
        }
        if (job.hasDeclined(repairerId)) {
            return new Outcome(false, job, "You already declined this request.");
        }
        if (job.status() != RepairJob.Status.OPEN) {
            return new Outcome(false, job, closedMessage(job));
        }
        // Check and change in one step, inside the lock: this is the first-come, first-served rule
        RepairJob won = job.withAssigned(repairerId, clock.instant());
        store(job, won);
        return new Outcome(true, won, "");
    }

    /** The repairer says no. When every invited repairer has said no, the job is NO_TAKERS. */
    public synchronized Outcome reject(String jobId, String repairerId) {
        tidy();
        RepairJob job = find(jobId);
        if (!job.isInvited(repairerId)) {
            return new Outcome(false, job, "This request was not sent to you.");
        }
        if (job.hasDeclined(repairerId)) {
            return new Outcome(true, job, ""); // pressed twice
        }
        if (job.status() != RepairJob.Status.OPEN) {
            return new Outcome(false, job, closedMessage(job));
        }
        RepairJob declined = job.withDeclined(repairerId);
        if (declined.declined().containsAll(declined.invited())) {
            declined = declined.withClosed(RepairJob.Status.NO_TAKERS, clock.instant());
        }
        store(job, declined);
        return new Outcome(true, declined, "");
    }

    /** The customer cancels a request that is still open or assigned. */
    public synchronized RepairJob cancel(String jobId) {
        tidy();
        RepairJob job = find(jobId);
        if (job.status() == RepairJob.Status.CANCELLED) {
            return job;
        }
        if (job.status().isFinal()) {
            throw new InvalidInputException("This request is already " + job.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ".");
        }
        RepairJob cancelled = job.withClosed(RepairJob.Status.CANCELLED, clock.instant());
        store(job, cancelled);
        return cancelled;
    }

    /** The assigned repairer marks the repair as done. */
    public synchronized Outcome finish(String jobId, String repairerId) {
        tidy();
        RepairJob job = find(jobId);
        if (!repairerId.equals(job.assignedTo())) {
            return new Outcome(false, job, "Only the repairer who accepted this job can finish it.");
        }
        if (job.status() == RepairJob.Status.DONE) {
            return new Outcome(true, job, "");
        }
        if (job.status() != RepairJob.Status.ASSIGNED) {
            return new Outcome(false, job, closedMessage(job));
        }
        RepairJob done = job.withClosed(RepairJob.Status.DONE, clock.instant());
        store(job, done);
        return new Outcome(true, done, "");
    }

    /** @param listener told about every job change (null = nobody) */
    public synchronized void setListener(Listener listener) {
        this.listener = listener == null ? (before, after) -> { } : listener;
    }

    /** Something the pages show changed outside the board (e.g. an SMS was delivered): pages redraw. */
    public synchronized void touch() {
        changed();
    }

    /** A repairer's page is open and asking; marks them online. */
    public synchronized void seen(String repairerId) {
        lastSeen.put(repairerId, clock.instant());
        if (online.add(repairerId)) {
            changed();
        }
        tidy();
    }

    /** A copy of everything, newest job first. */
    public synchronized Snapshot snapshot() {
        tidy();
        List<RepairJob> newestFirst = new ArrayList<>(jobs.values());
        java.util.Collections.reverse(newestFirst);
        return new Snapshot(version, clock.instant(), List.copyOf(newestFirst), Set.copyOf(online));
    }

    public synchronized long version() {
        tidy();
        return version;
    }

    /** @throws InvalidInputException if there is no such job */
    private RepairJob find(String jobId) {
        RepairJob job = jobId == null ? null : jobs.get(jobId.trim().toUpperCase(java.util.Locale.ROOT));
        if (job == null) {
            throw new InvalidInputException("No repair request " + jobId + ".");
        }
        return job;
    }

    static String closedMessage(RepairJob job) {
        return switch (job.status()) {
            case ASSIGNED, DONE -> "Too late: another repairer already accepted this request.";
            case CANCELLED -> "The customer cancelled this request.";
            case EXPIRED -> "This request expired.";
            case NO_TAKERS -> "Every repairer declined this request.";
            case OPEN -> "";
        };
    }

    /** Expires requests nobody accepted in time and marks silent repairers offline. */
    private void tidy() {
        Instant now = clock.instant();
        for (RepairJob job : new ArrayList<>(jobs.values())) {
            if (job.status() == RepairJob.Status.OPEN && !now.isBefore(job.expiresAt())) {
                store(job, job.withClosed(RepairJob.Status.EXPIRED, now));
            }
        }
        for (Iterator<String> it = online.iterator(); it.hasNext(); ) {
            String id = it.next();
            if (Duration.between(lastSeen.get(id), now).compareTo(ONLINE_FOR) > 0) {
                it.remove();
                changed();
            }
        }
    }

    /** Makes room for one more job. */
    private void forgetOldJobs() {
        Iterator<RepairJob> it = jobs.values().iterator();
        while (jobs.size() >= MAX_KEPT && it.hasNext()) {
            if (it.next().status().isFinal()) {
                it.remove();
            }
        }
    }

    /** Saves the new copy of a job, raises the version and tells the listener (before = null: a new job). */
    private void store(RepairJob before, RepairJob after) {
        jobs.put(after.id(), after);
        changed();
        listener.jobChanged(before, after);
    }

    private void changed() {
        version++;
    }
}

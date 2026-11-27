package ca.carcareplus.app;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import ca.carcareplus.dispatch.JobBoard;
import ca.carcareplus.dispatch.RepairJob;
import ca.carcareplus.dispatch.Repairer;
import ca.carcareplus.dispatch.RepairerDirectory;
import ca.carcareplus.dispatch.SmsDispatcher;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.models.VehicleType;
import ca.carcareplus.validation.DataValidator;

/**
 * The live dispatch demo (page: /live-demo): a customer sends one repair request to
 * several repairers, and the first repairer to accept gets the job. JobBoard holds
 * the rules; this class checks the form fields and turns jobs into what each page
 * shows. Like WebApi it knows nothing about HTTP, so it is tested without a network.
 *
 * <pre>
 * GET  /api/repairers                                   the repairers and who is online
 * POST /api/jobs         name, phone, area, vehicle, q, details, repairers=sriram,sarvesh
 * POST /api/jobs/accept  id, repairer                   first accept wins
 * POST /api/jobs/reject  id, repairer
 * POST /api/jobs/done    id, repairer                   the assigned repairer finished
 * POST /api/jobs/cancel  id                             the customer cancels
 * GET  /api/jobs/live    since=17 &amp; (job=JOB-1 | repairer=sriram)
 *      the page's "anything new?" question, asked about every half second:
 *      {changed:false, version:17} when nothing changed, else the whole view
 * </pre>
 *
 * Privacy: before accepting, a repairer sees the problem, area and the customer's
 * first name only. The full name and phone number are shown to the one repairer
 * who wins the job (as ride apps do).
 *
 * Demo-level security: there are no passwords. The server only listens on this
 * computer (127.0.0.1), and "?as=sriram" simply chooses whose window it is.
 */
public final class DispatchApi {

    static final int MAX_NAME = 60;
    static final int MAX_AREA = 50;
    /** Most requests a repairer's page lists. */
    static final int JOBS_SHOWN = 20;

    private final RepairerDirectory repairers;
    private final JobBoard board;
    /** (vehicle, problem) -> service name such as "Battery problem", or "" (WebApi.serviceFor). */
    private final BiFunction<String, String, String> serviceFor;
    /** Texts the repairers; null = no SMS (some tests). */
    private final SmsDispatcher sms;

    public DispatchApi(RepairerDirectory repairers, JobBoard board, BiFunction<String, String, String> serviceFor) {
        this(repairers, board, serviceFor, null);
    }

    /** @param sms texts the repairers (it listens to the board), or null for no SMS */
    public DispatchApi(RepairerDirectory repairers, JobBoard board, BiFunction<String, String, String> serviceFor,
                       SmsDispatcher sms) {
        if (repairers == null || board == null || serviceFor == null) {
            throw new InvalidInputException("DispatchApi needs repairers, a job board and a service finder.");
        }
        this.repairers = repairers;
        this.board = board;
        this.serviceFor = serviceFor;
        this.sms = sms;
    }

    /** GET /api/repairers */
    public List<Map<String, Object>> repairers() {
        return repairerList(board.snapshot().online());
    }

    /** POST /api/jobs -- sends the request to every chosen repairer at once. */
    public Map<String, Object> post(Map<String, String> params) {
        if (repairers.size() == 0) {
            throw new InvalidInputException("No repairers are set up (config/repairers.txt).");
        }
        String name = clean(params.get("name"));
        if (name.isEmpty()) {
            throw new InvalidInputException("Please enter your name.");
        }
        if (name.length() > MAX_NAME) {
            throw new InvalidInputException("The name is too long (at most " + MAX_NAME + " characters).");
        }
        String phone = DataValidator.normalizePhone(clean(params.get("phone")));
        if (phone == null) {
            throw new InvalidInputException("Please enter a Canadian phone number, e.g. 519 555 0100, so the repairer can call you.");
        }
        String area = clean(params.get("area"));
        if (area.length() > MAX_AREA) {
            throw new InvalidInputException("The area is too long (at most " + MAX_AREA + " characters).");
        }
        String vehicleName = clean(params.get("vehicle"));
        VehicleType vehicle = vehicleName.isEmpty() ? null : VehicleType.fromUserInput(vehicleName); // optional
        String picked = clean(params.get("q"));
        String details = clean(params.get("details"));
        if (picked.isEmpty() && details.isEmpty()) {
            throw new InvalidInputException("Please say what is wrong with the car.");
        }
        String service = picked.isEmpty() ? "" : serviceFor.apply(vehicleName, picked);
        String problem = picked;
        if (!details.isEmpty() && !details.equalsIgnoreCase(picked)) {
            problem = picked.isEmpty() ? details : picked + " - " + details;
        }
        if (problem.length() > WebApi.MAX_PROBLEM) {
            problem = problem.substring(0, WebApi.MAX_PROBLEM);
        }
        List<String> invited = new ArrayList<>();
        for (String id : params.getOrDefault("repairers", "").split(",")) {
            if (id.isBlank()) {
                continue;
            }
            Repairer repairer = repairers.get(id);
            if (repairer == null) {
                throw new InvalidInputException("Unknown repairer '" + id.trim() + "'.");
            }
            if (!invited.contains(repairer.id())) {
                invited.add(repairer.id());
            }
        }
        if (invited.isEmpty()) {
            throw new InvalidInputException("Pick at least one repairer.");
        }
        RepairJob job = board.post(new JobBoard.JobRequest(name, phone, area, vehicle == null ? "" : vehicle.displayName(),
                problem, service, invited));
        JobBoard.Snapshot snapshot = board.snapshot();
        return customerJob(snapshot.job(job.id()), snapshot);
    }

    /** POST /api/jobs/accept -- {accepted, message, job} where job is the repairer's view of it. */
    public Map<String, Object> accept(Map<String, String> params) {
        Repairer me = repairer(params.get("repairer"));
        JobBoard.Outcome outcome = board.accept(params.get("id"), me.id());
        return outcomeMap("accepted", outcome, me);
    }

    /** POST /api/jobs/reject */
    public Map<String, Object> reject(Map<String, String> params) {
        Repairer me = repairer(params.get("repairer"));
        JobBoard.Outcome outcome = board.reject(params.get("id"), me.id());
        return outcomeMap("declined", outcome, me);
    }

    /** POST /api/jobs/done */
    public Map<String, Object> done(Map<String, String> params) {
        Repairer me = repairer(params.get("repairer"));
        JobBoard.Outcome outcome = board.finish(params.get("id"), me.id());
        return outcomeMap("done", outcome, me);
    }

    /** POST /api/jobs/cancel -- the customer's view of the cancelled job. */
    public Map<String, Object> cancel(Map<String, String> params) {
        RepairJob job = board.cancel(params.get("id"));
        JobBoard.Snapshot snapshot = board.snapshot();
        return customerJob(snapshot.job(job.id()), snapshot);
    }

    /**
     * GET /api/jobs/live?since=17&repairer=sriram  (a repairer's window)
     * GET /api/jobs/live?since=17&job=JOB-1        (the customer's window; job may be blank)
     */
    public Map<String, Object> live(Map<String, String> params) {
        String repairerId = clean(params.get("repairer"));
        Repairer me = repairerId.isEmpty() ? null : repairer(repairerId);
        if (me != null) {
            board.seen(me.id()); // first, so this window counts as online in the answer
        }
        long since = parseSince(params.get("since"));
        JobBoard.Snapshot snapshot = board.snapshot();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("version", snapshot.version());
        answer.put("now", snapshot.now().toEpochMilli());
        if (since == snapshot.version()) {
            answer.put("changed", false);
            return answer;
        }
        answer.put("changed", true);
        answer.put("answerSeconds", JobBoard.ANSWER_TIME.toSeconds());
        answer.put("sms", smsInfo());
        if (me != null) {
            answer.put("me", repairerMap(me, snapshot.online()));
            List<Map<String, Object>> jobs = new ArrayList<>();
            for (RepairJob job : snapshot.jobs()) {
                if (job.isInvited(me.id()) && jobs.size() < JOBS_SHOWN) {
                    jobs.add(repairerJob(job, me));
                }
            }
            answer.put("jobs", jobs);
        } else {
            answer.put("repairers", repairerList(snapshot.online()));
            String jobId = clean(params.get("job"));
            RepairJob job = jobId.isEmpty() ? null : snapshot.job(jobId.toUpperCase(java.util.Locale.ROOT));
            answer.put("job", job == null ? null : customerJob(job, snapshot));
            if (!jobId.isEmpty() && job == null) {
                answer.put("message", "Request " + jobId + " is no longer on the board (the server was restarted).");
            }
        }
        return answer;
    }

    /** {mode: "off" | "simulated" | "real", description} for the pages. */
    Map<String, Object> smsInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mode", sms == null ? "off" : sms.gateway().isSimulated() ? "simulated" : "real");
        info.put("description", sms == null ? "SMS is off" : sms.gateway().description());
        return info;
    }

    // ------------------------------------------------------------------ views

    /** What the customer sees: every invited repairer's answer, and the winner's contact details. */
    Map<String, Object> customerJob(RepairJob job, JobBoard.Snapshot snapshot) {
        Map<String, Object> item = jobBasics(job);
        item.put("customerName", job.customerName());
        item.put("customerPhone", job.customerPhone());
        List<Map<String, Object>> invited = new ArrayList<>();
        for (String id : job.invited()) {
            Repairer repairer = repairers.get(id);
            Map<String, Object> row = repairer == null ? new LinkedHashMap<>() : repairerMap(repairer, snapshot.online());
            row.put("id", id);
            row.put("answer", answerOf(job, id));
            SmsDispatcher.Delivery delivery = sms == null ? null : sms.delivery(job.id(), id);
            if (delivery != null) {
                row.put("sms", delivery.name()); // SENDING | SENT | FAILED
                String error = sms.deliveryError(job.id(), id);
                if (error != null) {
                    row.put("smsError", error);
                }
            }
            invited.add(row);
        }
        item.put("invited", invited);
        Repairer assigned = repairers.get(job.assignedTo());
        if (assigned != null) {
            Map<String, Object> winner = repairerMap(assigned, snapshot.online());
            winner.put("acceptedInSeconds", secondsBetween(job.createdAt(), job.assignedAt()));
            item.put("assigned", winner);
        }
        item.put("headline", customerHeadline(job, assigned));
        return item;
    }

    /** What one repairer sees: the request, their own answer, and the customer's contact only if they won. */
    Map<String, Object> repairerJob(RepairJob job, Repairer me) {
        Map<String, Object> item = jobBasics(job);
        String mine = mineOf(job, me.id());
        item.put("mine", mine);
        boolean won = mine.equals("WON") || mine.equals("DONE");
        item.put("customerName", won ? job.customerName() : firstName(job.customerName()));
        if (won) {
            item.put("customerPhone", job.customerPhone());
        }
        item.put("invitedCount", job.invited().size());
        item.put("headline", repairerHeadline(mine));
        return item;
    }

    private Map<String, Object> jobBasics(RepairJob job) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", job.id());
        item.put("status", job.status().name());
        item.put("problem", job.problem());
        item.put("service", job.service());
        item.put("vehicle", job.vehicle());
        item.put("area", job.area());
        item.put("createdAt", job.createdAt().toEpochMilli());
        item.put("expiresAt", job.expiresAt().toEpochMilli());
        return item;
    }

    /** waiting | declined | accepted | no answer */
    static String answerOf(RepairJob job, String repairerId) {
        if (repairerId.equals(job.assignedTo())) {
            return "accepted";
        }
        if (job.hasDeclined(repairerId)) {
            return "declined";
        }
        return job.status() == RepairJob.Status.OPEN ? "waiting" : "no answer";
    }

    /** NEW (can still answer) | DECLINED | WON | DONE | TAKEN (someone else won) | CANCELLED | EXPIRED */
    static String mineOf(RepairJob job, String repairerId) {
        if (repairerId.equals(job.assignedTo())) {
            return job.status() == RepairJob.Status.DONE ? "DONE" : "WON";
        }
        if (job.hasDeclined(repairerId)) {
            return "DECLINED";
        }
        return switch (job.status()) {
            case OPEN -> "NEW";
            case ASSIGNED, DONE -> "TAKEN";
            case CANCELLED -> "CANCELLED";
            case EXPIRED, NO_TAKERS -> "EXPIRED";
        };
    }

    private static String customerHeadline(RepairJob job, Repairer assigned) {
        String who = assigned == null ? "A repairer" : assigned.name();
        return switch (job.status()) {
            case OPEN -> "Waiting for a repairer to accept…";
            case ASSIGNED -> who + " accepted your request";
            case DONE -> who + " finished the repair";
            case NO_TAKERS -> "Every repairer declined. Try again or pick other repairers.";
            case EXPIRED -> "No repairer accepted within " + JobBoard.ANSWER_TIME.toMinutes() + " minutes.";
            case CANCELLED -> "You cancelled this request.";
        };
    }

    private static String repairerHeadline(String mine) {
        return switch (mine) {
            case "NEW" -> "New repair request";
            case "WON" -> "You got this job";
            case "DONE" -> "Job done";
            case "DECLINED" -> "You declined";
            case "TAKEN" -> "Already assigned to another repairer";
            case "CANCELLED" -> "Cancelled by the customer";
            default -> "Expired";
        };
    }

    private Map<String, Object> outcomeMap(String key, JobBoard.Outcome outcome, Repairer me) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put(key, outcome.ok());
        answer.put("message", outcome.message());
        answer.put("job", repairerJob(outcome.job(), me));
        return answer;
    }

    private List<Map<String, Object>> repairerList(Set<String> online) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Repairer repairer : repairers.all()) {
            list.add(repairerMap(repairer, online));
        }
        return list;
    }

    private static Map<String, Object> repairerMap(Repairer repairer, Set<String> online) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", repairer.id());
        item.put("name", repairer.name());
        item.put("phone", repairer.phone());
        item.put("area", repairer.area());
        item.put("specialties", repairer.specialties());
        item.put("online", online.contains(repairer.id()));
        return item;
    }

    /** @throws InvalidInputException for an unknown repairer */
    private Repairer repairer(String id) {
        Repairer repairer = repairers.get(id);
        if (repairer == null) {
            throw new InvalidInputException("Unknown repairer '" + clean(id) + "'.");
        }
        return repairer;
    }

    private static long parseSince(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new InvalidInputException("'since' must be a number.");
        }
    }

    private static double secondsBetween(Instant from, Instant to) {
        return to == null ? 0 : Math.round(java.time.Duration.between(from, to).toMillis() / 100.0) / 10.0;
    }

    private static String firstName(String name) {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }
}

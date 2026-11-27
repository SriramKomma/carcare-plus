package ca.carcareplus.dispatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.carcareplus.exceptions.CarCareException;
import ca.carcareplus.exceptions.InvalidInputException;
import ca.carcareplus.sms.SmsGateway;

/**
 * Repair dispatch by text message: no app and no login for the repairers.
 *
 * <pre>
 *  customer posts JOB-7 ──▶ SMS to every invited repairer:
 *                           "CarCare+ JOB-7: Battery problem ... Reply ACCEPTED to take it"
 *  Sarvesh replies "ACCEPTED" ──▶ board.accept(JOB-7, sarvesh)      (the same synchronized
 *                                  first one wins                    method the web page uses)
 *        ├─▶ Sarvesh: "You got JOB-7. Customer: Alex Morgan, (519) 555-0100 ..."
 *        ├─▶ everyone else: "JOB-7 has been assigned to another repairer."
 *        └─▶ the customer's page shows Sarvesh within half a second (version number)
 *  Sriram replies "ACCEPTED" a moment later ──▶ "Sorry, JOB-7 was already assigned ..."
 * </pre>
 *
 * <h2>Who replied?</h2>
 * The SMS arrives with the sender's number ("+15195550101"); RepairerDirectory.byPhone
 * turns it into the repairer. A number that is not in config/repairers.txt is refused.
 *
 * <h2>Which request?</h2>
 * "ACCEPTED" means the repairer's one open request. If they have several open at
 * once, the reply asks them to add its number ("ACCEPTED 7"). Upper/lower case,
 * punctuation and "ACCEPT", "YES", "NO", "DECLINE" are all understood.
 *
 * <h2>Threads</h2>
 * Sending a real SMS takes up to a second, so it never happens on the board's lock or
 * on a page request: every message is handed to {@code executor} (one thread in the
 * server, so a repairer's messages arrive in the order they were written). The board
 * tells us about changes through JobBoard.Listener, whichever way they happened, so
 * a job accepted on the web page sends the same texts as one accepted by SMS.
 */
public final class SmsDispatcher implements JobBoard.Listener {

    private static final Logger LOG = Logger.getLogger(SmsDispatcher.class.getName());

    static final Pattern NUMBER = Pattern.compile("\\b(?:JOB\\s*-?\\s*)?(\\d{1,6})\\b");
    static final List<String> YES = List.of("ACCEPTED", "ACCEPT", "YES", "Y", "OK", "TAKE");
    /** Opt-out words that the SMS provider handles; we must not answer them. */
    static final List<String> CARRIER_WORDS = List.of("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT",
            "START", "UNSTOP", "HELP", "INFO");
    static final List<String> NO = List.of("NO", "N", "DECLINE", "DECLINED", "REJECT", "REJECTED", "PASS");

    /** What happened to an invitation SMS (shown to the customer next to each repairer). */
    public enum Delivery { SENDING, SENT, FAILED }

    private final SmsGateway gateway;
    private final RepairerDirectory repairers;
    private final JobBoard board;
    private final Executor executor;
    /** "JOB-7/sriram" -> what happened to that invitation */
    private final Map<String, Delivery> deliveries = new ConcurrentHashMap<>();
    private final Map<String, String> deliveryErrors = new ConcurrentHashMap<>();

    /**
     * Also registers itself as the board's listener.
     *
     * @param executor runs the sending; the server uses one background thread, tests use Runnable::run
     */
    public SmsDispatcher(SmsGateway gateway, RepairerDirectory repairers, JobBoard board, Executor executor) {
        if (gateway == null || repairers == null || board == null || executor == null) {
            throw new InvalidInputException("SmsDispatcher needs a gateway, repairers, a board and an executor.");
        }
        this.gateway = gateway;
        this.repairers = repairers;
        this.board = board;
        this.executor = executor;
        board.setListener(this);
    }

    public SmsGateway gateway() {
        return gateway;
    }

    // ------------------------------------------------------------ outgoing texts

    /** Called by the board, inside its lock: only decides what to send, then hands it over. */
    @Override
    public void jobChanged(RepairJob before, RepairJob after) {
        if (before == null) {
            for (String id : after.invited()) {
                deliveries.put(key(after.id(), id), Delivery.SENDING);
                sendLater(id, invitation(after), after.id());
            }
            return;
        }
        if (before.status() == after.status()) {
            return; // a decline that leaves the job open: nothing to tell anyone
        }
        switch (after.status()) {
            case ASSIGNED -> {
                sendLater(after.assignedTo(), wonMessage(after), null);
                for (String id : stillWaiting(after)) {
                    sendLater(id, "CarCare+ " + after.id() + " has been assigned to another repairer. No need to reply.", null);
                }
            }
            case CANCELLED -> {
                List<String> told = stillWaiting(after);
                if (after.assignedTo() != null) {
                    told.add(after.assignedTo());
                }
                for (String id : told) {
                    sendLater(id, "CarCare+ " + after.id() + " was cancelled by the customer. No need to reply.", null);
                }
            }
            case EXPIRED -> {
                for (String id : stillWaiting(after)) {
                    sendLater(id, "CarCare+ " + after.id() + " expired: nobody accepted within "
                            + JobBoard.ANSWER_TIME.toMinutes() + " minutes. No need to reply.", null);
                }
            }
            default -> { } // NO_TAKERS (everyone said no already), DONE
        }
    }

    static String invitation(RepairJob job) {
        StringBuilder text = new StringBuilder("CarCare+ repair request ").append(job.id()).append(": ");
        text.append(job.service().isEmpty() ? job.problem() : job.service() + " (" + job.problem() + ")");
        text.append(". ").append(job.vehicle());
        if (!job.area().isEmpty()) {
            text.append(", ").append(job.area());
        }
        text.append(". Customer: ").append(firstName(job.customerName()));
        text.append(". Reply ACCEPTED to take it - the first reply gets the job. Reply NO to pass.");
        return text.toString();
    }

    static String wonMessage(RepairJob job) {
        return "You got " + job.id() + ". Customer: " + job.customerName() + ", " + job.customerPhone()
                + (job.area().isEmpty() ? "" : ", " + job.area()) + ". Problem: " + job.problem()
                + ". Please call them now.";
    }

    /** Invited, not the winner, and did not say no: they are still waiting to hear. */
    private static List<String> stillWaiting(RepairJob job) {
        List<String> ids = new ArrayList<>();
        for (String id : job.invited()) {
            if (!id.equals(job.assignedTo()) && !job.hasDeclined(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** @param invitationOf the job id when this is an invitation (its delivery is shown), else null */
    private void sendLater(String repairerId, String text, String invitationOf) {
        Repairer repairer = repairers.get(repairerId);
        if (repairer == null) {
            return;
        }
        executor.execute(() -> {
            try {
                gateway.send(repairer.phone(), text);
                if (invitationOf != null) {
                    deliveries.put(key(invitationOf, repairerId), Delivery.SENT);
                }
            } catch (CarCareException | RuntimeException e) {
                LOG.log(Level.WARNING, "SMS to " + repairer.name() + " failed: " + e.getMessage());
                if (invitationOf != null) {
                    deliveries.put(key(invitationOf, repairerId), Delivery.FAILED);
                    deliveryErrors.put(key(invitationOf, repairerId), e.getMessage());
                }
            }
            if (invitationOf != null) {
                board.touch(); // the customer's page shows "SMS sent" / "SMS failed"
            }
        });
    }

    /** @return what happened to the invitation SMS, or null if none was sent */
    public Delivery delivery(String jobId, String repairerId) {
        return deliveries.get(key(jobId, repairerId));
    }

    /** @return why the invitation could not be sent, or null */
    public String deliveryError(String jobId, String repairerId) {
        return deliveryErrors.get(key(jobId, repairerId));
    }

    private static String key(String jobId, String repairerId) {
        return jobId + "/" + repairerId;
    }

    // ------------------------------------------------------------- incoming texts

    /**
     * A text arrived from {@code fromPhone}. Acts on it and texts back when there is
     * something to say. (Winning is told by jobChanged, so it is not repeated here.)
     *
     * @return the text sent back, or "" when the reply is the "You got JOB-7" message
     */
    public String received(String fromPhone, String body) {
        Repairer me = repairers.byPhone(fromPhone);
        if (me == null) {
            String answer = "This number is not registered as a CarCare+ repairer.";
            replyTo(fromPhone, answer);
            return answer;
        }
        String answer = answerFor(me, body == null ? "" : body);
        if (!answer.isEmpty()) {
            sendLater(me.id(), answer, null);
        }
        return answer;
    }

    private String answerFor(Repairer me, String body) {
        String words = body.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]+", " ").trim();
        String first = words.isEmpty() ? "" : words.split(" ")[0];
        if (CARRIER_WORDS.contains(first)) {
            return ""; // STOP, HELP ...: Twilio and the phone company answer these themselves
        }
        boolean yes = YES.contains(first);
        boolean no = NO.contains(first);
        if (!yes && !no) {
            return "CarCare+: reply ACCEPTED to take a request, or NO to pass. "
                    + "To choose one of several, add its number, e.g. ACCEPTED 7.";
        }
        String jobId;
        Matcher number = NUMBER.matcher(words.substring(first.length()));
        if (number.find()) {
            jobId = JobBoard.PREFIX + Integer.parseInt(number.group(1));
        } else {
            List<String> open = openFor(me);
            if (open.isEmpty()) {
                // A late ACCEPTED is about their latest request, which someone else just took:
                // say so, rather than "nothing open"
                String latest = yes ? latestFor(me) : null;
                if (latest == null) {
                    return "CarCare+: you have no open requests right now.";
                }
                open = List.of(latest);
            }
            if (open.size() > 1) {
                return "CarCare+: you have " + open.size() + " open requests (" + String.join(", ", open)
                        + "). Reply " + (yes ? "ACCEPTED" : "NO") + " with the number, e.g. "
                        + (yes ? "ACCEPTED " : "NO ") + open.get(0).substring(JobBoard.PREFIX.length()) + ".";
            }
            jobId = open.get(0);
        }
        RepairJob current = board.snapshot().job(jobId);
        if (yes && current != null && me.id().equals(current.assignedTo())) {
            return "CarCare+: " + jobId + " is already yours.";
        }
        JobBoard.Outcome outcome;
        try {
            outcome = yes ? board.accept(jobId, me.id()) : board.reject(jobId, me.id());
        } catch (InvalidInputException e) {
            return "CarCare+: there is no request " + jobId + ".";
        }
        if (!outcome.ok()) {
            return "Sorry, " + jobId + ": " + outcome.message();
        }
        return yes ? "" : "CarCare+: OK, you passed on " + jobId + ".";
    }

    /** Ids of the requests this repairer can still answer, oldest first. */
    private List<String> openFor(Repairer me) {
        List<String> open = new ArrayList<>();
        for (RepairJob job : board.snapshot().jobs()) { // newest first
            if (job.status() == RepairJob.Status.OPEN && job.isInvited(me.id()) && !job.hasDeclined(me.id())) {
                open.add(0, job.id());
            }
        }
        return open;
    }

    /** The newest request this repairer was sent and did not decline, or null. */
    private String latestFor(Repairer me) {
        for (RepairJob job : board.snapshot().jobs()) { // newest first
            if (job.isInvited(me.id()) && !job.hasDeclined(me.id())) {
                return job.id();
            }
        }
        return null;
    }

    /** A reply to a number that is not a repairer (so no repairer id to look up). */
    private void replyTo(String phone, String text) {
        executor.execute(() -> {
            try {
                gateway.send(phone, text);
            } catch (CarCareException | RuntimeException e) {
                LOG.log(Level.WARNING, "SMS reply to an unknown number failed: " + e.getMessage());
            }
        });
    }

    private static String firstName(String name) {
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }
}

package ca.carcareplus.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.carcareplus.exceptions.InvalidInputException;

class JobBoardTest {

    private static final List<String> TEAM = List.of("sriram", "sarvesh", "saikrishna", "dharanish");

    private MutableClock clock;
    private JobBoard board;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-26T15:00:00Z"));
        board = new JobBoard(clock);
    }

    private RepairJob post(List<String> invited) {
        return board.post(new JobBoard.JobRequest("Sam Lee", "(519) 555-0100", "Windsor", "Gas vehicle",
                "flat tire", "Flat tire / puncture", invited));
    }

    @Test
    void newJobIsOpenForEveryInvitedRepairer() {
        RepairJob job = post(TEAM);
        assertEquals("JOB-1", job.id());
        assertEquals(RepairJob.Status.OPEN, job.status());
        assertEquals(TEAM, job.invited());
        assertEquals(clock.instant().plus(JobBoard.ANSWER_TIME), job.expiresAt());
        assertNull(job.assignedTo());
        assertEquals("JOB-2", post(TEAM).id(), "references count up");
    }

    @Test
    void firstAcceptWinsAndLocksTheJob() {
        RepairJob job = post(TEAM);
        clock.advance(Duration.ofSeconds(4));
        JobBoard.Outcome first = board.accept(job.id(), "sarvesh");
        assertTrue(first.ok());
        assertEquals(RepairJob.Status.ASSIGNED, first.job().status());
        assertEquals("sarvesh", first.job().assignedTo());
        assertEquals(clock.instant(), first.job().assignedAt());

        JobBoard.Outcome second = board.accept(job.id(), "sriram");
        assertFalse(second.ok(), "the job is locked once someone accepted");
        assertEquals("sarvesh", second.job().assignedTo(), "the winner does not change");
        assertTrue(second.message().contains("another repairer already accepted"), second.message());

        assertFalse(board.reject(job.id(), "dharanish").ok(), "too late to decline as well");
        assertTrue(board.accept(job.id(), "sarvesh").ok(), "the winner pressing again is harmless");
    }

    @Test
    void manyRepairersAcceptingAtTheSameMomentGiveExactlyOneWinner() throws Exception {
        // 40 repairers, 40 threads, all released by one starting gun: the synchronized
        // accept() must still let exactly one of them win. Repeated to catch rare timings.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add("r" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(many.size());
        try {
            for (int round = 0; round < 50; round++) {
                RepairJob job = post(many);
                CountDownLatch startingGun = new CountDownLatch(1);
                List<Future<JobBoard.Outcome>> answers = new ArrayList<>();
                for (String repairer : many) {
                    answers.add(pool.submit(() -> {
                        startingGun.await();
                        return board.accept(job.id(), repairer);
                    }));
                }
                startingGun.countDown();
                int winners = 0;
                String winner = null;
                for (Future<JobBoard.Outcome> answer : answers) {
                    JobBoard.Outcome outcome = answer.get(10, TimeUnit.SECONDS);
                    if (outcome.ok()) {
                        winners++;
                        winner = outcome.job().assignedTo();
                    }
                }
                assertEquals(1, winners, "round " + round + ": exactly one repairer may win");
                assertEquals(winner, board.snapshot().job(job.id()).assignedTo(), "the board agrees with the winner");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whenEveryoneDeclinesTheJobHasNoTakers() {
        RepairJob job = post(List.of("sriram", "sarvesh"));
        assertTrue(board.reject(job.id(), "sriram").ok());
        assertEquals(RepairJob.Status.OPEN, board.snapshot().job(job.id()).status(), "one still to answer");
        assertFalse(board.accept(job.id(), "sriram").ok(), "cannot accept after declining");

        JobBoard.Outcome last = board.reject(job.id(), "sarvesh");
        assertEquals(RepairJob.Status.NO_TAKERS, last.job().status());
        assertEquals(clock.instant(), last.job().closedAt());
    }

    @Test
    void declinedByOneThenAcceptedByAnother() {
        RepairJob job = post(TEAM);
        board.reject(job.id(), "sriram");
        JobBoard.Outcome won = board.accept(job.id(), "saikrishna");
        assertTrue(won.ok());
        assertEquals(java.util.Set.of("sriram"), won.job().declined());
    }

    @Test
    void onlyInvitedRepairersCanAnswer() {
        RepairJob job = post(List.of("sriram"));
        assertFalse(board.accept(job.id(), "sarvesh").ok());
        assertFalse(board.reject(job.id(), "sarvesh").ok());
        assertEquals(RepairJob.Status.OPEN, board.snapshot().job(job.id()).status());
    }

    @Test
    void requestExpiresWhenNobodyAcceptsInTime() {
        RepairJob job = post(TEAM);
        clock.advance(JobBoard.ANSWER_TIME.minusSeconds(1));
        assertEquals(RepairJob.Status.OPEN, board.snapshot().job(job.id()).status());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(RepairJob.Status.EXPIRED, board.snapshot().job(job.id()).status());
        JobBoard.Outcome late = board.accept(job.id(), "sriram");
        assertFalse(late.ok());
        assertEquals("This request expired.", late.message());
    }

    @Test
    void customerCanCancelAndRepairersThenCannotAccept() {
        RepairJob job = post(TEAM);
        assertEquals(RepairJob.Status.CANCELLED, board.cancel(job.id()).status());
        assertEquals(RepairJob.Status.CANCELLED, board.cancel(job.id()).status(), "cancelling twice is fine");
        JobBoard.Outcome late = board.accept(job.id(), "sriram");
        assertFalse(late.ok());
        assertEquals("The customer cancelled this request.", late.message());
    }

    @Test
    void onlyTheWinnerCanFinishAndADoneJobCannotBeCancelled() {
        RepairJob job = post(TEAM);
        assertFalse(board.finish(job.id(), "sriram").ok(), "not assigned yet");
        board.accept(job.id(), "sriram");
        assertFalse(board.finish(job.id(), "sarvesh").ok(), "not the winner");
        assertEquals(RepairJob.Status.DONE, board.finish(job.id(), "sriram").job().status());
        assertThrows(InvalidInputException.class, () -> board.cancel(job.id()));
    }

    @Test
    void unknownJobOrNoRepairersIsBadInput() {
        assertThrows(InvalidInputException.class, () -> board.accept("JOB-99", "sriram"));
        assertThrows(InvalidInputException.class, () -> board.cancel(null));
        assertThrows(InvalidInputException.class, () -> post(List.of()));
        assertEquals("JOB-1", post(TEAM).id());
        assertTrue(board.accept("job-1", "sriram").ok(), "reference is case-insensitive");
    }

    @Test
    void everyChangeRaisesTheVersionAndReadingDoesNot() {
        long start = board.version();
        assertEquals(start, board.version(), "just asking changes nothing");
        RepairJob job = post(TEAM);
        long afterPost = board.version();
        assertTrue(afterPost > start);
        board.accept(job.id(), "sriram");
        assertTrue(board.version() > afterPost);
        long afterAccept = board.version();
        board.accept(job.id(), "sarvesh"); // lost: nothing changed
        assertEquals(afterAccept, board.version());
    }

    @Test
    void repairerIsOnlineWhileTheirPageKeepsAsking() {
        board.seen("sriram");
        long v = board.version();
        assertEquals(java.util.Set.of("sriram"), board.snapshot().online());
        clock.advance(Duration.ofSeconds(2));
        board.seen("sriram");
        assertEquals(v, board.version(), "still online: no change");
        clock.advance(JobBoard.ONLINE_FOR.plusSeconds(1));
        assertTrue(board.snapshot().online().isEmpty(), "window closed: offline after a few seconds");
        assertTrue(board.version() > v, "going offline is a change the customer's window sees");
    }
}

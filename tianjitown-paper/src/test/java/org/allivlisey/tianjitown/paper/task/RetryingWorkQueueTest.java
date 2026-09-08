package org.allivlisey.tianjitown.paper.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryingWorkQueueTest {
    private final TestScheduler scheduler = new TestScheduler();
    private final List<String> completed = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @Test
    void submissionsBeforeWorkerStartsAreProcessedInOrderWithoutDuplicateDrains() {
        var queue = queue(completed::add);
        queue.submit("first");
        queue.submit("second");
        queue.flush();
        assertTrue(completed.isEmpty());
        assertEquals(2, queue.pendingCount());
        assertEquals(1, scheduler.workers.size());

        scheduler.runWorker();

        assertEquals(List.of("first", "second"), completed);
        assertEquals(0, queue.pendingCount());
        assertTrue(scheduler.workers.isEmpty());
        assertTrue(scheduler.delayed.isEmpty());
    }

    @Test
    void failedHeadIsRetriedBeforeLaterItemsWithoutAnotherSubmission() {
        AtomicInteger attempts = new AtomicInteger();
        var queue = queue(item -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("database busy");
            }
            completed.add(item);
        });
        queue.submit("first");
        queue.submit("second");
        scheduler.runWorker();
        assertEquals(2, queue.pendingCount());
        assertTrue(completed.isEmpty());
        assertTrue(scheduler.workers.isEmpty());

        scheduler.runRetry();
        scheduler.runWorker();
        assertEquals(2, queue.pendingCount());
        scheduler.runRetry();
        scheduler.runWorker();

        assertEquals(List.of("first", "second"), completed);
        assertEquals(List.of("first", "first"), failures);
        assertEquals(List.of(5L, 10L), scheduler.delays);
        assertEquals(0, queue.pendingCount());
        assertTrue(scheduler.delayed.isEmpty());
    }

    @Test
    void retryDelayIsCappedAndResetsAfterRecovery() {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        var queue = queue(item -> {
            if (unavailable.get()) throw new IllegalStateException("offline");
            completed.add(item);
        });
        queue.submit("first");
        scheduler.runWorker();
        for (int retry = 0; retry < 4; retry++) {
            scheduler.runRetry();
            scheduler.runWorker();
        }
        assertEquals(List.of(5L, 10L, 20L, 30L, 30L), scheduler.delays);
        unavailable.set(false);
        scheduler.runRetry();
        scheduler.runWorker();
        assertEquals(List.of("first"), completed);

        unavailable.set(true);
        queue.submit("second");
        scheduler.runWorker();
        assertEquals(List.of(5L, 10L, 20L, 30L, 30L, 5L), scheduler.delays);
        unavailable.set(false);
        scheduler.runRetry();
        scheduler.runWorker();
        assertEquals(List.of("first", "second"), completed);
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void workSubmittedDuringProcessingIsNotLostOrScheduledTwice() {
        var reference = new java.util.concurrent.atomic.AtomicReference<RetryingWorkQueue<String>>();
        reference.set(queue(item -> {
            completed.add(item);
            if (item.equals("first")) reference.get().submit("second");
        }));
        reference.get().submit("first");
        scheduler.runWorker();
        assertEquals(List.of("first", "second"), completed);
        assertEquals(0, reference.get().pendingCount());
        assertTrue(scheduler.workers.isEmpty());
    }

    @Test
    void rejectedWorkerSubmissionKeepsWorkAvailableForExplicitFlush() {
        var queue = queue(completed::add);
        scheduler.rejectWorker = true;
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> queue.submit("first"));
        assertEquals(1, queue.pendingCount());
        assertTrue(completed.isEmpty());

        scheduler.rejectWorker = false;
        queue.flush();
        scheduler.runWorker();
        assertEquals(List.of("first"), completed);
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void failingNotificationDoesNotPreventRetryingTheOriginalWork() {
        AtomicInteger attempts = new AtomicInteger();
        var notificationFailure = new IllegalArgumentException("notification failed");
        var queue = new RetryingWorkQueue<String>(scheduler, 5, 30, item -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("offline");
            completed.add(item);
        }, (item, error) -> { throw notificationFailure; });
        queue.submit("first");
        assertSame(notificationFailure, assertThrows(IllegalArgumentException.class, scheduler::runWorker));
        assertEquals(1, queue.pendingCount());
        scheduler.runRetry();
        scheduler.runWorker();
        assertEquals(List.of("first"), completed);
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void optionalDependencyLinkageFailureIsReportedAndRetried() {
        var linkageFailure = new NoSuchMethodError("optional dependency");
        var reported = new ArrayList<RuntimeException>();
        AtomicInteger attempts = new AtomicInteger();
        var queue = new RetryingWorkQueue<String>(scheduler, 5, 30, item -> {
            if (attempts.incrementAndGet() == 1) throw linkageFailure;
            completed.add(item);
        }, (item, error) -> reported.add(error));
        queue.submit("first");
        scheduler.runWorker();
        assertEquals(1, reported.size());
        assertSame(linkageFailure, reported.getFirst().getCause());
        assertEquals(1, queue.pendingCount());
        scheduler.runRetry();
        scheduler.runWorker();
        assertEquals(List.of("first"), completed);
        assertEquals(0, queue.pendingCount());
    }

    private RetryingWorkQueue<String> queue(java.util.function.Consumer<String> worker) {
        return new RetryingWorkQueue<>(scheduler, 5, 30, worker,
                (item, error) -> failures.add(item));
    }

    @Test
    void rejectedTimerRetainsHeadAndAllowsSchedulingAgainAfterExplicitFlush() {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        var queue = queue(item -> {
            if (unavailable.get()) throw new IllegalStateException("database offline");
            completed.add(item);
        });
        queue.submit("first");
        queue.submit("second");
        scheduler.rejectTimer = true;
        assertThrows(java.util.concurrent.RejectedExecutionException.class, scheduler::runWorker);
        assertEquals(2, queue.pendingCount());
        assertTrue(completed.isEmpty());
        assertTrue(scheduler.delayed.isEmpty());

        scheduler.rejectTimer = false;
        queue.flush();
        scheduler.runWorker();
        assertEquals(1, scheduler.delayed.size());
        unavailable.set(false);
        scheduler.runRetry();
        scheduler.runWorker();
        assertEquals(List.of("first", "second"), completed);
        assertEquals(List.of("first", "first"), failures);
        assertEquals(0, queue.pendingCount());
        assertTrue(scheduler.delayed.isEmpty());
    }

    @Test
    void staleTimerAfterExplicitRecoveryDoesNotProcessItemsTwice() {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        var queue = queue(item -> {
            if (unavailable.get()) throw new IllegalStateException("offline");
            completed.add(item);
        });
        queue.submit("first");
        scheduler.runWorker();
        unavailable.set(false);
        queue.submit("second");
        scheduler.runWorker();
        assertEquals(List.of("first", "second"), completed);
        scheduler.runRetry();
        assertTrue(scheduler.workers.isEmpty());
        assertEquals(0, queue.pendingCount());
        assertEquals(List.of("first", "second"), completed);
    }

    // Separate worker and timer queues model scheduling without wall-clock sleeps.
    private static final class TestScheduler implements RetryingWorkQueue.Scheduler {
        private final Queue<Runnable> workers = new ArrayDeque<>();
        private final Queue<Runnable> delayed = new ArrayDeque<>();
        private final List<Long> delays = new ArrayList<>();
        private boolean rejectWorker;
        private boolean rejectTimer;

        @Override
        public void executeAsync(Runnable task) {
            if (rejectWorker) throw new java.util.concurrent.RejectedExecutionException();
            workers.add(task);
        }

        @Override
        public void schedule(Runnable task, long delayTicks) {
            if (rejectTimer) throw new java.util.concurrent.RejectedExecutionException();
            delays.add(delayTicks);
            delayed.add(task);
        }

        private void runWorker() { workers.remove().run(); }
        private void runRetry() { delayed.remove().run(); }
    }
}

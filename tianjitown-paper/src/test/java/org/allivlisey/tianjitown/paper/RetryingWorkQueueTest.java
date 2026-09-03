package org.allivlisey.tianjitown.paper;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryingWorkQueueTest {
    @Test
    void retriesFailedWorkWithoutAnotherSubmission() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<String> completed = new ArrayList<>();
        RetryingWorkQueue<String> queue = new RetryingWorkQueue<>(scheduler, 5, 30,
                item -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("SQLITE_BUSY");
                    }
                    completed.add(item);
                }, (item, exception) -> failures.incrementAndGet());

        queue.submit("tax-1030");

        assertEquals(1, queue.pendingCount());
        assertEquals(1, failures.get());
        scheduler.runNextDelayed();
        assertEquals(1, queue.pendingCount());
        scheduler.runNextDelayed();

        assertEquals(List.of("tax-1030"), completed);
        assertEquals(3, attempts.get());
        assertEquals(2, failures.get());
        assertEquals(0, queue.pendingCount());
        assertEquals(List.of(5L, 10L), scheduler.scheduledDelays);
    }

    @Test
    void keepsSingleDrainWhenWorkArrivesDuringProcessing() {
        TestScheduler scheduler = new TestScheduler();
        List<String> completed = new ArrayList<>();
        @SuppressWarnings("unchecked")
        RetryingWorkQueue<String>[] reference = new RetryingWorkQueue[1];
        reference[0] = new RetryingWorkQueue<>(scheduler, 5, 30, item -> {
            completed.add(item);
            if (item.equals("first")) {
                reference[0].submit("second");
            }
        }, (item, exception) -> {
        });

        reference[0].submit("first");

        assertEquals(List.of("first", "second"), completed);
        assertEquals(1, scheduler.asyncExecutions);
        assertEquals(0, reference[0].pendingCount());
    }

    @Test
    void requeuesWorkAfterLinkageError() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<String> completed = new ArrayList<>();
        RetryingWorkQueue<String> queue = new RetryingWorkQueue<>(scheduler, 5, 30,
                item -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new NoSuchMethodError("INJECTED");
                    }
                    completed.add(item);
                }, (item, exception) -> failures.incrementAndGet());

        queue.submit("jobs-income-1");
        assertEquals(1, queue.pendingCount());
        scheduler.runNextDelayed();

        assertEquals(List.of("jobs-income-1"), completed);
        assertEquals(2, attempts.get());
        assertEquals(1, failures.get());
        assertEquals(0, queue.pendingCount());
    }

    private static final class TestScheduler implements RetryingWorkQueue.Scheduler {
        private final Queue<Runnable> delayed = new ArrayDeque<>();
        private final List<Long> scheduledDelays = new ArrayList<>();
        private int asyncExecutions;

        @Override
        public void executeAsync(Runnable task) {
            asyncExecutions++;
            task.run();
        }

        @Override
        public void schedule(Runnable task, long delayTicks) {
            scheduledDelays.add(delayTicks);
            delayed.add(task);
        }

        private void runNextDelayed() {
            delayed.remove().run();
        }
    }
}

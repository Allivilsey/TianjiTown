package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskTrackerTest {
    @Test
    void shutdownRejectsNewTasksAndWaitsForRunningTask() throws Exception {
        AsyncTaskTracker tracker = new AsyncTaskTracker();
        tracker.startAccepting();
        assertTrue(tracker.begin());
        tracker.stopAccepting();
        assertFalse(tracker.begin());

        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            waiting.countDown();
            assertTrue(tracker.awaitQuiescence(Duration.ofSeconds(2)));
        });
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        tracker.complete();
        waiter.join(2_000);

        assertFalse(waiter.isAlive());
        assertEquals(0, tracker.active());
    }

    @Test
    void timeoutLeavesRunningCountVisible() {
        AsyncTaskTracker tracker = new AsyncTaskTracker();
        tracker.startAccepting();
        assertTrue(tracker.begin());
        tracker.stopAccepting();

        assertFalse(tracker.awaitQuiescence(Duration.ofMillis(10)));
        assertEquals(1, tracker.active());
        tracker.complete();
    }

    @Test
    void newLifecycleRequiresPreviousTasksToFinish() {
        AsyncTaskTracker tracker = new AsyncTaskTracker();
        tracker.startAccepting();
        assertTrue(tracker.begin());
        tracker.stopAccepting();

        assertThrows(IllegalStateException.class, tracker::startAccepting);
        tracker.complete();
        tracker.startAccepting();
        assertTrue(tracker.begin());
        tracker.complete();
    }
}

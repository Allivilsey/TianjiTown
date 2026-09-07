package org.allivlisey.tianjitown.integrations.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class JobsTaxCallTest {
    @Test void timeoutAbandonsQueuedSettlement() {
        AtomicInteger debits = new AtomicInteger();
        JobsTaxCall call = new JobsTaxCall(() -> {
            debits.incrementAndGet();
            return JobsIncomeTaxAdapter.TaxResult.taxed(95);
        });
        assertThrows(TimeoutException.class, () -> call.await(1, TimeUnit.MILLISECONDS));
        assertNull(call.call());
        assertEquals(0, debits.get());
    }

    @Test void interruptionAbandonsQueuedSettlement() {
        AtomicInteger debits = new AtomicInteger();
        JobsTaxCall call = new JobsTaxCall(() -> {
            debits.incrementAndGet();
            return JobsIncomeTaxAdapter.TaxResult.taxed(95);
        });
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> call.await(1, TimeUnit.SECONDS));
            assertNull(call.call());
            assertEquals(0, debits.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void runningSettlementMustFinishBeforeEventReturnsEvenAfterTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        JobsTaxCall call = new JobsTaxCall(() -> {
            started.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return JobsIncomeTaxAdapter.TaxResult.taxed(95);
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            workers.submit(call);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Future<JobsIncomeTaxAdapter.TaxResult> waiting = workers.submit(
                    () -> call.await(1, TimeUnit.MILLISECONDS));
            try {
                assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
            } finally { finish.countDown(); }
            assertEquals(95, waiting.get(1, TimeUnit.SECONDS).netAmount());
        } finally { finish.countDown(); }
    }

    @Test void interruptedRunningSettlementReturnsNetAndRestoresInterruptFlag() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        JobsTaxCall call = new JobsTaxCall(() -> {
            started.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return JobsIncomeTaxAdapter.TaxResult.taxed(95);
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            workers.submit(call);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Future<Boolean> waiting = workers.submit(() -> {
                Thread.currentThread().interrupt();
                assertEquals(95, call.await(1, TimeUnit.MILLISECONDS).netAmount());
                return Thread.currentThread().isInterrupted();
            });
            try {
                assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
            } finally { finish.countDown(); }
            assertTrue(waiting.get(1, TimeUnit.SECONDS));
        } finally { finish.countDown(); }
    }
}

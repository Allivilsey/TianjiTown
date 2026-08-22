package cn.tianji.town.paper;

import cn.tianji.town.integrations.vault.VaultSettlementService;
import cn.tianji.town.storage.economy.EconomyRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DonationCompensationCoordinatorTest {
    @Test
    void retriesExternalRefundAndFinalizesStorageWithoutPayingTwice() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger refundAttempts = new AtomicInteger();
        AtomicInteger storageAttempts = new AtomicInteger();
        TestListener listener = new TestListener();
        EconomyRepository.EconomyOperation operation = operation();
        DonationCompensationCoordinator coordinator = new DonationCompensationCoordinator(
                scheduler, (playerId, amountMinor) -> refundAttempts.incrementAndGet() < 3
                ? VaultSettlementService.Result.failure("INJECTED_COMPFAIL", false, false)
                : VaultSettlementService.Result.success("RECOVERED"),
                (operationId, detail) -> {
                    if (storageAttempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("SQLITE_BUSY");
                    }
                }, listener, 5, 5, 20);

        coordinator.submit(operation);
        assertEquals(1, coordinator.pendingCount());

        scheduler.runNextDelayed();
        scheduler.runNextDelayed();
        scheduler.runNextDelayed();
        scheduler.runNextDelayed();

        assertEquals(3, refundAttempts.get());
        assertEquals(2, storageAttempts.get());
        assertEquals(0, coordinator.pendingCount());
        assertEquals(List.of(5L, 10L, 20L, 5L), scheduler.delays);
        assertEquals(2, listener.refundFailures);
        assertEquals(1, listener.storageFailures);
        assertEquals(3, listener.recoveredAttempts);
        assertEquals(0, listener.exhausted);
    }

    @Test
    void stopsAtRetryLimitAndLeavesPersistentLockForManualRecovery() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger refundAttempts = new AtomicInteger();
        AtomicInteger storageAttempts = new AtomicInteger();
        TestListener listener = new TestListener();
        DonationCompensationCoordinator coordinator = new DonationCompensationCoordinator(
                scheduler, (playerId, amountMinor) -> {
                    refundAttempts.incrementAndGet();
                    return VaultSettlementService.Result.failure("OUTAGE", false, false);
                }, (operationId, detail) -> storageAttempts.incrementAndGet(), listener,
                3, 5, 20);

        coordinator.submit(operation());
        scheduler.runNextDelayed();
        scheduler.runNextDelayed();
        scheduler.runNextDelayed();

        assertEquals(3, refundAttempts.get());
        assertEquals(0, storageAttempts.get());
        assertEquals(0, coordinator.pendingCount());
        assertEquals(1, listener.exhausted);
        assertEquals(List.of(5L, 10L, 20L), scheduler.delays);
    }

    private static EconomyRepository.EconomyOperation operation() {
        return new EconomyRepository.EconomyOperation(UUID.randomUUID(), UUID.randomUUID(),
                "DONATION", "donation:test", 100, UUID.randomUUID(), "Player",
                "测试捐款", "COMPENSATION_REQUIRED", "INJECTED_COMPFAIL", Instant.now());
    }

    private static final class TestScheduler
            implements DonationCompensationCoordinator.Scheduler {
        private final Queue<Runnable> delayed = new ArrayDeque<>();
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void runMainLater(Runnable task, long delayTicks) {
            delays.add(delayTicks);
            delayed.add(task);
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        private void runNextDelayed() {
            delayed.remove().run();
        }
    }

    private static final class TestListener implements DonationCompensationCoordinator.Listener {
        private int refundFailures;
        private int storageFailures;
        private int recoveredAttempts;
        private int exhausted;

        @Override
        public void retryFailed(EconomyRepository.EconomyOperation operation, int attempt,
                                String detail) {
            refundFailures++;
        }

        @Override
        public void finalizationFailed(EconomyRepository.EconomyOperation operation, int attempt,
                                       String detail) {
            storageFailures++;
        }

        @Override
        public void recovered(EconomyRepository.EconomyOperation operation, int attempts) {
            recoveredAttempts = attempts;
        }

        @Override
        public void exhausted(EconomyRepository.EconomyOperation operation, String detail) {
            exhausted++;
        }
    }
}

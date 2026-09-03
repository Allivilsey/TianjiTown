package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DonationRefundCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void retriesExternalRefundAndFinalizesStorageWithoutPayingTwice() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger refundAttempts = new AtomicInteger();
        AtomicInteger storageAttempts = new AtomicInteger();
        TestListener listener = new TestListener();
        EconomyRepository.EconomyOperation operation = operation();
        DonationRefundCoordinator coordinator = new DonationRefundCoordinator(
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
        DonationRefundCoordinator coordinator = new DonationRefundCoordinator(
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

    @Test
    void resolvesInvalidOperationAndRefundExceptionThroughInjectedMessages() {
        TestScheduler scheduler = new TestScheduler();
        TestListener listener = new TestListener();
        DonationRefundCoordinator coordinator = new DonationRefundCoordinator(
                scheduler, (playerId, amountMinor) -> {
                    throw new IllegalStateException("refund&failure");
                }, (operationId, detail) -> {
                }, listener, 1, 5, 20, (key, placeholders) -> switch (key) {
                    case "validation.donation.refund-operation" -> "自定义操作校验失败";
                    case "diagnostic.donation.refund-call-failure" ->
                            "自定义退款调用失败: " + placeholders.get("detail");
                    default -> key;
                });

        IllegalArgumentException invalidOperation = assertThrows(IllegalArgumentException.class,
                () -> coordinator.submit(operationWithoutActor()));
        assertEquals("自定义操作校验失败", invalidOperation.getMessage());

        coordinator.submit(operation());
        scheduler.runNextDelayed();
        assertEquals("自定义退款调用失败: refund＆failure", listener.lastRetryDetail);
        assertEquals("自定义退款调用失败: refund＆failure", listener.lastExhaustedDetail);
    }

    @Test
    void resolvesRefundReasonWhenStorageResolutionRunsAfterReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        TestScheduler scheduler = new TestScheduler();
        List<String> resolvedDetails = new ArrayList<>();
        DonationRefundCoordinator coordinator = new DonationRefundCoordinator(
                scheduler, (playerId, amountMinor) -> VaultSettlementService.Result.success(
                        "RECOVERED"),
                (operationId, detail) -> resolvedDetails.add(detail), new TestListener(),
                1, 5, 20, messages::plainText);

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("log.donation.refund-resolved", "自定义退款账本原因");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        coordinator.submit(operation());
        scheduler.runNextDelayed();

        assertEquals(List.of("自定义退款账本原因"), resolvedDetails);
    }

    private static EconomyRepository.EconomyOperation operation() {
        return new EconomyRepository.EconomyOperation(UUID.randomUUID(), UUID.randomUUID(),
                "DONATION", "donation:test", 100, UUID.randomUUID(), "Player",
                "测试捐款", "COMPENSATION_REQUIRED", "INJECTED_COMPFAIL", Instant.now());
    }

    private static EconomyRepository.EconomyOperation operationWithoutActor() {
        return new EconomyRepository.EconomyOperation(UUID.randomUUID(), UUID.randomUUID(),
                "DONATION", "donation:invalid", 100, null, null,
                "测试捐款", "COMPENSATION_REQUIRED", "INJECTED_COMPFAIL", Instant.now());
    }

    private static final class TestScheduler
            implements DonationRefundCoordinator.Scheduler {
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

    private static final class TestListener implements DonationRefundCoordinator.Listener {
        private int refundFailures;
        private int storageFailures;
        private int recoveredAttempts;
        private int exhausted;
        private String lastRetryDetail;
        private String lastExhaustedDetail;

        @Override
        public void retryFailed(EconomyRepository.EconomyOperation operation, int attempt,
                                String detail) {
            refundFailures++;
            lastRetryDetail = detail;
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
            lastExhaustedDetail = detail;
        }
    }
}

package org.allivlisey.tianjitown.paper.economy;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DonationRefundCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void retriesExternalRefundAndFinalizesStorageWithoutPayingTwice() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger refundAttempts = new AtomicInteger();
        AtomicInteger storageAttempts = new AtomicInteger();
        var listener = mock(DonationRefundCoordinator.Listener.class);
        EconomyRepository.EconomyOperation operation = operation();
        DonationRefundCoordinator coordinator = new DonationRefundCoordinator(
                scheduler, (playerId, amountMinor) -> refundAttempts.incrementAndGet() < 3
                ? VaultPlayerEconomyService.Result.failure("INJECTED_COMPFAIL", false, false)
                : VaultPlayerEconomyService.Result.success("RECOVERED"),
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
        verify(listener, times(2)).retryFailed(any(), anyInt(), any());
        verify(listener).finalizationFailed(any(), anyInt(), any());
        verify(listener).recovered(operation, 3);
        verify(listener, never()).exhausted(any(), any());
    }

    @Test
    void stopsAtRetryLimitAndLeavesPersistentLockForManualRecovery() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger refundAttempts = new AtomicInteger();
        AtomicInteger storageAttempts = new AtomicInteger();
        var listener = mock(DonationRefundCoordinator.Listener.class);
        DonationRefundCoordinator coordinator = new DonationRefundCoordinator(
                scheduler, (playerId, amountMinor) -> {
                    refundAttempts.incrementAndGet();
                    return VaultPlayerEconomyService.Result.failure("OUTAGE", false, false);
                }, (operationId, detail) -> storageAttempts.incrementAndGet(), listener,
                3, 5, 20);

        coordinator.submit(operation());
        scheduler.runNextDelayed();
        scheduler.runNextDelayed();
        scheduler.runNextDelayed();

        assertEquals(3, refundAttempts.get());
        assertEquals(0, storageAttempts.get());
        assertEquals(0, coordinator.pendingCount());
        verify(listener).exhausted(any(), eq("OUTAGE"));
        assertEquals(List.of(5L, 10L, 20L), scheduler.delays);
    }

    @Test
    void resolvesInvalidOperationAndRefundExceptionThroughInjectedMessages() {
        TestScheduler scheduler = new TestScheduler();
        var listener = mock(DonationRefundCoordinator.Listener.class);
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
        verify(listener).retryFailed(any(), eq(1), eq("自定义退款调用失败: refund＆failure"));
        verify(listener).exhausted(any(), eq("自定义退款调用失败: refund＆failure"));
    }

    @Test
    void resolvesRefundReasonWhenStorageResolutionRunsAfterReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        TestScheduler scheduler = new TestScheduler();
        List<String> resolvedDetails = new ArrayList<>();
        DonationRefundCoordinator coordinator = new DonationRefundCoordinator(
                scheduler, (playerId, amountMinor) -> VaultPlayerEconomyService.Result.success(
                        "RECOVERED"),
                (operationId, detail) -> resolvedDetails.add(detail),
                mock(DonationRefundCoordinator.Listener.class),
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
}

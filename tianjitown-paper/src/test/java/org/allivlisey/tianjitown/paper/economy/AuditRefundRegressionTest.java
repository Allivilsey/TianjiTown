package org.allivlisey.tianjitown.paper.economy;

import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditRefundRegressionTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ambiguous", "exception", "null"})
    void uncertainCreditStopsAutomaticPayments(String outcome) {
        Queue<Runnable> delayed = new ArrayDeque<>();
        AtomicInteger paid = new AtomicInteger(), attempts = new AtomicInteger(), resolved = new AtomicInteger();
        var scheduler = new DonationRefundCoordinator.Scheduler() {
            public void runMainLater(Runnable task, long ticks) { delayed.add(task); }
            public void runAsync(Runnable task) { task.run(); }
        };
        var coordinator = new DonationRefundCoordinator(scheduler, (player, amount) -> {
            paid.addAndGet(Math.toIntExact(amount));
            attempts.incrementAndGet();
            if (outcome.equals("exception")) throw new IllegalStateException("response lost");
            if (outcome.equals("null")) return null;
            return VaultSettlementService.Result.failure("credit applied but response lost", false, true);
        }, (operation, detail) -> resolved.incrementAndGet(), mock(DonationRefundCoordinator.Listener.class));
        var operation = new EconomyRepository.EconomyOperation(UUID.randomUUID(), UUID.randomUUID(),
                "DONATION", "audit:refund", 100, UUID.randomUUID(), "Player", "audit", "COMPENSATION_REQUIRED", "refund needed", Instant.now());
        coordinator.submit(operation);
        delayed.remove().run();
        assertEquals(100, paid.get());
        coordinator.submit(operation);
        assertTrue(delayed.isEmpty());
        assertEquals(1, attempts.get());
        assertEquals(100, paid.get());
        assertEquals(0, resolved.get());
        assertEquals(1, coordinator.pendingCount());
    }
}

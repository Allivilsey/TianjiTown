package org.allivlisey.tianjitown.paper.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandConfirmationManagerTest {
    @Test
    void remainsUsableOneMillisecondBeforeExpiry() {
        var confirmation = manager.request("admin-a", "删除小镇", () -> {});
        clock.set(confirmation.expiresAt() - 1);
        assertEquals(CommandConfirmationManager.Status.CONFIRMED,
                manager.consume("admin-a", confirmation.token()).status());
    }

    @Test
    void cancellationByAnotherOwnerDoesNotInvalidateTheOriginalAction() {
        AtomicInteger executions = new AtomicInteger();
        var confirmation = manager.request("admin-a", "删除小镇", executions::incrementAndGet);
        var denied = manager.cancel("admin-b", confirmation.token());
        assertEquals(CommandConfirmationManager.Status.NOT_OWNER, denied.status());
        assertNull(denied.action());
        assertEquals(0, executions.get());
        manager.consume("admin-a", confirmation.token()).action().run();
        assertEquals(1, executions.get());
    }

    @Test
    void cancelledTokenCannotBeReplayedOrCancelledAgain() {
        AtomicInteger executions = new AtomicInteger();
        var confirmation = manager.request("admin-a", "删除小镇", executions::incrementAndGet);
        assertNull(manager.cancel("admin-a", confirmation.token()).action());
        var replay = manager.consume("admin-a", confirmation.token());
        assertEquals(CommandConfirmationManager.Status.NOT_FOUND, replay.status());
        assertNull(replay.action());
        assertEquals(CommandConfirmationManager.Status.NOT_FOUND,
                manager.cancel("admin-a", confirmation.token()).status());
        assertEquals(0, executions.get());
    }

    @Test
    void cancellationAtExpiryDiscardsTheAction() {
        var confirmation = manager.request("admin-a", "删除小镇", () -> {});
        clock.set(confirmation.expiresAt());
        var result = manager.cancel("admin-a", confirmation.token());
        assertEquals(CommandConfirmationManager.Status.EXPIRED, result.status());
        assertNull(result.action());
        assertEquals(CommandConfirmationManager.Status.NOT_FOUND,
                manager.consume("admin-a", confirmation.token()).status());
    }

    @Test
    void failingActionCannotBeExecutedAgainWithTheSameToken() {
        AtomicInteger executions = new AtomicInteger();
        var confirmation = manager.request("admin-a", "删除小镇", () -> {
            executions.incrementAndGet();
            throw new IllegalStateException("external operation failed");
        });
        var result = manager.consume("admin-a", confirmation.token());
        assertThrows(IllegalStateException.class, result.action()::run);
        var replay = manager.consume("admin-a", confirmation.token());
        assertEquals(CommandConfirmationManager.Status.NOT_FOUND, replay.status());
        assertNull(replay.action());
        assertEquals(1, executions.get());
    }

    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicInteger tokens = new AtomicInteger();
    private final CommandConfirmationManager manager = new CommandConfirmationManager(
            Duration.ofSeconds(60), clock::get, () -> "token" + tokens.incrementAndGet());

    @Test
    void consumesConfirmationOnceForItsOwner() {
        AtomicInteger executions = new AtomicInteger();
        CommandConfirmationManager.Confirmation confirmation = manager.request(
                "admin-a", "删除小镇", executions::incrementAndGet);

        CommandConfirmationManager.Result result = manager.consume(
                "admin-a", confirmation.token());

        assertEquals(CommandConfirmationManager.Status.CONFIRMED, result.status());
        assertNotNull(result.action());
        result.action().run();
        assertEquals(1, executions.get());
        assertEquals(CommandConfirmationManager.Status.NOT_FOUND,
                manager.consume("admin-a", confirmation.token()).status());
    }

    @Test
    void doesNotConsumeAnotherOwnersConfirmation() {
        CommandConfirmationManager.Confirmation confirmation = manager.request(
                "admin-a", "重建领地", () -> {
                });

        assertEquals(CommandConfirmationManager.Status.NOT_OWNER,
                manager.consume("admin-b", confirmation.token()).status());
        assertEquals(CommandConfirmationManager.Status.CONFIRMED,
                manager.consume("admin-a", confirmation.token()).status());
    }

    @Test
    void expiresAndCancelsWithoutReturningAnAction() {
        CommandConfirmationManager.Confirmation expired = manager.request(
                "admin-a", "冒烟测试", () -> {
                });
        clock.addAndGet(Duration.ofSeconds(60).toMillis());
        CommandConfirmationManager.Result expiredResult = manager.consume(
                "admin-a", expired.token());
        assertEquals(CommandConfirmationManager.Status.EXPIRED, expiredResult.status());
        assertNull(expiredResult.action());

        CommandConfirmationManager.Confirmation cancelled = manager.request(
                "admin-a", "删除小镇", () -> {
                });
        CommandConfirmationManager.Result cancelledResult = manager.cancel(
                "admin-a", cancelled.token());
        assertEquals(CommandConfirmationManager.Status.CANCELLED, cancelledResult.status());
        assertNull(cancelledResult.action());
    }

    @Test
    void newerRequestReplacesPreviousRequestForTheSameOwner() {
        CommandConfirmationManager.Confirmation first = manager.request(
                "admin-a", "第一次", () -> {
                });
        CommandConfirmationManager.Confirmation second = manager.request(
                "admin-a", "第二次", () -> {
                });

        assertEquals(CommandConfirmationManager.Status.NOT_FOUND,
                manager.consume("admin-a", first.token()).status());
        assertEquals(CommandConfirmationManager.Status.CONFIRMED,
                manager.consume("admin-a", second.token()).status());
    }
}

package org.allivlisey.tianjitown.paper.buff;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuffExpirationSchedulerTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<Long> expiredGenerations = new ArrayList<>();
    private final UUID playerId = UUID.randomUUID();
    private final BuffExpirationScheduler expirations = new BuffExpirationScheduler(plugin,
            (id, generation) -> {
                assertEquals(playerId, id);
                expiredGenerations.add(generation);
            });

    BuffExpirationSchedulerTest() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenAnswer(call -> {
                    BukkitTask task = mock(BukkitTask.class);
                    scheduled.add(new Scheduled(call.getArgument(1), task, call.getArgument(2)));
                    return task;
                });
    }

    @Test
    void newRefreshPreservesDeadlineAndRejectsOldCallbacks() {
        long first = expirations.nextRefreshGeneration(playerId);
        expirations.ensureExpirationAt(playerId, first, Instant.now().plusSeconds(60));
        long second = expirations.nextRefreshGeneration(playerId);

        verify(scheduled.getFirst().task()).cancel();
        assertFalse(expirations.isCurrentRefresh(playerId, first));
        assertTrue(expirations.isCurrentRefresh(playerId, second));
        assertEquals(2, scheduled.size());
        scheduled.getFirst().callback().run();
        assertTrue(expiredGenerations.isEmpty());
        expirations.forgetRefresh(playerId, first);
        assertTrue(expirations.isCurrentRefresh(playerId, second));
        scheduled.getLast().callback().run();
        assertEquals(List.of(second), expiredGenerations);
    }

    @Test
    void selectsEarliestLiveBuffAndEmptyRefreshCancelsExpiration() {
        long generation = expirations.nextRefreshGeneration(playerId);
        Instant now = Instant.now();
        expirations.scheduleExpiration(playerId, List.of(buff(now.minusSeconds(10)),
                buff(now.plusSeconds(600)), buff(now.plusSeconds(60))), generation);

        assertEquals(1, scheduled.size());
        assertTrue(scheduled.getFirst().ticks() > 1100);
        assertTrue(scheduled.getFirst().ticks() <= 1200);
        expirations.scheduleExpiration(playerId, List.of(), generation);
        verify(scheduled.getFirst().task()).cancel();
        expirations.nextRefreshGeneration(playerId);
        assertEquals(1, scheduled.size(), "empty refresh must remove the remembered deadline");
    }

    @Test
    void purchaseOnlyBringsDeadlineForwardAndOverdueRunsNextTick() {
        long generation = expirations.nextRefreshGeneration(playerId);
        Instant now = Instant.now();
        expirations.ensureExpirationAt(playerId, generation, now.plusSeconds(60));
        expirations.ensureExpirationAt(playerId, generation, now.plusSeconds(120));
        assertEquals(1, scheduled.size());
        expirations.ensureExpirationAt(playerId, generation, now.minusSeconds(1));
        verify(scheduled.getFirst().task()).cancel();
        assertEquals(1L, scheduled.getLast().ticks());
    }

    @Test
    void quittingCancelsTasksAndOldGenerationCannotMatchRejoin() {
        long generation = expirations.nextRefreshGeneration(playerId);
        expirations.ensureExpirationAt(playerId, generation, Instant.now().plusSeconds(60));
        expirations.playerQuit(playerId);
        assertFalse(expirations.isCurrentRefresh(playerId, generation));
        verify(scheduled.getFirst().task()).cancel();
        long rejoined = expirations.nextRefreshGeneration(playerId);
        assertNotEquals(generation, rejoined);
        scheduled.getFirst().callback().run();
        assertTrue(expiredGenerations.isEmpty());
        assertEquals(1, scheduled.size(), "quit must forget the previous deadline");
    }

    @Test
    void failedSchedulingKeepsDeadlineForNextRefresh() {
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenThrow(new IllegalStateException("scheduler unavailable"))
                .thenReturn(mock(BukkitTask.class));
        long first = expirations.nextRefreshGeneration(playerId);
        expirations.ensureExpirationAt(playerId, first, Instant.now().plusSeconds(60));
        long second = expirations.nextRefreshGeneration(playerId);
        verify(scheduler, times(2)).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
        assertTrue(expirations.isCurrentRefresh(playerId, second));
    }

    @Test
    void staleRefreshCannotReplaceOrRetryCurrentExpiration() {
        long first = expirations.nextRefreshGeneration(playerId);
        long second = expirations.nextRefreshGeneration(playerId);
        expirations.ensureExpirationAt(playerId, second, Instant.now().plusSeconds(60));
        expirations.scheduleExpiration(playerId, List.of(), first);
        expirations.ensureExpirationAt(playerId, first, Instant.now());
        expirations.retryExpiration(playerId, first);
        assertEquals(1, scheduled.size());
        verify(scheduled.getFirst().task(), never()).cancel();
    }

    @Test
    void retryAfterExpirationSchedulesCleanupAgain() {
        long generation = expirations.nextRefreshGeneration(playerId);
        expirations.ensureExpirationAt(playerId, generation, Instant.now());
        scheduled.getFirst().callback().run();
        expirations.retryExpiration(playerId, generation);
        assertEquals(2, scheduled.size());
        assertTrue(scheduled.getLast().ticks() > 0 && scheduled.getLast().ticks() <= 20);
        scheduled.getLast().callback().run();
        assertEquals(List.of(generation, generation), expiredGenerations);
    }

    @Test
    void shutdownContinuesAfterCancelFailureAndInvalidatesCallbacks() {
        UUID otherPlayer = UUID.randomUUID();
        long first = expirations.nextRefreshGeneration(playerId);
        long second = expirations.nextRefreshGeneration(otherPlayer);
        expirations.ensureExpirationAt(playerId, first, Instant.now().plusSeconds(60));
        expirations.ensureExpirationAt(otherPlayer, second, Instant.now().plusSeconds(60));
        doThrow(new IllegalStateException("already stopped")).when(scheduled.getFirst().task()).cancel();
        expirations.clearAll();

        for (Scheduled task : scheduled) {
            verify(task.task()).cancel();
            task.callback().run();
        }
        assertTrue(expiredGenerations.isEmpty());
        assertFalse(expirations.isCurrentRefresh(playerId, first));
        assertFalse(expirations.isCurrentRefresh(otherPlayer, second));
    }

    private static CommerceRepository.ActiveBuff buff(Instant deadline) {
        CommerceRepository.ActiveBuff buff = mock(CommerceRepository.ActiveBuff.class);
        when(buff.expiresAt()).thenReturn(deadline);
        return buff;
    }

    private record Scheduled(Runnable callback, BukkitTask task, long ticks) {}
}

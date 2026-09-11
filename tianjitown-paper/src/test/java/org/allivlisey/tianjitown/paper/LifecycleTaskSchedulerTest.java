package org.allivlisey.tianjitown.paper;

import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LifecycleTaskSchedulerTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final BukkitScheduler bukkit = mock(BukkitScheduler.class);
    private LifecycleTaskScheduler scheduler;

    @BeforeEach
    void setup() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(bukkit);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        scheduler = new TownStartupCoordinator(plugin).scheduler;
        scheduler.start();
    }

    @AfterEach
    void shutdown() {
        scheduler.stopAccepting();
        scheduler.shutdown();
    }

    @Test
    void shutdownAloneRejectsSubmissionsAndAllowsAFreshExecutor() throws Exception {
        assertThrows(IllegalStateException.class, scheduler::start);
        scheduler.shutdown();
        assertFalse(scheduler.runMain(() -> fail("disabled callback")));
        assertFalse(scheduler.runAsync(() -> fail("disabled worker")));
        scheduler.start();
        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(scheduler.runAsync(ran::countDown));
        assertTrue(ran.await(5, TimeUnit.SECONDS));
    }

    @Test
    void discardsQueuedMainCallbacksAcrossRestart() {
        Runnable action = mock(Runnable.class);
        assertTrue(scheduler.runMainLater(action, 5));
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(bukkit).runTaskLater(eq(plugin), callback.capture(), eq(5L));
        scheduler.stopAccepting();
        assertFalse(scheduler.runMain(action));
        scheduler.shutdown();
        scheduler.start();
        callback.getValue().run();
        verifyNoInteractions(action);
        assertTrue(scheduler.runMain(action));
        verify(bukkit).runTask(eq(plugin), callback.capture());
        callback.getValue().run();
        verify(action).run();
    }

    @Test
    void rejectsLateWorkerCallbackAndDrainsWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        Runnable action = mock(Runnable.class);
        assertTrue(scheduler.runAsync(() -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("worker timed out");
                }
                accepted.set(scheduler.runMain(action));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.stopAccepting();
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertFalse(accepted.get());
        verifyNoInteractions(action);
        scheduler.shutdown();
        assertFalse(scheduler.runAsync(action));
        verify(bukkit).cancelTasks(plugin);
    }
}

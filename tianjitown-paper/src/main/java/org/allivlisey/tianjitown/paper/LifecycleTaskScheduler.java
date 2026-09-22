package org.allivlisey.tianjitown.paper;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.task.AsyncTaskTracker;


import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.allivlisey.tianjitown.paper.TownStartupCoordinator.*;

final class LifecycleTaskScheduler {
    private final TianjiTownPlugin plugin;
    private final TownStartupCoordinator startup;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AsyncTaskTracker asyncTasks = new AsyncTaskTracker();
    private final ThreadLocal<Long> asyncGeneration = new ThreadLocal<>();
    private final Set<String> periodicFailures = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService asyncExecutor;
    private volatile boolean accepting;

    LifecycleTaskScheduler(TianjiTownPlugin plugin, TownStartupCoordinator startup) {
        this.plugin = plugin;
        this.startup = startup;
    }

    long start() {
        if (asyncExecutor != null) {
            throw new IllegalStateException("上一生命周期尚未关闭");
        }
        long generation = lifecycleGeneration.incrementAndGet();
        periodicFailures.clear();
        asyncTasks.startAccepting();
        asyncExecutor = Executors.newFixedThreadPool(4,
                Thread.ofPlatform().daemon(true).name("TianjiTown-Async-", 0).factory());
        accepting = true;
        return generation;
    }

    void stopAccepting() {
        accepting = false;
        lifecycleGeneration.incrementAndGet();
        asyncTasks.stopAccepting();
    }

    public boolean runAsync(Runnable task) {
        java.util.Objects.requireNonNull(task, "task");
        ExecutorService executor = asyncExecutor;
        Long inheritedGeneration = asyncGeneration.get();
        long generation = inheritedGeneration == null
                ? lifecycleGeneration.get() : inheritedGeneration;
        if (executor == null || !isCurrentLifecycle(generation)) {
            return false;
        }
        try {
            executor.execute(() -> {
                if (!asyncTasks.begin()) {
                    return;
                }
                asyncGeneration.set(generation);
                try {
                    if (isCurrentLifecycle(generation)) {
                        task.run();
                    }
                } catch (RuntimeException | LinkageError exception) {
                    if (isCurrentLifecycle(generation)) {
                        plugin.getLogger().severe(startup.plainText(ASYNC_TASK_FAILURE,
                                Map.of("detail", safeMessage(exception))));
                    }
                } finally {
                    asyncGeneration.remove();
                    asyncTasks.complete();
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            if (!executor.isShutdown()) {
                throw exception;
            }
            return false;
        }
    }

    public boolean runMain(Runnable task) {
        return scheduleMain(task, 0L);
    }

    public boolean runMainLater(Runnable task, long delayTicks) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException(startup.plainText(NEGATIVE_DELAY));
        }
        return scheduleMain(task, delayTicks);
    }

    private boolean scheduleMain(Runnable task, long delayTicks) {
        java.util.Objects.requireNonNull(task, "task");
        Long inheritedGeneration = asyncGeneration.get();
        long generation = inheritedGeneration == null
                ? lifecycleGeneration.get() : inheritedGeneration;
        if (!isCurrentLifecycle(generation)) {
            return false;
        }
        Runnable guarded = () -> {
            if (!isCurrentLifecycle(generation)) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().severe(startup.plainText(MAIN_THREAD_CALLBACK_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
        };
        try {
            if (delayTicks == 0L) {
                plugin.getServer().getScheduler().runTask(plugin, guarded);
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, guarded, delayTicks);
            }
            return true;
        } catch (RuntimeException exception) {
            if (isCurrentLifecycle(generation)) {
                plugin.getLogger().warning(startup.plainText(MAIN_THREAD_CALLBACK_SUBMIT_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
            return false;
        }
    }

    boolean isCurrentLifecycle(long generation) {
        return accepting && plugin.isEnabled() && lifecycleGeneration.get() == generation;
    }

    private void runPeriodic(String messageKey, Runnable task) {
        try {
            task.run();
            periodicFailures.remove(messageKey);
        } catch (RuntimeException | LinkageError exception) {
            // 同一周期任务持续失败时只记录首次，避免依赖故障造成日志洪泛。
            if (periodicFailures.add(messageKey)) {
                plugin.getLogger().severe(startup.plainText(messageKey,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        }
    }

    void shutdown() {
        stopAccepting();
        ExecutorService executor = asyncExecutor;
        if (executor != null) {
            executor.shutdown();
        }
        if (!asyncTasks.awaitQuiescence(Duration.ofSeconds(30))) {
            plugin.getLogger().severe(startup.plainText(ASYNC_SHUTDOWN_TIMEOUT,
                    Map.of("active", asyncTasks.active())));
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        asyncExecutor = null;
        plugin.getServer().getScheduler().cancelTasks(plugin);
    }

    void registerPeriodic(TownRuntime runtime) {
        registerPeriodicTask(() -> {
            if (plugin.townUi() != null) plugin.townUi().deliverPlayerChanges();
        }, 20L, 20L);
        registerPeriodicTask(
                () -> runPeriodic(PERIODIC_SQLITE_RECOVERY_FAILURE, runtime::checkRecovery),
                20L * 30, 20L * 30);
        registerPeriodicTask(
                () -> runPeriodic(PERIODIC_RESIDENCE_RECONCILIATION_FAILURE,
                        runtime::reconcileAll), 20L * 10,
                20L * 60 * 60);
        registerPeriodicTask(
                () -> runPeriodic(PERIODIC_VOTE_SETTLEMENT_FAILURE,
                        runtime::settleDueVotes), 20L * 30,
                20L * 60);
        registerPeriodicTask(
                () -> runPeriodic(PERIODIC_TERRITORY_BONUS_INDEX_FAILURE,
                        runtime.bonuses()::refreshIndex),
                20L * 15, 20L * 30);
        registerPeriodicTask(
                () -> runPeriodic(PERIODIC_BEACON_EFFECT_FAILURE,
                        runtime.bonuses()::refreshBeaconEffects),
                20L * 10, runtime.bonuses().settings().beacon().refreshIntervalTicks());
        registerPeriodicTask(
                () -> runPeriodic(PERIODIC_REFUND_COUNTER_FAILURE,
                        runtime.bonuses()::cleanupCounters),
                20L * 60, 20L * 60 * 60);
    }
    private void registerPeriodicTask(Runnable task, long delay, long interval) {
        long generation = lifecycleGeneration.get();
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (isCurrentLifecycle(generation)) {
                task.run();
            }
        }, delay, interval);
    }
}

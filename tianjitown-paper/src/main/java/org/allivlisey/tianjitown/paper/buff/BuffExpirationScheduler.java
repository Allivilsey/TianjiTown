package org.allivlisey.tianjitown.paper.buff;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Owns refresh generations and expiration tasks; all access is on the main thread. */
final class BuffExpirationScheduler {
    private static final String EXPIRATION_SCHEDULE_FAILURE =
            "log.buff.expiration-schedule-failure";
    private static final String EXPIRATION_CANCEL_FAILURE =
            "log.buff.expiration-cancel-failure";

    private final TianjiTownPlugin plugin;
    private final BiConsumer<UUID, Long> onExpiration;
    private final Map<UUID, Long> refreshGenerations = new HashMap<>();
    private final Map<UUID, BukkitTask> expirationTasks = new HashMap<>();
    private final Map<UUID, Instant> expirationDeadlines = new HashMap<>();
    private long generationSequence;

    BuffExpirationScheduler(TianjiTownPlugin plugin, BiConsumer<UUID, Long> onExpiration) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.onExpiration = Objects.requireNonNull(onExpiration, "onExpiration");
    }

    void clearAll() {
        clearExpirationTasks();
        refreshGenerations.clear();
    }

    void playerQuit(UUID playerId) {
        cancelExpirationTask(playerId);
        expirationDeadlines.remove(playerId);
        refreshGenerations.remove(playerId);
    }

    void forgetRefresh(UUID playerId, long generation) {
        refreshGenerations.remove(playerId, generation);
    }

    void retryExpiration(UUID playerId, long generation) {
        if (!isCurrentRefresh(playerId, generation)) {
            return;
        }
        Instant retryAt = Instant.now().plusSeconds(1);
        if (!scheduleExpirationTask(playerId, generation, retryAt)) {
            expirationDeadlines.put(playerId, retryAt);
        }
    }

    long nextRefreshGeneration(UUID playerId) {
        Instant previousDeadline = expirationDeadlines.get(playerId);
        cancelExpirationTask(playerId);
        long next = ++generationSequence;
        refreshGenerations.put(playerId, next);
        if (previousDeadline != null) {
            scheduleExpirationTask(playerId, next, previousDeadline);
        }
        return next;
    }

    boolean isCurrentRefresh(UUID playerId, long generation) {
        return Objects.equals(refreshGenerations.get(playerId), generation);
    }

    void scheduleExpiration(UUID playerId, List<CommerceRepository.ActiveBuff> buffs,
                            long refreshGeneration) {
        if (!isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        cancelExpirationTask(playerId);
        expirationDeadlines.remove(playerId);
        Instant now = Instant.now();
        CommerceRepository.ActiveBuff next = buffs.stream()
                .filter(buff -> buff.expiresAt().isAfter(now))
                .min(Comparator.comparing(CommerceRepository.ActiveBuff::expiresAt))
                .orElse(null);
        if (next == null) {
            return;
        }
        if (!scheduleExpirationTask(playerId, refreshGeneration, next.expiresAt())) {
            expirationDeadlines.put(playerId, next.expiresAt());
        }
    }

    void ensureExpirationAt(UUID playerId, long refreshGeneration, Instant deadline) {
        if (!isCurrentRefresh(playerId, refreshGeneration) || deadline == null) {
            return;
        }
        Instant currentDeadline = expirationDeadlines.get(playerId);
        if (currentDeadline != null && !currentDeadline.isAfter(deadline)) {
            return;
        }
        cancelExpirationTask(playerId);
        expirationDeadlines.remove(playerId);
        if (!scheduleExpirationTask(playerId, refreshGeneration, deadline)) {
            expirationDeadlines.put(playerId, deadline);
        }
    }

    private boolean scheduleExpirationTask(UUID playerId, long refreshGeneration,
                                           Instant deadline) {
        if (!isCurrentRefresh(playerId, refreshGeneration)) {
            return false;
        }
        long delayTicks = ticksUntil(Instant.now(), deadline);
        try {
            BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!isCurrentRefresh(playerId, refreshGeneration)) {
                    return;
                }
                expirationTasks.remove(playerId);
                expirationDeadlines.remove(playerId);
                onExpiration.accept(playerId, refreshGeneration);
            }, delayTicks);
            expirationTasks.put(playerId, task);
            expirationDeadlines.put(playerId, deadline);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(plugin.messages().plainText(EXPIRATION_SCHEDULE_FAILURE,
                    Map.of("player", playerId, "detail", safeText(safeMessage(exception)))));
            return false;
        }
    }

    private void cancelExpirationTask(UUID playerId) {
        BukkitTask task = expirationTasks.remove(playerId);
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning(plugin.messages().plainText(EXPIRATION_CANCEL_FAILURE,
                        Map.of("player", playerId,
                                "detail", safeText(safeMessage(exception)))));
            }
        }
    }

    private void clearExpirationTasks() {
        for (UUID playerId : List.copyOf(expirationTasks.keySet())) {
            cancelExpirationTask(playerId);
        }
        expirationDeadlines.clear();
    }

    private static long ticksUntil(Instant now, Instant expiresAt) {
        long remainingMillis = Math.max(0L, Duration.between(now, expiresAt).toMillis());
        return Math.max(1L, (remainingMillis + 49L) / 50L);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(String text) {
        return text == null ? "" : text.replace('&', '＆').replace('§', '�');
    }
}

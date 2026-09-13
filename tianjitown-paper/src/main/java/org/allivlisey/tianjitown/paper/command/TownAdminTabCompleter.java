package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TownAdminTabCompleter {
    private static final long REFRESH_INTERVAL_TICKS = 20L * 15;
    private static final long STALE_NANOS = Duration.ofSeconds(20).toNanos();
    private static final String COMPLETION_CACHE_RESTORED =
            "log.admin.completion-cache-restored";
    private static final String COMPLETION_CACHE_REFRESH_FAILED =
            "log.admin.completion-cache-refresh-failed";
    private final TianjiTownPlugin plugin;
    private final TownAdminCompletionEngine engine;
    private final AtomicReference<TownAdminCompletionEngine.Snapshot> snapshot =
            new AtomicReference<>(TownAdminCompletionEngine.Snapshot.empty());
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();
    private final AtomicLong refreshedAt = new AtomicLong();
    private volatile TownRuntime runtime;
    private volatile boolean started;

    public TownAdminTabCompleter(TianjiTownPlugin plugin) {
        this.plugin = plugin;
        this.engine = new TownAdminCompletionEngine(plugin.messages()::plainText);
    }

    public void start(TownRuntime runtime) {
        this.runtime = runtime;
        requestRefresh();
        if (!started) {
            started = true;
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::requestRefresh,
                    REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);
        }
    }

    public void stop() {
        runtime = null;
        started = false;
        refreshing.set(false);
        snapshot.set(TownAdminCompletionEngine.Snapshot.empty());
        refreshedAt.set(0L);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (!TownAdminPermissions.hasAny(sender::hasPermission)) {
            return List.of();
        }
        if (System.nanoTime() - refreshedAt.get() > STALE_NANOS) {
            requestRefresh();
        }
        TownAdminCompletionEngine.Snapshot current = snapshot.get();
        List<String> suggestions = engine.complete(args, current, dynamic(sender, current));
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            return suggestions.stream().filter(topic -> TownAdminPermissions.canViewHelpTopic(
                    sender::hasPermission, topic)).toList();
        }
        return suggestions;
    }

    private void requestRefresh() {
        if (runtime == null || !plugin.isEnabled() || !refreshing.compareAndSet(false, true)) {
            return;
        }
        if (!plugin.runAsync(this::refreshClaimed)) {
            refreshing.set(false);
        }
    }

    private void refreshClaimed() {
        TownRuntime currentRuntime = runtime;
        try {
            List<TownSnapshot> towns = currentRuntime.repository().listTowns(true);
            Map<UUID, List<UUID>> members = currentRuntime.repository().listMemberIdsByTown();
            snapshot.set(new TownAdminCompletionEngine.Snapshot(
                    towns.stream().map(town -> new TownAdminCompletionEngine.TownCandidate(
                            town.id(), town.profile().residenceName(), town.status())).toList(), members,
                    currentRuntime.buffs().settings().buffs().values().stream().collect(
                            java.util.stream.Collectors.toMap(definition -> definition.key(),
                                    definition -> Math.min(5, definition.maximumLevel())))));
            refreshedAt.set(System.nanoTime());
            if (refreshFailureLogged.compareAndSet(true, false)) {
                plugin.getLogger().info(plugin.messages().plainText(COMPLETION_CACHE_RESTORED));
            }
        } catch (RuntimeException exception) {
            if (refreshFailureLogged.compareAndSet(false, true)) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        COMPLETION_CACHE_REFRESH_FAILED,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        } finally {
            refreshing.set(false);
        }
    }

    private TownAdminCompletionEngine.Dynamic dynamic(CommandSender sender,
                                                       TownAdminCompletionEngine.Snapshot current) {
        Map<UUID, TownAdminCompletionEngine.PlayerCandidate> players = new LinkedHashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            players.put(online.getUniqueId(), new TownAdminCompletionEngine.PlayerCandidate(
                    online.getUniqueId(), online.getName(), true));
        }
        if (plugin.getServer().isPrimaryThread()) {
            current.membersByTown().values().stream().flatMap(List::stream).distinct()
                    .filter(playerId -> !players.containsKey(playerId))
                    .forEach(playerId -> {
                        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
                        players.put(playerId, new TownAdminCompletionEngine.PlayerCandidate(
                                playerId, offline.getName(), false));
                    });
        }
        return new TownAdminCompletionEngine.Dynamic(List.copyOf(players.values()),
                sender instanceof Player);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }
}

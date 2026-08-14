package cn.tianji.town.paper;

import cn.tianji.town.storage.town.ApplicationSnapshot;
import cn.tianji.town.storage.town.TownSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class TownAdminTabCompleter implements TabCompleter {
    private static final long REFRESH_INTERVAL_TICKS = 20L * 15;
    private static final long STALE_NANOS = Duration.ofSeconds(20).toNanos();
    private final TianjiTownPlugin plugin;
    private final TownAdminCompletionEngine engine = new TownAdminCompletionEngine();
    private final AtomicReference<TownAdminCompletionEngine.Snapshot> snapshot =
            new AtomicReference<>(TownAdminCompletionEngine.Snapshot.empty());
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();
    private final AtomicLong refreshedAt = new AtomicLong();
    private volatile TownRuntime runtime;
    private volatile boolean started;

    TownAdminTabCompleter(TianjiTownPlugin plugin) {
        this.plugin = plugin;
    }

    void start(TownRuntime runtime) {
        this.runtime = runtime;
        requestRefresh();
        if (!started) {
            started = true;
            plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::refresh,
                    REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command command,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (!TownAdminPermissions.hasAny(sender::hasPermission)) {
            return List.of();
        }
        if (System.nanoTime() - refreshedAt.get() > STALE_NANOS) {
            requestRefresh();
        }
        TownAdminCompletionEngine.Snapshot current = snapshot.get();
        List<String> suggestions = engine.complete(args, current, dynamic(sender, current));
        if (args.length <= 1) {
            return suggestions.stream().filter(root -> TownAdminPermissions.canUseRoot(
                    sender::hasPermission, root)).toList();
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (!TownAdminPermissions.canUseRoot(sender::hasPermission, root)) {
            return List.of();
        }
        if (root.equals("help") && args.length == 2) {
            return suggestions.stream().filter(topic -> TownAdminPermissions.canViewHelpTopic(
                    sender::hasPermission, topic)).toList();
        }
        return suggestions;
    }

    private void requestRefresh() {
        if (runtime == null || !plugin.isEnabled() || !refreshing.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::refreshClaimed);
    }

    private void refresh() {
        if (runtime == null || !plugin.isEnabled() || !refreshing.compareAndSet(false, true)) {
            return;
        }
        refreshClaimed();
    }

    private void refreshClaimed() {
        TownRuntime currentRuntime = runtime;
        try {
            List<ApplicationSnapshot> applications = currentRuntime.repository()
                    .listApplicationsForCompletion(500);
            List<TownSnapshot> towns = currentRuntime.repository().listTowns(true);
            Map<UUID, List<UUID>> members = currentRuntime.repository().listMemberIdsByTown();
            snapshot.set(new TownAdminCompletionEngine.Snapshot(
                    applications.stream().map(application ->
                            new TownAdminCompletionEngine.ApplicationCandidate(application.id(),
                                    application.text().name(),
                                    application.status())).toList(),
                    towns.stream().map(town -> new TownAdminCompletionEngine.TownCandidate(
                            town.id(), town.profile().name(), town.status())).toList(), members));
            refreshedAt.set(System.nanoTime());
            if (refreshFailureLogged.compareAndSet(true, false)) {
                plugin.getLogger().info("管理员命令补全缓存已恢复。");
            }
        } catch (RuntimeException exception) {
            if (refreshFailureLogged.compareAndSet(false, true)) {
                plugin.getLogger().warning("刷新管理员命令补全缓存失败，将继续使用旧缓存: "
                        + safeMessage(exception));
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
}

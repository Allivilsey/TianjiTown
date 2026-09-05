package org.allivlisey.tianjitown.paper.buff;

import org.allivlisey.tianjitown.paper.config.BuffSettings;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Coordinates purchases, database refreshes and player lifecycle events. */
public final class BuffRuntime implements Listener {
    private static final long REFRESH_RETRY_DELAY_TICKS = 20L * 5;
    private static final String BUFF_SHOP_PAUSED = "chat.buff.shop-paused";
    private static final String REFRESH_FAILURE = "log.buff.refresh-failure";
    private static final String REFRESH_CHECK_FAILURE = "log.buff.refresh-check-failure";
    private static final String REFUND_REASON = "log.buff.refund-reason";
    private static final String APPLICATION_FAILURE_REFUNDED =
            "chat.buff.application-failure-refunded";
    private static final String EXPIRATION_CLEANUP_FAILURE =
            "log.buff.expiration-cleanup-failure";

    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final CommerceRepository repository;
    private final BuffSettings settings;
    private final BuffPlayerEffects effects;
    private final BuffExpirationScheduler expirations;
    private final Set<Player> awaitingSync = new HashSet<>();
    private boolean huskSyncHook;

    public void registerHuskSyncHook() {
        huskSyncHook = HuskSyncBuffHook.register(plugin, this, this::onSyncComplete);
    }

    void onSyncComplete(Player player) {
        plugin.runMain(() -> {
            if (!player.isOnline()) {
                return;
            }
            awaitingSync.remove(player);
            effects.forgetPlayer(player);
            refreshPlayer(player, true, false);
        });
    }

    public BuffRuntime(TianjiTownPlugin plugin, TownRuntime host,
                       CommerceRepository repository, BuffSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.effects = new BuffPlayerEffects(plugin, settings);
        this.expirations = new BuffExpirationScheduler(plugin, this::expirePlayer);
    }

    public CommerceRepository repository() {
        return repository;
    }

    public BuffSettings settings() {
        return settings;
    }

    public boolean buffShopEnabled() {
        return plugin.getConfig().getBoolean("buffs.shop-enabled",
                settings.buffShopEnabled());
    }

    public void buyBuffAction(Player player, String key, BuffDurationOption duration,
                       Consumer<CommerceRepository.BuffPurchase> success,
                       Consumer<RuntimeException> failure) {
        if (!buffShopEnabled() || !host.consumptionEnabled()) {
            failure.accept(new IllegalStateException(plugin.messages().plainText(BUFF_SHOP_PAUSED)));
            return;
        }
        try {
            BuffDefinition definition = settings.requireBuff(key);
            host.writeAction(player, () -> repository.purchaseBuff(player.getUniqueId(),
                            player.getName(), definition, settings.label(key), duration,
                            host.settlement().scale(),
                            "buff-purchase:" + UUID.randomUUID(), Instant.now()),
                    purchase -> verifyBuffPurchase(player, purchase, success, failure),
                    failure);
        } catch (RuntimeException exception) {
            failure.accept(exception);
        }
    }

    public void buyBuffAction(Player player, String key, int weeks, int level,
                       Consumer<CommerceRepository.BuffPurchase> success,
                       Consumer<RuntimeException> failure) {
        if (!buffShopEnabled() || !host.consumptionEnabled()) {
            failure.accept(new IllegalStateException(plugin.messages().plainText(BUFF_SHOP_PAUSED)));
            return;
        }
        try {
            BuffDefinition definition = settings.requireBuff(key);
            host.writeAction(player, () -> repository.purchaseBuff(player.getUniqueId(),
                            player.getName(), definition, settings.label(key), weeks, level,
                            host.settlement().scale(),
                            "buff-purchase:" + UUID.randomUUID(), Instant.now()),
                    purchase -> verifyBuffPurchase(player, purchase, success, failure), failure);
        } catch (RuntimeException exception) {
            failure.accept(exception);
        }
    }

    public void refreshAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayer(player);
        }
    }

    public void refreshPlayer(Player player) {
        refreshPlayer(player, false, false);
    }

    private void refreshPlayer(Player player, boolean expireRecords,
                                boolean normalizeRespawnHealth) {
        if (!player.isOnline() || awaitingSync.contains(player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long refreshGeneration = expirations.nextRefreshGeneration(playerId);
        Consumer<List<CommerceRepository.ActiveBuff>> apply = buffs -> {
            if (player.isOnline() && expirations.isCurrentRefresh(playerId, refreshGeneration)) {
                try {
                    effects.applyBuffs(player, buffs, normalizeRespawnHealth);
                    expirations.scheduleExpiration(playerId, buffs, refreshGeneration);
                } catch (RuntimeException | LinkageError exception) {
                    plugin.getLogger().severe(plugin.messages().plainText(REFRESH_FAILURE,
                            Map.of("player", player.getUniqueId(),
                                    "detail", safeText(safeMessage(exception)))));
                    retryRefresh(player, playerId, refreshGeneration, expireRecords, exception);
                }
            }
        };
        if (expireRecords) {
            host.writeAction(player, () -> {
                Instant now = Instant.now();
                repository.expireBuffsForPlayer(playerId, now);
                return repository.activeBuffsForPlayer(playerId, now);
            }, apply, exception -> retryRefresh(player, playerId, refreshGeneration,
                    true, exception));
        } else {
            host.readAction(player,
                    () -> repository.activeBuffsForPlayer(playerId, Instant.now()), apply,
                    exception -> retryRefresh(player, playerId, refreshGeneration,
                            false, exception));
        }
    }

    private void retryRefresh(Player player, UUID playerId, long refreshGeneration,
                              boolean expireRecords, Throwable exception) {
        if (!expirations.isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        plugin.getLogger().warning(plugin.messages().plainText(REFRESH_CHECK_FAILURE,
                Map.of("player", playerId, "detail", safeText(safeMessage(exception)))));
        if (!plugin.runMainLater(() -> {
            if (!expirations.isCurrentRefresh(playerId, refreshGeneration)) {
                return;
            }
            if (!player.isOnline()) {
                expirations.forgetRefresh(playerId, refreshGeneration);
                return;
            }
            refreshPlayer(player, expireRecords, false);
        }, REFRESH_RETRY_DELAY_TICKS)) {
            expirations.forgetRefresh(playerId, refreshGeneration);
        }
    }

    public void clearAll() {
        awaitingSync.clear();
        expirations.clearAll();
        effects.clearAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (huskSyncHook) {
            awaitingSync.add(player);
            return;
        }
        // 登录时先收尾停服期间已到期的记录，再校验并恢复玩家效果。
        plugin.runMain(() -> refreshPlayer(player, true, false));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // 重生后重新校验 Buff，避免已到期效果残留。
        plugin.runMain(() -> refreshPlayer(event.getPlayer(), true, true));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        awaitingSync.remove(event.getPlayer());
        effects.forgetPlayer(event.getPlayer());
        expirations.playerQuit(playerId);
    }

    private void verifyBuffPurchase(Player player, CommerceRepository.BuffPurchase purchase,
                                    Consumer<CommerceRepository.BuffPurchase> success,
                                    Consumer<RuntimeException> failure) {
        UUID playerId = player.getUniqueId();
        long refreshGeneration = expirations.nextRefreshGeneration(playerId);
        expirations.ensureExpirationAt(playerId, refreshGeneration, purchase.buff().expiresAt());
        host.readAction(player,
                () -> repository.activeBuffsForPlayer(playerId, Instant.now()), buffs -> {
            if (!player.isOnline() || !expirations.isCurrentRefresh(playerId, refreshGeneration)) {
                refreshAllPlayers();
                success.accept(purchase);
                return;
            }
            try {
                effects.applyBuffs(player, buffs, false);
                expirations.scheduleExpiration(playerId, buffs, refreshGeneration);
                refreshAllPlayers();
                success.accept(purchase);
            } catch (RuntimeException | LinkageError exception) {
                host.writeAction(player,
                        () -> repository.refundActiveBuff(purchase.buff().buffId(), null,
                                "SYSTEM", plugin.messages().plainText(REFUND_REASON,
                                        Map.of("detail", safeText(safeMessage(exception))))),
                        refunded -> {
                    refreshAllPlayers();
                    failure.accept(new IllegalStateException(
                            plugin.messages().plainText(APPLICATION_FAILURE_REFUNDED,
                                    Map.of("detail", safeText(safeMessage(exception)))), exception));
                        }, refundFailure -> {
                            retryRefresh(player, playerId, refreshGeneration, false, refundFailure);
                            failure.accept(refundFailure);
                        });
            }
        }, exception -> {
            retryRefresh(player, playerId, refreshGeneration, false, exception);
            failure.accept(exception);
        });
    }

    private void expirePlayer(UUID playerId, long refreshGeneration) {
        try {
            handleExpiration(playerId, refreshGeneration);
        } catch (RuntimeException | LinkageError exception) {
            retryExpiration(playerId, refreshGeneration, exception);
        }
    }

    private void handleExpiration(UUID playerId, long refreshGeneration) {
        if (!expirations.isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        host.writeAction(Bukkit.getConsoleSender(), () -> repository.expireBuffsForPlayer(playerId,
                        Instant.now()),
                ignored -> {
                    if (!expirations.isCurrentRefresh(playerId, refreshGeneration)) {
                        return;
                    }
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null || !player.isOnline()) {
                        expirations.forgetRefresh(playerId, refreshGeneration);
                        return;
                    }
                    refreshPlayer(player);
                }, exception -> {
                    retryExpiration(playerId, refreshGeneration, exception);
                });
    }

    private void retryExpiration(UUID playerId, long refreshGeneration, Throwable exception) {
        if (!expirations.isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        plugin.getLogger().warning(plugin.messages().plainText(EXPIRATION_CLEANUP_FAILURE,
                Map.of("player", playerId, "detail", safeText(safeMessage(exception)))));
        expirations.retryExpiration(playerId, refreshGeneration);
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

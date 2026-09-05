package org.allivlisey.tianjitown.paper.bonus;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BeaconInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Owns beacon permissions, recording and managed player effects on the main thread. */
final class TownBeaconEffects {
    private static final String BEACON_REFRESH_OBJECT_FAILURE =
            "log.bonus.beacon-refresh-object-failure";
    private static final String BEACON_RECORD_OBJECT_FAILURE =
            "log.bonus.beacon-record-object-failure";
    private static final String BEACON_CLEANUP_OBJECT_FAILURE =
            "log.bonus.beacon-cleanup-object-failure";
    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final TownBonusRepository repository;
    private final TownBonusSettings.BeaconEnhancement settings;
    private final Supplier<TownBonusRepository.BonusIndex> index;
    private final Runnable refreshIndex;
    private final Map<PlayerEffectKey, ManagedEffect> managedEffects = new HashMap<>();
    private final AtomicBoolean beaconPlayerFailureLogged = new AtomicBoolean();
    private final AtomicBoolean beaconCleanupFailureLogged = new AtomicBoolean();

    TownBeaconEffects(TianjiTownPlugin plugin, TownRuntime host,
                      TownBonusRepository repository, TownBonusSettings.BeaconEnhancement settings,
                      Supplier<TownBonusRepository.BonusIndex> index, Runnable refreshIndex) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.index = Objects.requireNonNull(index, "index");
        this.refreshIndex = Objects.requireNonNull(refreshIndex, "refreshIndex");
    }

    boolean beaconEnabled() {
        return plugin.getConfig().getBoolean("territory.beacon.enabled", settings.enabled());
    }

    void refreshBeaconEffects() {
        if (!plugin.isEnabled()) {
            return;
        }
        if (!beaconEnabled()) {
            clearManagedEffects();
            return;
        }
        TownBonusSettings.BeaconEnhancement config = settings;
        TownBonusRepository.BonusIndex snapshot = index.get();
        Map<PlayerEffectKey, Integer> desiredEffects = new HashMap<>();
        boolean playerFailure = false;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                collectDesiredEffects(player, snapshot, desiredEffects);
            } catch (RuntimeException | LinkageError exception) {
                playerFailure = true;
                if (beaconPlayerFailureLogged.compareAndSet(false, true)) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            BEACON_REFRESH_OBJECT_FAILURE,
                            Map.of("detail", safeText(safeMessage(exception)))));
                }
            }
        }
        if (!playerFailure) {
            beaconPlayerFailureLogged.set(false);
        }
        applyManagedEffects(desiredEffects, config);
    }

    private void collectDesiredEffects(Player player,
                                       TownBonusRepository.BonusIndex snapshot,
                                       Map<PlayerEffectKey, Integer> desiredEffects) {
        if (!player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        UUID townId = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                world.getUID(), player.getChunk().getX(), player.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        Map<String, Integer> effects = townId == null ? null
                : snapshot.beaconEffects().get(townId);
        Location location = player.getLocation();
        if (residenceName == null || effects == null || !host.landProtection().contains(
                residenceName, world.getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ())) {
            return;
        }
        for (Map.Entry<String, Integer> effect : effects.entrySet()) {
            PotionEffectType type = PotionEffectType.getByKey(
                    org.bukkit.NamespacedKey.fromString(effect.getKey()));
            if (type != null) {
                desiredEffects.merge(new PlayerEffectKey(player.getUniqueId(), type),
                        effect.getValue(), Math::max);
            }
        }
    }

    void clearAll() {
        clearManagedEffects();
        managedEffects.clear();
    }

    void onBeaconInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (!beaconEnabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || block.getType() != Material.BEACON) {
            return;
        }
        TownBonusRepository.BonusIndex snapshot = index.get();
        UUID townId = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                block.getWorld().getUID(), block.getChunk().getX(), block.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        if (residenceName == null || !host.landProtection().contains(residenceName,
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ())) {
            return;
        }
        Player player = event.getPlayer();
        UUID membership = snapshot.memberships().get(player.getUniqueId());
        MemberRole role = snapshot.roles().get(player.getUniqueId());
        if (townId.equals(membership) && role != null && role.isLeader()) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        player.sendActionBar(plugin.messages().component("chat.bonus.beacon-edit-forbidden"));
    }

    void onBeaconInventoryClose(InventoryCloseEvent event) {
        if (!beaconEnabled() || !(event.getInventory() instanceof BeaconInventory inventory)
                || !(inventory.getHolder() instanceof Beacon beacon)) {
            return;
        }
        plugin.runMain(() -> recordBeaconEffects(beacon));
    }

    private void recordBeaconEffects(Beacon beacon) {
        try {
            recordBeaconEffectsChecked(beacon);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(plugin.messages().plainText(
                    BEACON_RECORD_OBJECT_FAILURE,
                    Map.of("detail", safeText(safeMessage(exception)))));
        }
    }

    private void recordBeaconEffectsChecked(Beacon beacon) {
        Block block = beacon.getBlock();
        TownBonusRepository.BonusIndex snapshot = index.get();
        UUID townId = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                block.getWorld().getUID(), block.getChunk().getX(), block.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        if (beacon.getTier() < 1 || residenceName == null || !host.landProtection().contains(
                residenceName, block.getWorld().getUID(), block.getX(), block.getY(),
                block.getZ())) {
            return;
        }
        List<PotionEffect> effects = java.util.stream.Stream.of(beacon.getPrimaryEffect(),
                        beacon.getSecondaryEffect()).filter(Objects::nonNull).toList();
        for (PotionEffect effect : effects) {
            String effectKey = effect.getType().getKey().toString();
            host.write(plugin.getServer().getConsoleSender(),
                    () -> repository.recordBeaconEffect(townId, effectKey,
                            effect.getAmplifier()), changed -> {
                        if (changed) {
                            refreshIndex.run();
                        }
                    });
        }
    }

    private void applyManagedEffects(Map<PlayerEffectKey, Integer> desired,
                                     TownBonusSettings.BeaconEnhancement config) {
        int duration = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                config.refreshIntervalTicks() * 2 + 40));
        for (Map.Entry<PlayerEffectKey, Integer> entry : desired.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey().playerId());
            if (player == null) {
                continue;
            }
            int amplifier = entry.getValue();
            player.addPotionEffect(new PotionEffect(entry.getKey().type(), duration, amplifier,
                    true, true, true));
            managedEffects.put(entry.getKey(), new ManagedEffect(amplifier, duration));
        }
        for (PlayerEffectKey key : new ArrayList<>(managedEffects.keySet())) {
            if (desired.containsKey(key)) {
                continue;
            }
            removeManagedEffect(key, managedEffects.remove(key));
        }
    }

    private void clearManagedEffects() {
        boolean failed = false;
        for (Map.Entry<PlayerEffectKey, ManagedEffect> entry
                : List.copyOf(managedEffects.entrySet())) {
            try {
                removeManagedEffect(entry.getKey(), entry.getValue());
            } catch (RuntimeException | LinkageError exception) {
                if (!failed && beaconCleanupFailureLogged.compareAndSet(false, true)) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            BEACON_CLEANUP_OBJECT_FAILURE,
                            Map.of("detail", safeText(safeMessage(exception)))));
                }
                failed = true;
            }
        }
        if (!failed) {
            beaconCleanupFailureLogged.set(false);
        }
        managedEffects.clear();
    }

    private void removeManagedEffect(PlayerEffectKey key, ManagedEffect managed) {
        Player player = plugin.getServer().getPlayer(key.playerId());
        if (player == null || managed == null) {
            return;
        }
        PotionEffect current = player.getPotionEffect(key.type());
        if (current != null && current.getAmplifier() == managed.amplifier()
                && current.getDuration() <= managed.maximumDuration() + 40) {
            player.removePotionEffect(key.type());
        }
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private record PlayerEffectKey(UUID playerId, PotionEffectType type) {
    }

    private record ManagedEffect(int amplifier, int maximumDuration) {
    }
}

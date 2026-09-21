package org.allivlisey.tianjitown.paper.bonus;

import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository.ChunkKey;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Main-thread source discovery and renewal. Potion effects always expire naturally. */
final class TownBeaconEffects {
    private static final int DISCOVERY_BATCH_SIZE = 32;
    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final TownBonusSettings.BeaconEnhancement settings;
    private final Supplier<TownBonusRepository.BonusIndex> index;
    private final Predicate<Beacon> hasBeam;
    private final Map<ChunkKey, Set<Position>> sources = new HashMap<>();
    private final Set<ChunkKey> scannedChunks = new HashSet<>();
    private final Set<ChunkKey> pendingChunks = new LinkedHashSet<>();
    private boolean discoveryScheduled;
    private boolean refreshScheduled;
    private boolean failureLogged;
    private boolean failedThisRefresh;

    TownBeaconEffects(TianjiTownPlugin plugin, TownRuntime host,
                      TownBonusSettings.BeaconEnhancement settings,
                      Supplier<TownBonusRepository.BonusIndex> index) {
        this(plugin, host, settings, index, PaperBeaconActivity::hasBeam);
    }

    TownBeaconEffects(TianjiTownPlugin plugin, TownRuntime host,
                      TownBonusSettings.BeaconEnhancement settings,
                      Supplier<TownBonusRepository.BonusIndex> index, Predicate<Beacon> hasBeam) {
        this.plugin = Objects.requireNonNull(plugin);
        this.host = Objects.requireNonNull(host);
        this.settings = Objects.requireNonNull(settings);
        this.index = Objects.requireNonNull(index);
        this.hasBeam = Objects.requireNonNull(hasBeam);
    }

    boolean beaconEnabled() {
        return plugin.getConfig().getBoolean("territory.beacon.enabled", settings.enabled());
    }

    void refreshBeaconEffects() {
        if (!plugin.isEnabled() || !beaconEnabled()) {
            clearAll();
            return;
        }
        failedThisRefresh = false;
        var snapshot = index.get();
        reconcileChunks(snapshot);
        discoverBatch();
        Map<UUID, Map<PotionEffectType, PotionEffect>> towns = new HashMap<>();
        for (var entry : sources.entrySet()) {
            try {
                collectSourceEffects(entry.getKey(), entry.getValue(), snapshot, towns);
            } catch (RuntimeException | LinkageError exception) {
                reportFailure(exception);
            }
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                if (!player.isOnline()) continue;
                UUID townId = townAt(player.getLocation(), snapshot);
                var effects = towns.get(townId);
                if (effects != null) effects.values().forEach(player::addPotionEffect);
            } catch (RuntimeException | LinkageError exception) {
                reportFailure(exception);
            }
        }
        if (!failedThisRefresh) failureLogged = false;
    }

    private void reconcileChunks(TownBonusRepository.BonusIndex snapshot) {
        Set<ChunkKey> loaded = new HashSet<>();
        for (ChunkKey key : snapshot.territories().keySet()) {
            World world = plugin.getServer().getWorld(key.worldId());
            if (world != null && world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                loaded.add(key);
                if (!scannedChunks.contains(key)) pendingChunks.add(key);
            }
        }
        sources.keySet().retainAll(loaded);
        scannedChunks.retainAll(loaded);
        pendingChunks.retainAll(loaded);
    }

    private void discoverBatch() {
        int remaining = DISCOVERY_BATCH_SIZE;
        var pending = pendingChunks.iterator();
        while (remaining-- > 0 && pending.hasNext()) {
            ChunkKey key = pending.next();
            pending.remove();
            try {
                World world = plugin.getServer().getWorld(key.worldId());
                if (world == null || !index.get().territories().containsKey(key)
                        || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) continue;
                Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
                Set<Position> found = new HashSet<>();
                // Filter block entities before taking states; never walk all blocks or load a chunk.
                for (BlockState state : chunk.getTileEntities(
                        block -> block.getType() == Material.BEACON, false)) {
                    found.add(new Position(state.getX(), state.getY(), state.getZ()));
                }
                sources.put(key, found);
                scannedChunks.add(key);
            } catch (RuntimeException | LinkageError exception) {
                reportFailure(exception);
            }
        }
        if (!pendingChunks.isEmpty() && !discoveryScheduled) {
            discoveryScheduled = plugin.runMain(() -> {
                discoveryScheduled = false;
                if (plugin.isEnabled() && beaconEnabled()) discoverBatch();
                else clearAll();
            });
        }
    }

    private void collectSourceEffects(ChunkKey key, Set<Position> positions,
                                      TownBonusRepository.BonusIndex snapshot,
                                      Map<UUID, Map<PotionEffectType, PotionEffect>> towns) {
        World world = plugin.getServer().getWorld(key.worldId());
        if (world == null || !world.isChunkLoaded(key.chunkX(), key.chunkZ())) return;
        Chunk.LoadLevel level = world.getChunkAt(key.chunkX(), key.chunkZ()).getLoadLevel();
        if (level != Chunk.LoadLevel.TICKING && level != Chunk.LoadLevel.ENTITY_TICKING) return;
        var iterator = positions.iterator();
        while (iterator.hasNext()) {
            Position position = iterator.next();
            try {
                Block block = world.getBlockAt(position.x(), position.y(), position.z());
                if (block.getType() != Material.BEACON) {
                    iterator.remove();
                    continue;
                }
                UUID townId = townAt(block.getLocation(), snapshot);
                if (townId == null || !(block.getState(false) instanceof Beacon beacon)
                        || beacon.getTier() < 1 || !hasBeam.test(beacon)) continue;
                var effects = towns.computeIfAbsent(townId, ignored -> new HashMap<>());
                mergeEffect(effects, beacon.getPrimaryEffect());
                mergeEffect(effects, beacon.getSecondaryEffect());
            } catch (RuntimeException | LinkageError exception) {
                reportFailure(exception);
            }
        }
    }

    private static void mergeEffect(Map<PotionEffectType, PotionEffect> effects, PotionEffect effect) {
        if (effect == null) return;
        effects.merge(effect.getType(), effect, (left, right) -> {
            if (left.getAmplifier() != right.getAmplifier()) {
                return left.getAmplifier() > right.getAmplifier() ? left : right;
            }
            return left.getDuration() >= right.getDuration() ? left : right;
        });
    }

    private UUID townAt(Location location, TownBonusRepository.BonusIndex snapshot) {
        World world = location.getWorld();
        if (world == null) return null;
        UUID townId = snapshot.territories().get(new ChunkKey(world.getUID(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4));
        String residence = townId == null ? null : snapshot.residenceNames().get(townId);
        return residence != null && host.landProtection().contains(residence, world.getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()) ? townId : null;
    }

    void clearAll() {
        sources.clear();
        scannedChunks.clear();
        pendingChunks.clear();
        // Previously applied effects keep their native duration, including on plugin disable.
    }

    void onChunkUnload(Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        sources.remove(key);
        scannedChunks.remove(key);
        pendingChunks.remove(key);
    }

    void onChunkLoad(Chunk chunk) {
        if (!beaconEnabled()) return;
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (index.get().territories().containsKey(key)) {
            scannedChunks.remove(key);
            pendingChunks.add(key);
            requestRefresh();
        }
    }

    void sourceChanged(Block block) {
        if (!beaconEnabled()) return;
        ChunkKey key = new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
        if (index.get().territories().containsKey(key)) {
            sources.computeIfAbsent(key, ignored -> new HashSet<>())
                    .add(new Position(block.getX(), block.getY(), block.getZ()));
            requestRefresh();
        }
    }

    void requestRefresh() {
        if (!refreshScheduled && plugin.isEnabled()) {
            refreshScheduled = plugin.runMain(() -> {
                refreshScheduled = false;
                refreshBeaconEffects();
            });
        }
    }

    private boolean mayEdit(Player player, Block block) {
        var snapshot = index.get();
        UUID townId = townAt(block.getLocation(), snapshot);
        if (townId == null) return true;
        MemberRole role = snapshot.roles().get(player.getUniqueId());
        if (townId.equals(snapshot.memberships().get(player.getUniqueId()))
                && role != null && role.isLeader()) return true;
        player.sendActionBar(plugin.messages().component("chat.bonus.beacon-edit-forbidden"));
        return false;
    }

    void onBeaconInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (beaconEnabled() && event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null
                && block.getType() == Material.BEACON && !mayEdit(event.getPlayer(), block)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
        }
    }

    void onBeaconEffectChange(PlayerChangeBeaconEffectEvent event) {
        if (beaconEnabled() && !mayEdit(event.getPlayer(), event.getBeacon())) {
            event.setCancelled(true);
        }
    }

    private void reportFailure(Throwable exception) {
        failedThisRefresh = true;
        if (!failureLogged) {
            failureLogged = true;
            String detail = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            plugin.getLogger().warning(plugin.messages().plainText("log.bonus.beacon-refresh-object-failure",
                    Map.of("detail", detail.replace('&', '＆').replace('§', '�'))));
        }
    }

    private record Position(int x, int y, int z) {}
}

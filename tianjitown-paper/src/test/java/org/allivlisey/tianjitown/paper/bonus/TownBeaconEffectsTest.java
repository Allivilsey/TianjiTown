package org.allivlisey.tianjitown.paper.bonus;

import io.papermc.paper.registry.RegistryAccess;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository.ChunkKey;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownBeaconEffectsTest {
    private MockedStatic<RegistryAccess> registryMock;
    private final UUID townId = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final TownRuntime host = mock(TownRuntime.class);
    private final LandProtectionService land = mock(LandProtectionService.class);
    private final Player player = mock(Player.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final Map<ChunkKey, UUID> territories = new HashMap<>();
    private final Map<ChunkKey, Chunk> chunks = new HashMap<>();
    private final Set<ChunkKey> loaded = new HashSet<>();
    private final Map<ChunkKey, List<Block>> blocks = new HashMap<>();
    private final Set<Beacon> beams = new HashSet<>();
    private final List<Runnable> main = new ArrayList<>();
    private TownBeaconEffects effects;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() {
        registryMock = mockStatic(RegistryAccess.class);
        RegistryAccess access = (RegistryAccess) java.lang.reflect.Proxy.newProxyInstance(
                RegistryAccess.class.getClassLoader(), new Class<?>[]{RegistryAccess.class},
                (proxy, method, arguments) -> java.lang.reflect.Proxy.newProxyInstance(
                        Registry.class.getClassLoader(), new Class<?>[]{Registry.class},
                        (registry, operation, keys) -> operation.getName().equals("getOrThrow")
                                ? mock(PotionEffectType.class) : null));
        registryMock.when(RegistryAccess::registryAccess).thenReturn(access);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(plugin.messages().plainText(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        when(plugin.runMain(any())).thenAnswer(call -> {
            main.add(call.getArgument(0));
            return true;
        });
        when(server.getWorld(worldId)).thenReturn(world);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(world.getUID()).thenReturn(worldId);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call ->
                loaded.contains(new ChunkKey(worldId, call.getArgument(0), call.getArgument(1))));
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> {
            ChunkKey key = new ChunkKey(worldId, call.getArgument(0), call.getArgument(1));
            assertTrue(loaded.contains(key), "must not load an absent source chunk");
            return chunks.get(key);
        });
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            ChunkKey key = new ChunkKey(worldId, x >> 4, z >> 4);
            assertTrue(loaded.contains(key), "must not read an unloaded source block");
            return blocks.get(key).stream().filter(block -> block.getX() == x
                    && block.getY() == y && block.getZ() == z).findFirst().orElseThrow();
        });
        when(host.landProtection()).thenReturn(land);
        when(land.contains(eq("res"), eq(worldId), anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 1024, 70, 0));
        territories.put(new ChunkKey(worldId, 64, 0), townId);
        effects = new TownBeaconEffects(plugin, host, new TownBonusSettings.BeaconEnhancement(true, 100),
                () -> new TownBonusRepository.BonusIndex(Map.of(), Map.of(), territories,
                        Map.of(townId, "res")), beams::contains);
    }

    @AfterEach
    void closeRegistry() {
        registryMock.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void discoversExistingBeaconAndRenewsDistantVisitorWithNativeDuration(int tier) {
        PotionEffect nativeEffect = effect(PotionEffectType.SPEED, 0, (9 + tier * 2) * 20);
        beacon(1, tier, nativeEffect, null);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(nativeEffect);
        // Membership is intentionally empty. Discovery is once per loaded territory chunk.
        effects.refreshBeaconEffects();
        verify(chunks.get(new ChunkKey(worldId, 0, 0)), times(1)).getTileEntities(any(), eq(false));
        verify(player, never()).removePotionEffect(any());
    }

    @Test
    void sourceChangesReplaceCurrentEffectsAndKeepSecondaryDuration() {
        PotionEffect speed = effect(PotionEffectType.SPEED, 0, 340);
        PotionEffect haste = effect(PotionEffectType.HASTE, 1, 340);
        PotionEffect regeneration = effect(PotionEffectType.REGENERATION, 0, 340);
        Beacon beacon = beacon(1, 4, speed, regeneration);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(regeneration);
        clearInvocations(player);
        // Source coordinates are retained, while the block state is read again.
        Beacon updated = mock(Beacon.class);
        when(updated.getTier()).thenReturn(4);
        when(updated.getPrimaryEffect()).thenReturn(haste);
        beams.add(updated);
        when(beacon.getBlock().getState(false)).thenReturn(updated);
        effects.sourceChanged(beacon.getBlock());
        main.removeFirst().run();
        verify(player).addPotionEffect(haste);
        verify(player, never()).addPotionEffect(speed);
        verify(player, never()).addPotionEffect(regeneration);
        verify(player, never()).removePotionEffect(any());
    }

    @Test
    void aggregatesSourcesAndDowngradesWhenStrongestBeaconIsRemoved() {
        PotionEffect weak = effect(PotionEffectType.HASTE, 0, 220);
        PotionEffect strong = effect(PotionEffectType.HASTE, 1, 340);
        Beacon high = beacon(1, 4, strong, null);
        Beacon low = beacon(2, 1, weak, null);
        effects.refreshBeaconEffects();
        verify(player, times(1)).addPotionEffect(strong);
        verify(player, never()).addPotionEffect(weak);
        clearInvocations(player);
        when(high.getBlock().getType()).thenReturn(Material.AIR);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(weak);
        clearInvocations(player);
        when(low.getTier()).thenReturn(0);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        verify(player, never()).removePotionEffect(any());
    }

    @Test
    void sameLevelUsesLongestNativeDurationWithoutBorrowingDurationFromWeakerEffect() {
        PotionEffect shortStrong = effect(PotionEffectType.SPEED, 1, 220);
        PotionEffect longWeak = effect(PotionEffectType.SPEED, 0, 340);
        beacon(1, 1, shortStrong, null);
        Beacon other = beacon(2, 4, longWeak, null);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(shortStrong);
        clearInvocations(player);
        PotionEffect longStrong = effect(PotionEffectType.SPEED, 1, 340);
        when(other.getPrimaryEffect()).thenReturn(longStrong);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(longStrong);
    }

    @Test
    void blockedBeamStopsRenewalEvenWithNonzeroTierAndRestoresWhenVisible() {
        PotionEffect speed = effect(PotionEffectType.SPEED, 0, 340);
        Beacon beacon = beacon(1, 4, speed, null);
        beams.remove(beacon);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        beams.add(beacon);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(speed);
    }

    @Test
    void unloadedOrNonTickingChunkNeverProvidesCachedEffectsAndRecovers() {
        PotionEffect speed = effect(PotionEffectType.SPEED, 0, 340);
        beacon(1, 4, speed, null);
        ChunkKey key = new ChunkKey(worldId, 0, 0);
        Chunk chunk = chunks.get(key);
        effects.refreshBeaconEffects();
        clearInvocations(player);
        when(chunk.getLoadLevel()).thenReturn(Chunk.LoadLevel.BORDER);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        when(chunk.getLoadLevel()).thenReturn(Chunk.LoadLevel.TICKING);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(speed);
        clearInvocations(player);
        effects.onChunkUnload(chunk);
        loaded.remove(key);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        loaded.add(key);
        effects.onChunkLoad(chunk);
        main.removeFirst().run();
        verify(player).addPotionEffect(speed);
        verify(chunk, times(2)).getTileEntities(any(), eq(false));
        verify(chunk, never()).addPluginChunkTicket(any());
        verify(player, never()).removePotionEffect(any());
    }

    @Test
    void onlyRegisteredAndResidenceValidatedSourceAndPlayerLocationsReceiveEffects() {
        Beacon beacon = beacon(1, 4, effect(PotionEffectType.SPEED, 0, 340), null);
        when(land.contains("res", worldId, 1, 64, 0)).thenReturn(false);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        when(land.contains("res", worldId, 1, 64, 0)).thenReturn(true);
        when(land.contains("res", worldId, 1024, 70, 0)).thenReturn(false);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        when(land.contains("res", worldId, 1024, 70, 0)).thenReturn(true);
        territories.remove(new ChunkKey(worldId, 64, 0));
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        territories.put(new ChunkKey(worldId, 64, 0), townId);
        territories.remove(new ChunkKey(worldId, 0, 0));
        effects.sourceChanged(beacon.getBlock());
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
    }

    @Test
    void archivedDisabledAndStoppedPluginLeavePotionsToExpireNaturally() {
        beacon(1, 4, effect(PotionEffectType.SPEED, 0, 340), null);
        effects.refreshBeaconEffects();
        clearInvocations(player);
        config.set("territory.beacon.enabled", false);
        effects.refreshBeaconEffects();
        effects.clearAll();
        verify(player, never()).addPotionEffect(any());
        verify(player, never()).removePotionEffect(any());
        config.set("territory.beacon.enabled", true);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(any());
        clearInvocations(player);
        territories.clear();
        effects.refreshBeaconEffects();
        when(plugin.isEnabled()).thenReturn(false);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        verify(player, never()).removePotionEffect(any());
    }

    @Test
    void discoversNewlyPlacedBeaconInPreviouslyScannedChunkAndCoalescesEvents() {
        chunk(0);
        effects.refreshBeaconEffects();
        verify(player, never()).addPotionEffect(any());
        Beacon beacon = beacon(1, 4, effect(PotionEffectType.SPEED, 0, 340), null);
        effects.sourceChanged(beacon.getBlock());
        effects.sourceChanged(beacon.getBlock());
        assertEquals(1, main.size());
        main.removeFirst().run();
        verify(player).addPotionEffect(any());
    }

    @Test
    void discoveryIsBatchedAndPendingWorkDoesNotLoadChunksAfterTheyUnload() {
        for (int x = 0; x < 65; x++) chunk(x);
        effects.refreshBeaconEffects();
        int scanned = chunks.values().stream().mapToInt(chunk ->
                (int) mockingDetails(chunk).getInvocations().stream()
                        .filter(call -> call.getMethod().getName().equals("getTileEntities")).count()).sum();
        assertEquals(32, scanned);
        assertEquals(1, main.size());
        loaded.clear();
        main.removeFirst().run();
        while (!main.isEmpty()) main.removeFirst().run();
        verify(player, never()).addPotionEffect(any());
    }

    @Test
    void brokenSourceDoesNotSuppressOtherSourcesAndRetriesLater() {
        Beacon broken = beacon(1, 4, effect(PotionEffectType.SPEED, 0, 340), null);
        PotionEffect haste = effect(PotionEffectType.HASTE, 0, 340);
        beacon(2, 4, haste, null);
        effects.refreshBeaconEffects();
        clearInvocations(player);
        when(broken.getBlock().getState(false)).thenThrow(new IllegalStateException("unavailable"));
        effects.refreshBeaconEffects();
        effects.refreshBeaconEffects();
        verify(player, times(2)).addPotionEffect(haste);
        verify(plugin.getLogger(), times(1)).warning(anyString());
        Block brokenBlock = broken.getBlock();
        doReturn(broken).when(brokenBlock).getState(false);
        effects.refreshBeaconEffects();
        verify(player).addPotionEffect(broken.getPrimaryEffect());
    }

    @SuppressWarnings("unchecked")
    private Chunk chunk(int x) {
        ChunkKey key = new ChunkKey(worldId, x, 0);
        return chunks.computeIfAbsent(key, ignored -> {
            Chunk chunk = mock(Chunk.class);
            when(chunk.getWorld()).thenReturn(world);
            when(chunk.getX()).thenReturn(x);
            when(chunk.getZ()).thenReturn(0);
            when(chunk.getLoadLevel()).thenReturn(Chunk.LoadLevel.ENTITY_TICKING);
            blocks.put(key, new ArrayList<>());
            when(chunk.getTileEntities(any(), eq(false))).thenAnswer(call -> {
                Predicate<Block> predicate = call.getArgument(0);
                return blocks.get(key).stream().filter(predicate)
                        .map(block -> block.getState(false)).toList();
            });
            loaded.add(key);
            territories.put(key, townId);
            return chunk;
        });
    }

    private Beacon beacon(int x, int tier, PotionEffect primary, PotionEffect secondary) {
        chunk(x >> 4);
        Block block = mock(Block.class);
        Beacon beacon = mock(Beacon.class);
        when(block.getType()).thenReturn(Material.BEACON);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(0);
        when(block.getLocation()).thenReturn(new Location(world, x, 64, 0));
        when(block.getState(false)).thenReturn(beacon);
        when(beacon.getBlock()).thenReturn(block);
        when(beacon.getX()).thenReturn(x);
        when(beacon.getY()).thenReturn(64);
        when(beacon.getZ()).thenReturn(0);
        when(beacon.getTier()).thenReturn(tier);
        when(beacon.getPrimaryEffect()).thenReturn(primary);
        when(beacon.getSecondaryEffect()).thenReturn(secondary);
        beams.add(beacon);
        blocks.get(new ChunkKey(worldId, x >> 4, 0)).add(block);
        return beacon;
    }

    private static PotionEffect effect(PotionEffectType type, int amplifier, int duration) {
        return new PotionEffect(type, duration, amplifier, true, true, true);
    }
}

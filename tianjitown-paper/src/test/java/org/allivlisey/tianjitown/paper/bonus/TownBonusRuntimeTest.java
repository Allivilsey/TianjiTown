package org.allivlisey.tianjitown.paper.bonus;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BeaconInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownBonusRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final TownRuntime host = mock(TownRuntime.class);
    private final TownBonusRepository repository = mock(TownBonusRepository.class);
    private final LandProtectionService land = mock(LandProtectionService.class);
    private final Player player = mock(Player.class);
    private final Block block = mock(Block.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final UUID playerId = UUID.randomUUID();
    private final UUID townId = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final List<Runnable> async = new ArrayList<>();
    private final List<Runnable> main = new ArrayList<>();
    private final List<Write> writes = new ArrayList<>();
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private final TownBonusRuntime runtime;

    TownBonusRuntimeTest() {
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(plugin.messages().plainText(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        when(plugin.getServer()).thenReturn(mock(Server.class));
        doAnswer(call -> async.add(call.getArgument(0))).when(plugin).runAsync(any());
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        when(host.settlement()).thenReturn(mock(VaultSettlementService.class));
        when(host.landProtection()).thenReturn(land);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isOnline()).thenReturn(true);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(block.getWorld()).thenReturn(world);
        when(block.getChunk()).thenReturn(mock(Chunk.class));
        when(block.getType()).thenReturn(Material.BEACON);
        when(land.contains("res", worldId, 0, 0, 0)).thenReturn(true);
        doAnswer(call -> {
            writes.add(new Write(call.getArgument(1), call.getArgument(2)));
            return null;
        }).when(host).write(any(), any(), any());
        var settings = new TownBonusSettings(
                new TownBonusSettings.BuildingRefund(true, 1, 3000, 12, zone, Set.of(Material.TNT)),
                new TownBonusSettings.BeaconEnhancement(true, 100),
                new TownBonusSettings.Operations(7));
        when(host.settlement().accountId()).thenReturn(UUID.randomUUID());
        runtime = new TownBonusRuntime(plugin, host, repository, mock(org.allivlisey.tianjitown.storage.diagnostics.TownDiagnosticRepository.class), settings, mock(org.bukkit.plugin.Plugin.class));
    }

    @Test
    void refreshCoalescesRequestsAndKeepsOldSnapshotAfterFailure() {
        loadIndex(MemberRole.MEMBER);
        runtime.refreshIndex();
        runtime.refreshIndex();
        assertEquals(2, async.size());
        when(repository.loadBonusIndex()).thenThrow(new IllegalStateException("offline"));
        async.getLast().run();
        PlayerInteractEvent event = beaconInteraction();
        runtime.onBeaconInteract(event);
        verify(event).setCancelled(true);

        doReturn(index(MemberRole.MAYOR)).when(repository).loadBonusIndex();
        runtime.refreshIndex();
        async.getLast().run();
        PlayerInteractEvent leader = beaconInteraction();
        runtime.onBeaconInteract(leader);
        verify(leader, never()).setCancelled(anyBoolean());
    }

    @Test
    void rejectedIndexSubmissionCanRetryAndDisabledPluginDoesNotSchedule() {
        doReturn(false).when(plugin).runAsync(any());
        runtime.refreshIndex();
        doAnswer(call -> async.add(call.getArgument(0))).when(plugin).runAsync(any());
        when(repository.loadBonusIndex()).thenReturn(index(MemberRole.MEMBER));
        runtime.refreshIndex();
        assertEquals(1, async.size());
        async.getFirst().run();
        when(plugin.isEnabled()).thenReturn(false);
        runtime.refreshIndex();
        assertEquals(1, async.size());
    }

    @Test
    void onlyTownLeadersCanEditTerritoryBeaconAndLiveDisableTakesEffect() {
        loadIndex(MemberRole.MEMBER);
        PlayerInteractEvent member = beaconInteraction();
        runtime.onBeaconInteract(member);
        verify(member).setUseInteractedBlock(Event.Result.DENY);
        verify(member).setCancelled(true);
        loadIndex(MemberRole.DEPUTY_MAYOR);
        PlayerInteractEvent deputy = beaconInteraction();
        runtime.onBeaconInteract(deputy);
        verify(deputy, never()).setCancelled(anyBoolean());

        when(land.contains("res", worldId, 0, 0, 0)).thenReturn(false);
        loadIndex(MemberRole.MEMBER);
        PlayerInteractEvent outside = beaconInteraction();
        runtime.onBeaconInteract(outside);
        verify(outside, never()).setCancelled(anyBoolean());

        config.set("territory.beacon.enabled", false);
        assertFalse(runtime.beaconEnabled());
        clearInvocations(land);
        runtime.onBeaconInteract(beaconInteraction());
        verifyNoInteractions(land);
    }

    @Test
    void delayedBeaconRecordingReadsLatestIndexAndToleratesInvalidBlock() {
        Beacon beacon = mock(Beacon.class, withSettings().extraInterfaces(InventoryHolder.class));
        when(beacon.getBlock()).thenReturn(block);
        when(beacon.getTier()).thenReturn(1);
        BeaconInventory inventory = mock(BeaconInventory.class);
        when(inventory.getHolder()).thenReturn((InventoryHolder) beacon);
        InventoryCloseEvent close = mock(InventoryCloseEvent.class);
        when(close.getInventory()).thenReturn(inventory);
        runtime.onBeaconInventoryClose(close);
        verifyNoInteractions(land);
        loadIndex(MemberRole.MAYOR);
        main.getFirst().run();
        verify(land).contains("res", worldId, 0, 0, 0);

        when(beacon.getBlock()).thenThrow(new IllegalStateException("unloaded"));
        runtime.onBeaconInventoryClose(close);
        assertDoesNotThrow(() -> main.getLast().run());
        verify(plugin.getLogger()).warning("log.bonus.beacon-record-object-failure");
    }

    @Test
    void reservesRefundBeforeDeliveryAndSkipsDeniedOrOfflineRecipients() {
        loadIndex(MemberRole.MEMBER);
        BlockPlaceEvent place = buildingPlacement();
        runtime.onPlace(place);
        verify(repository, never()).reserveBuildingRefund(any(), any(), any(), anyInt(), anyInt(),
                any(), anyString(), anyInt());
        assertEquals(1, writes.size());
        when(repository.reserveBuildingRefund(any(), any(), any(), anyInt(), anyInt(), any(),
                anyString(), anyInt())).thenReturn(TownBonusRepository.RefundReservation.granted(1, 3000));
        Object result = writes.getFirst().operation().get();
        ArgumentCaptor<LocalDate> week = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).reserveBuildingRefund(eq(townId), eq(playerId), eq(worldId), eq(0), eq(0),
                week.capture(), eq("minecraft:stone"), eq(3000));
        assertEquals(DayOfWeek.MONDAY, week.getValue().getDayOfWeek());
        assertFalse(week.getValue().isAfter(LocalDate.now(zone)));
        writes.getFirst().success().accept(TownBonusRepository.RefundReservation.denied("limit"));
        when(player.isOnline()).thenReturn(false);
        writes.getFirst().success().accept(result);
        verify(player, never()).getInventory();
    }

    @Test
    void unsafeOrDisabledBuildingRefundsNeverReserveQuota() {
        loadIndex(MemberRole.MEMBER);
        runtime.onPlace(mock(BlockMultiPlaceEvent.class));
        BlockPlaceEvent place = buildingPlacement();
        when(place.getItemInHand().hasItemMeta()).thenReturn(true);
        runtime.onPlace(place);
        when(place.getItemInHand().hasItemMeta()).thenReturn(false);
        when(land.contains("res", worldId, 0, 0, 0)).thenReturn(false);
        runtime.onPlace(place);
        config.set("territory.building-refund.enabled", false);
        assertFalse(runtime.buildingRefundEnabled());
        runtime.onPlace(place);
        assertTrue(writes.isEmpty());
    }

    @Test
    void counterCleanupUsesConfiguredWeeklyRetentionOnWorker() {
        runtime.cleanupCounters();
        verifyNoInteractions(repository);
        LocalDate earliest = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(12);
        async.getFirst().run();
        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).cleanupRefundCounters(cutoff.capture());
        LocalDate latest = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(12);
        assertFalse(cutoff.getValue().isBefore(earliest));
        assertFalse(cutoff.getValue().isAfter(latest));
    }

    private TownBonusRepository.BonusIndex index(MemberRole role) {
        return new TownBonusRepository.BonusIndex(Map.of(playerId, townId), Map.of(playerId, role),
                Map.of(new TownBonusRepository.ChunkKey(worldId, 0, 0), townId),
                Map.of(townId, "res"), Map.of());
    }

    private void loadIndex(MemberRole role) {
        when(repository.loadBonusIndex()).thenReturn(index(role));
        runtime.refreshIndex();
        async.getLast().run();
    }

    private PlayerInteractEvent beaconInteraction() {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private BlockPlaceEvent buildingPlacement() {
        when(block.getType()).thenReturn(Material.STONE);
        ItemStack source = mock(ItemStack.class);
        when(source.getType()).thenReturn(Material.STONE);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getBlockPlaced()).thenReturn(block);
        when(event.getItemInHand()).thenReturn(source);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private record Write(Supplier<Object> operation, Consumer<Object> success) {}
}

package org.allivlisey.tianjitown.paper.runtime;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TerritoryUnit;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.TerritoryService;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownExpansionRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final EconomyRepository finance = mock(EconomyRepository.class);
    private final TownRepository towns = mock(TownRepository.class);
    private final LandProtectionService land = mock(LandProtectionService.class);
    private final UUID townId = UUID.randomUUID();
    private final UUID worldId = UUID.randomUUID();
    private final Set<String> externalAreas = new HashSet<>();
    private EconomyRepository.ExpansionBatchOperation batch;
    private TownExpansionRuntime runtime;

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);
        PluginMessages messages = mock(PluginMessages.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(messages.plainText(anyString(), anyMap())).thenAnswer(c -> c.getArgument(0));
        when(messages.plainText(anyString())).thenAnswer(c -> c.getArgument(0));
        when(plugin.runAsync(any())).thenAnswer(c -> { ((Runnable) c.getArgument(0)).run(); return true; });
        when(plugin.runMain(any())).thenAnswer(c -> { ((Runnable) c.getArgument(0)).run(); return true; });
        when(towns.listLandAccessIds(townId)).thenReturn(List.of());
        batch = new EconomyRepository.ExpansionBatchOperation(UUID.randomUUID(), townId,
                "test-batch", UUID.randomUUID(), "Mayor", 300, "PREPARED", null,
                List.of(expansion(1), expansion(2), expansion(3)));
        when(land.addArea(eq("town"), any(), any())).thenAnswer(c -> {
            LandProtectionService.Area area = c.getArgument(1);
            externalAreas.add(area.name());
            return LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
        });
        when(land.removeArea(eq("town"), any(LandProtectionService.Area.class))).thenAnswer(c -> {
            externalAreas.remove(((LandProtectionService.Area) c.getArgument(1)).name());
            return LandProtectionService.Result.successCode(LandProtectionService.ResultCode.EXPANSION_AREA_REMOVED);
        });
        runtime = new TownExpansionRuntime(plugin, towns, finance, land,
                mock(TerritoryService.class), new TownRuntimeTasks(plugin, new AtomicBoolean(true)), () -> true);
    }

    @Test
    void recoveryRollsBackPersistedAreasIncludingThoseNotReachedBeforeFailure() {
        externalAreas.addAll(Set.of("area1", "area3", "unrelated"));
        when(land.addArea(eq("town"), argThat(a -> a.name().equals("area2")), any()))
                .thenReturn(LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.AREA_ADD_REJECTED));

        recover();

        assertEquals(Set.of("unrelated"), externalAreas);
        for (var expansion : batch.expansions()) {
            verify(land).removeArea("town", new LandProtectionService.Area(
                    expansion.residenceAreaName(), expansion.unit().territory()));
        }
        verify(finance).refundExpansionBatch(eq(batch.batchId()), anyString());
        verify(finance, never()).completeExpansionBatch(any());
    }

    @Test
    void partialExternalFailureIsCleanedBeforeRefund() {
        when(land.addArea(eq("town"), argThat(a -> a.name().equals("area2")), any()))
                .thenAnswer(c -> { externalAreas.add("area2"); throw new IllegalStateException("after add"); });

        recover();

        assertTrue(externalAreas.isEmpty());
        verify(finance).refundExpansionBatch(eq(batch.batchId()), anyString());
    }

    @Test
    void refusesRefundWhenAnAreaCannotBeSafelyRemoved() {
        externalAreas.add("area1");
        when(land.addArea(eq("town"), any(), any()))
                .thenReturn(LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.AREA_BOUNDS_MISMATCH));
        when(land.removeArea(eq("town"), argThat((LandProtectionService.Area a) -> a.name().equals("area1"))))
                .thenReturn(LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.AREA_BOUNDS_MISMATCH));

        recover();

        assertEquals(Set.of("area1"), externalAreas);
        verify(finance, never()).refundExpansionBatch(any(), any());
    }

    @Test
    void uncertainDatabaseFinalizationDoesNotRemoveSuccessfullyProjectedAreas() {
        when(finance.completeExpansionBatch(batch.batchId())).thenThrow(
                new EconomyRepository.StorageUnavailableException("commit result unknown", null));

        recover();

        assertEquals(Set.of("area1", "area2", "area3"), externalAreas);
        verify(land, never()).removeArea(anyString(), any(LandProtectionService.Area.class));
        verify(finance, never()).refundExpansionBatch(any(), any());
    }

    private void recover() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getConsoleSender).thenReturn(mock(ConsoleCommandSender.class));
            runtime.recoverExpansionBatches(List.of(batch));
        }
    }

    private EconomyRepository.ExpansionOperation expansion(int x) {
        return new EconomyRepository.ExpansionOperation(UUID.randomUUID(), townId, UUID.randomUUID(),
                "expansion:" + x, UUID.randomUUID(), 100, "PREPARED", null,
                new TerritoryUnit(x, 0, new InitialTerritory(new ChunkPosition(worldId, "world", x * 5, 0))),
                "town", "area" + x);
    }
}

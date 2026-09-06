package org.allivlisey.tianjitown.paper.station;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.station.StationRecord;
import org.allivlisey.tianjitown.storage.station.StationRepository;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceStationPersistenceTest {
    @Test
    void creationWaitsForDatabaseAndFailureLeavesLecternUntouched() {
        Fixture fixture = new Fixture();
        when(fixture.repository.insert(any())).thenThrow(
                new StationRepository.StorageUnavailableException(new SQLException("offline")));
        assertTrue(fixture.controller.create(fixture.player));
        verify(fixture.data, never()).set(any(), eq(PersistentDataType.STRING), anyString());
        fixture.work.removeFirst().run();
        assertTrue(fixture.directory.records().isEmpty());
        verify(fixture.lectern, never()).update(anyBoolean());

        doReturn(true).when(fixture.repository).insert(any());
        assertTrue(fixture.controller.create(fixture.player));
        fixture.work.removeFirst().run();
        assertEquals(1, fixture.directory.records().size());
        verify(fixture.data).set(fixture.key, PersistentDataType.STRING,
                fixture.directory.records().getFirst().id());
        verify(fixture.messages).send(eq(fixture.player), eq("chat.station.created"), anyMap());
        verify(fixture.plugin, never()).saveConfig();
    }

    @Test
    void removalWaitsForDatabaseAndCanRetryAfterFailure() {
        Fixture fixture = new Fixture();
        StationRecord station = new StationRecord("station", fixture.worldId, "world", 0, 0, 0, null, null);
        when(fixture.repository.load()).thenReturn(List.of(station));
        fixture.directory.load();
        when(fixture.data.get(fixture.key, PersistentDataType.STRING)).thenReturn("station");
        doThrow(new StationRepository.StorageUnavailableException(new SQLException("offline")))
                .when(fixture.repository).deleteAt(fixture.worldId, 0, 0, 0);
        assertTrue(fixture.controller.remove(fixture.player));
        verify(fixture.data, never()).remove(fixture.key);
        fixture.work.removeFirst().run();
        assertEquals(List.of(station), fixture.directory.records());
        verify(fixture.data, never()).remove(fixture.key);
        verify(fixture.messages, never()).send(fixture.player, "station.removed");

        doNothing().when(fixture.repository).deleteAt(fixture.worldId, 0, 0, 0);
        assertTrue(fixture.controller.remove(fixture.player));
        fixture.work.removeFirst().run();
        assertTrue(fixture.directory.records().isEmpty());
        verify(fixture.data).remove(fixture.key);
        verify(fixture.messages).send(fixture.player, "station.removed");
    }

    private static final class Fixture {
        final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        final PluginMessages messages = mock(PluginMessages.class);
        final StationRepository repository = mock(StationRepository.class);
        final StationDirectory directory = new StationDirectory(repository);
        final Player player = mock(Player.class);
        final Lectern lectern = mock(Lectern.class);
        final PersistentDataContainer data = mock(PersistentDataContainer.class);
        final UUID worldId = UUID.randomUUID();
        final NamespacedKey key = new NamespacedKey("tianjitown", "service_station");
        final List<Runnable> work = new ArrayList<>();
        final ServiceStationController controller;

        Fixture() {
            when(plugin.namespace()).thenReturn("tianjitown");
            when(plugin.messages()).thenReturn(messages);
            TownRuntime runtime = mock(TownRuntime.class);
            when(runtime.stations()).thenReturn(directory);
            doAnswer(invocation -> {
                Supplier<Boolean> operation = invocation.getArgument(1);
                Consumer<Boolean> success = invocation.getArgument(2);
                Consumer<RuntimeException> failure = invocation.getArgument(3);
                work.add(() -> {
                    try { success.accept(operation.get()); }
                    catch (RuntimeException exception) { failure.accept(exception); }
                });
                return null;
            }).when(runtime).writeAction(eq(player), any(), any(), any());
            World world = mock(World.class);
            when(world.getUID()).thenReturn(worldId);
            when(world.getName()).thenReturn("world");
            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getState()).thenReturn(lectern);
            when(lectern.getPersistentDataContainer()).thenReturn(data);
            when(lectern.update(false)).thenReturn(true);
            when(player.getTargetBlockExact(6)).thenReturn(block);
            controller = new ServiceStationController(plugin, runtime, ignored -> {}, () -> true);
        }
    }
}

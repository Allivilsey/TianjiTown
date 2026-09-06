package org.allivlisey.tianjitown.paper.station;

import org.allivlisey.tianjitown.storage.station.StationRepository;
import org.allivlisey.tianjitown.storage.station.StationRecord;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StationRegistryTest {
    @Test
    void requiresBothMarkerAndRegisteredLocationAndPersistsRemoval() {
        StationRepository repository = mock(StationRepository.class);
        StationDirectory directory = new StationDirectory(repository);
        when(repository.insert(any())).thenReturn(true);
        NamespacedKey key = new NamespacedKey("tianjitown", "service_station");
        StationRegistry registry = new StationRegistry(directory, key);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("world");
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        Lectern lectern = mock(Lectern.class);
        when(block.getState()).thenReturn(lectern);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(lectern.getPersistentDataContainer()).thenReturn(data);
        when(data.get(key, PersistentDataType.STRING)).thenReturn("station");
        assertFalse(registry.isValidStation(block));
        directory.insert(new StationRecord("station", world.getUID(), "world", 0, 0, 0, null, null));
        assertTrue(new StationRegistry(directory, key).isValidStation(block));
        StationProtectionListener listener = new StationProtectionListener(registry);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);
        listener.onStationBreak(event);
        verify(event).setCancelled(true);
        when(block.getX()).thenReturn(1);
        assertFalse(registry.isValidStation(block));
        when(block.getX()).thenReturn(0);
        when(data.get(key, PersistentDataType.STRING)).thenReturn("copied-marker");
        assertFalse(registry.isValidStation(block));
        directory.deleteAt(world.getUID(), 0, 0, 0);
        assertTrue(new StationRegistry(directory, key).stationRecords().isEmpty());
        verify(repository).deleteAt(world.getUID(), 0, 0, 0);
    }
    @Test
    void failedWritesPreserveSnapshotAndPendingLocationsAreProtected() {
        StationRepository repository = mock(StationRepository.class);
        StationDirectory directory = new StationDirectory(repository);
        UUID worldId = UUID.randomUUID();
        StationRecord record = new StationRecord("station", worldId, "world", 0, 0, 0, null, null);
        when(repository.load()).thenReturn(List.of(record));
        directory.load();
        doThrow(new StationRepository.StorageUnavailableException(new java.sql.SQLException("offline")))
                .when(repository).deleteAt(worldId, 0, 0, 0);
        assertThrows(StationRepository.StorageUnavailableException.class,
                () -> directory.deleteAt(worldId, 0, 0, 0));
        assertEquals(List.of(record), directory.records());
        when(repository.insert(any())).thenThrow(new StationRepository.StorageUnavailableException(
                new java.sql.SQLException("offline")));
        assertThrows(StationRepository.StorageUnavailableException.class, () -> directory.insert(record));
        assertEquals(List.of(record), directory.records());
        Block block = mock(Block.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(block.getWorld()).thenReturn(world);
        StationRegistry registry = new StationRegistry(directory, new NamespacedKey("test", "station"));
        assertTrue(registry.beginChange(block));
        assertFalse(registry.beginChange(block));
        assertTrue(registry.isProtected(block));
        registry.endChange(block);
        assertFalse(registry.isProtected(block));
    }
}

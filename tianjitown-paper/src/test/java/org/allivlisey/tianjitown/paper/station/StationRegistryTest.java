package org.allivlisey.tianjitown.paper.station;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.configuration.file.YamlConfiguration;
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
        TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        NamespacedKey key = new NamespacedKey("tianjitown", "service_station");
        StationRegistry registry = new StationRegistry(plugin, key);
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
        registry.registerStation(block, "station", null, null);
        assertTrue(new StationRegistry(plugin, key).isValidStation(block));
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
        registry.unregisterStationAt(block);
        assertTrue(new StationRegistry(plugin, key).stationRecords().isEmpty());
        verify(plugin, times(2)).saveConfig();
    }
}

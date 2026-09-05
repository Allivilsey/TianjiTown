package org.allivlisey.tianjitown.paper.land;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerritoryPreviewServiceTest {
    @Test
    void replacementAndShutdownCancelSessionsEvenIfOneCancellationFails() {
        TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask first = mock(BukkitTask.class);
        BukkitTask replacement = mock(BukkitTask.class);
        BukkitTask other = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L)))
                .thenReturn(first, replacement, other);
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        Player player = player(world);
        Player second = player(world);
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(worldId, "world", 0, 0));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            TerritoryPreviewService previews = new TerritoryPreviewService(plugin);
            previews.previewSilently(player, territory);
            previews.previewSilently(player, territory);
            verify(first).cancel();
            previews.previewSilently(second, territory);
            doThrow(new IllegalStateException("scheduler stopped")).when(replacement).cancel();
            assertDoesNotThrow(previews::clearPreviews);
            verify(other).cancel();
            assertDoesNotThrow(() -> previews.stopPreview(player.getUniqueId()));
            verify(replacement).cancel();
        }
    }

    @Test
    void offlinePlayerEndsPeriodicPreviewAndRemovesItsSession() {
        TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(42);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(0L), eq(20L)))
                .thenReturn(task);
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        Player player = player(world);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            TerritoryPreviewService previews = new TerritoryPreviewService(plugin);
            previews.previewSilently(player, new InitialTerritory(
                    new ChunkPosition(worldId, "world", 0, 0)));
            ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskTimer(eq(plugin), callback.capture(), eq(0L), eq(20L));
            callback.getValue().run();
            verify(scheduler).cancelTask(42);
            previews.clearPreviews();
            verify(task, never()).cancel();
        }
    }

    private static Player player(World world) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        return player;
    }
}

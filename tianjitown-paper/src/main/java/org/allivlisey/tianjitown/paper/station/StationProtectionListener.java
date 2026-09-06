package org.allivlisey.tianjitown.paper.station;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;


public final class StationProtectionListener implements Listener {
    private final StationRegistry registry;

    StationProtectionListener(StationRegistry registry) {
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(registry::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(registry::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(registry::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(registry::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationBreak(BlockBreakEvent event) {
        if (registry.isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }


}

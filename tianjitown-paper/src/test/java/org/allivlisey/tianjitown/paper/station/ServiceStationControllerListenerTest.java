package org.allivlisey.tianjitown.paper.station;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServiceStationControllerListenerTest {
    @Test
    void registersEveryServiceStationEventHandler() throws NoSuchMethodException {
        assertHandler("onStationInteract", PlayerInteractEvent.class, EventPriority.HIGHEST, false);
        assertHandler("onStationEntityExplosion", EntityExplodeEvent.class, EventPriority.HIGHEST,
                false);
        assertHandler("onStationBlockExplosion", BlockExplodeEvent.class, EventPriority.HIGHEST,
                false);
        assertHandler("onStationPistonExtend", BlockPistonExtendEvent.class, EventPriority.HIGHEST,
                false);
        assertHandler("onStationPistonRetract", BlockPistonRetractEvent.class, EventPriority.HIGHEST,
                false);
        assertHandler("onStationInsertLecternBook", PlayerInsertLecternBookEvent.class,
                EventPriority.MONITOR, true);
        assertHandler("onStationBreak", BlockBreakEvent.class, EventPriority.HIGHEST, false);
    }

    private static void assertHandler(String name, Class<?> eventType, EventPriority priority,
                                      boolean ignoreCancelled) throws NoSuchMethodException {
        Class<?> owner = name.equals("onStationInteract") || name.equals("onStationInsertLecternBook")
                ? ServiceStationController.class : StationProtectionListener.class;
        Method method = owner.getMethod(name, eventType);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertNotNull(handler, () -> name + " must be registered with Paper");
        assertEquals(priority, handler.priority(), name + " priority");
        assertEquals(ignoreCancelled, handler.ignoreCancelled(), name + " cancellation policy");
        assertFalse(method.isSynthetic(), name + " must be a concrete event handler");
    }
}

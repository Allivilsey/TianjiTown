package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.protection.CuboidArea;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidenceGeometryTest {
    @Test
    void matchingCoordinatesInAnotherWorldAreNotHealthy() {
        World expectedWorld = world(UUID.randomUUID());
        CuboidArea expected = area(expectedWorld);
        var bounds = new ResidenceGeometry.Bounds(null, null, expected);

        assertFalse(ResidenceGeometry.matchesBounds(area(world(UUID.randomUUID())), bounds));
        assertTrue(ResidenceGeometry.matchesBounds(area(world(expectedWorld.getUID())), bounds));
        assertFalse(ResidenceGeometry.matchesBounds(area(null), bounds));
    }

    private static CuboidArea area(World world) {
        CuboidArea area = mock(CuboidArea.class);
        when(area.getWorld()).thenReturn(world);
        when(area.getLowVector()).thenReturn(new Vector(0, -64, 0));
        when(area.getHighVector()).thenReturn(new Vector(79, 319, 79));
        return area;
    }

    private static World world(UUID id) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(id);
        return world;
    }
}

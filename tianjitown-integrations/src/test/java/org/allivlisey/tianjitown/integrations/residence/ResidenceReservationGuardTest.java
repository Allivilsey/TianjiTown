package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.event.*;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import org.allivlisey.tianjitown.core.land.*;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidenceReservationGuardTest {
    private final World world = mock(World.class);
    private final UUID worldId = UUID.randomUUID();
    private final ResidenceLandProtectionService service = new ResidenceLandProtectionService(
            mock(Server.class), new HashSet<>());
    private final ResidenceReservationGuard guard = new ResidenceReservationGuard(service);

    private CuboidArea area(int minX, int maxX, int minZ, int maxZ) {
        when(world.getUID()).thenReturn(worldId);
        CuboidArea area = mock(CuboidArea.class);
        when(area.getWorld()).thenReturn(world);
        when(area.getLowVector()).thenReturn(new Vector(minX, -64, minZ));
        when(area.getHighVector()).thenReturn(new Vector(maxX, 319, maxZ));
        return area;
    }

    @Test
    void blocksAllClaimRoutesInUnactivatedCornerAndAllowsOutside() {
        service.reserve("SKY", new InitialTerritory(new ChunkPosition(worldId, "world", 0, 0)));
        service.reservationsLoaded();
        CuboidArea corner = area(-192, -192, -192, -192);
        ResidenceCreationEvent create = new ResidenceCreationEvent(null, "other", null, corner);
        guard.onCreate(create);
        assertTrue(create.isCancelled());
        ResidenceAreaAddEvent add = new ResidenceAreaAddEvent(null, "other", null, corner);
        guard.onAdd(add);
        assertTrue(add.isCancelled());
        ResidenceSizeChangeEvent resize = mock(ResidenceSizeChangeEvent.class);
        when(resize.getNewArea()).thenReturn(corner);
        guard.onResize(resize);
        verify(resize).setCancelled(true);
        ResidenceSubzoneCreationEvent subzone = new ResidenceSubzoneCreationEvent(null, "sub", null, corner);
        guard.onSubzone(subzone);
        assertTrue(subzone.isCancelled());
        ResidenceCreationEvent outside = new ResidenceCreationEvent(null, "outside", null,
                area(-193, -193, -192, -192));
        guard.onCreate(outside);
        assertFalse(outside.isCancelled());
        assertNull(service.reservationCollision(corner, "sky"));
    }

    @Test
    void blocksClaimsUntilDatabaseReservationsAreLoaded() {
        ResidenceCreationEvent event = new ResidenceCreationEvent(null, "other", null, area(0, 0, 0, 0));
        guard.onCreate(event);
        assertTrue(event.isCancelled());
    }
}

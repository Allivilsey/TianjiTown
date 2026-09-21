package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidenceAreaRollbackTest {
    private final Server server = mock(Server.class);
    private final ResidenceManager manager = mock(ResidenceManager.class);
    private final ClaimedResidence residence = mock(ClaimedResidence.class);
    private final World world = mock(World.class);
    private final UUID worldId = UUID.randomUUID();
    private final InitialTerritory territory = new InitialTerritory(new ChunkPosition(worldId, "world", 5, 0));
    private final LandProtectionService.Area expected = new LandProtectionService.Area("area", territory);
    private ResidenceLandProtectionService service;

    @BeforeEach
    void setUp() {
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getWorld(worldId)).thenReturn(world);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(manager.getByName("town")).thenReturn(residence);
        when(residence.isServerLand()).thenReturn(true);
        service = spy(new ResidenceLandProtectionService(server, new HashSet<>()));
        doReturn(manager).when(service).manager();
    }

    @Test
    void refusesSameNamedAreaInAnotherWorld() {
        World another = mock(World.class);
        when(another.getUID()).thenReturn(UUID.randomUUID());
        doReturn(area(another, 0)).when(residence).getArea("area");

        assertEquals(LandProtectionService.ResultCode.AREA_BOUNDS_MISMATCH,
                service.removeArea("town", expected).code());
        verify(residence, never()).removeArea(anyString());
    }

    @Test
    void refusesSameNamedAreaWithDifferentBoundsOrAnUncontrolledOwner() {
        doReturn(area(world, 1)).when(residence).getArea("area");
        assertEquals(LandProtectionService.ResultCode.AREA_BOUNDS_MISMATCH,
                service.removeArea("town", expected).code());
        when(residence.isServerLand()).thenReturn(false);
        assertEquals(LandProtectionService.ResultCode.AREA_OWNER_NOT_CONTROLLED,
                service.removeArea("town", expected).code());
        verify(residence, never()).removeArea(anyString());
    }

    @Test
    void removesOnlyMatchingExpansionAndConfirmsAbsence() {
        doReturn(area(world, 0)).when(residence).getArea("area");
        doAnswer(c -> { when(residence.getArea("area")).thenReturn(null); return null; })
                .when(residence).removeArea("area");

        assertTrue(service.removeArea("town", expected).success());
        verify(residence).removeArea("area");
        verify(manager).calculateChunks(residence);
    }

    @Test
    void leavesMainAreaUntouchedEvenIfItsBoundsMatch() {
        CuboidArea main = area(world, 0);
        when(residence.getArea("area")).thenReturn(main);
        when(residence.getMainArea()).thenReturn(main);

        assertEquals(LandProtectionService.ResultCode.MAIN_AREA_REMOVAL_REJECTED,
                service.removeArea("town", expected).code());
        verify(residence, never()).removeArea(anyString());
    }

    private CuboidArea area(World actualWorld, int offset) {
        return new CuboidArea(new Location(actualWorld, territory.minimumBlockX() + offset, -64, territory.minimumBlockZ()),
                new Location(actualWorld, territory.maximumBlockX() + offset, 319, territory.maximumBlockZ()));
    }
}

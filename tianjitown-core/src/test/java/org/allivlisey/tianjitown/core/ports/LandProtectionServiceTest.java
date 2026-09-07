package org.allivlisey.tianjitown.core.ports;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandProtectionServiceTest {
    private static final UUID WORLD_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final InitialTerritory FIRST = new InitialTerritory(
            new ChunkPosition(WORLD_ID, "world", 0, 0));
    private static final InitialTerritory SECOND = new InitialTerritory(
            new ChunkPosition(WORLD_ID, "world", 5, 0));

    @Test
    void unsupportedDefaultOperationsExposeStableCodesInsteadOfRenderedText() {
        LandProtectionService service = service();

        LandProtectionService.Result teleport = service.setTeleportPoint(
                "TOWN", WORLD_ID, "world", 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
        LandProtectionService.Result addArea = service.addArea(
                "TOWN", new LandProtectionService.Area("north", FIRST), List.of());
        LandProtectionService.Result removeArea = service.removeArea("TOWN", "north");

        assertFalse(teleport.success());
        assertEquals(LandProtectionService.ResultCode.UNSUPPORTED_TELEPORT_POINT,
                teleport.code());
        assertEquals("UNSUPPORTED_TELEPORT_POINT", teleport.code().name());
        assertEquals(LandProtectionService.ResultCode.UNSUPPORTED_ADD_AREA, addArea.code());
        assertEquals(LandProtectionService.ResultCode.UNSUPPORTED_REMOVE_AREA,
                removeArea.code());
    }

    @Test
    void unsupportedMultiAreaInspectionsExposeStableCode() {
        LandProtectionService.Inspection inspection = service().inspect(
                "TOWN", List.of(new LandProtectionService.Area("main", FIRST),
                        new LandProtectionService.Area("north", SECOND)), List.of());

        assertEquals(LandProtectionService.ProjectionState.INVALID, inspection.state());
        assertEquals(LandProtectionService.ResultCode.UNSUPPORTED_MULTI_AREA,
                inspection.code());
        assertEquals("UNSUPPORTED_MULTI_AREA", inspection.code().name());
        assertTrue(inspection.parameters().isEmpty());
    }

    private static LandProtectionService service() {
        return new LandProtectionService() {
            @Override
            public Collision findCollision(InitialTerritory territory) {
                return Collision.none();
            }

            @Override
            public Inspection inspect(String residenceName, InitialTerritory territory,
                                      Collection<UUID> members) {
                return Inspection.healthyCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
            }

            @Override
            public Result create(String residenceName, InitialTerritory territory,
                                 Collection<UUID> members) {
                return Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
            }

            @Override
            public Result remove(String residenceName, InitialTerritory territory) {
                return Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
            }

            @Override
            public Result reconcile(String residenceName, InitialTerritory territory,
                                    Collection<UUID> members, boolean repair) {
                return Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
            }
        };
    }
}

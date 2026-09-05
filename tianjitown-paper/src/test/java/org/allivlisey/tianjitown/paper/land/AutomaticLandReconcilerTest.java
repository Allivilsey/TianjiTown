package org.allivlisey.tianjitown.paper.land;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticLandReconcilerTest {
    private static final UUID WORLD_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<LandProtectionService.Area> AREAS = List.of(
            new LandProtectionService.Area("main", new InitialTerritory(
                    new ChunkPosition(WORLD_ID, "world", 0, 0))));

    @Test
    void keepsHealthyProjectionReadOnly() {
        FakeProtection protection = new FakeProtection(
                LandProtectionService.Inspection.healthy("正常"),
                LandProtectionService.Result.failure("不应调用修复"));

        AutomaticLandReconciler.Outcome outcome = AutomaticLandReconciler.reconcile(
                protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertFalse(outcome.repairAttempted());
        assertTrue(outcome.result().success());
        assertFalse(protection.repairCalled);
    }

    @Test
    void repairsMissingProjectionFromDatabaseSnapshot() {
        LandProtectionService.Result repaired = LandProtectionService.Result.ok("已重建");
        FakeProtection protection = new FakeProtection(
                LandProtectionService.Inspection.missing("投影缺失"), repaired);

        AutomaticLandReconciler.Outcome outcome = AutomaticLandReconciler.reconcile(
                protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertTrue(outcome.repairAttempted());
        assertTrue(protection.repairCalled);
        assertSame(repaired, outcome.result());
    }

    @Test
    void repairsInvalidProjectionFromDatabaseSnapshot() {
        LandProtectionService.Result failure = LandProtectionService.Result.failure("边界冲突");
        FakeProtection protection = new FakeProtection(
                LandProtectionService.Inspection.invalid("边界不一致"), failure);

        AutomaticLandReconciler.Outcome outcome = AutomaticLandReconciler.reconcile(
                protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertTrue(outcome.repairAttempted());
        assertTrue(protection.repairCalled);
        assertSame(failure, outcome.result());
    }

    private static final class FakeProtection implements LandProtectionService {
        private final Inspection inspection;
        private final Result repairResult;
        private boolean repairCalled;

        private FakeProtection(Inspection inspection, Result repairResult) {
            this.inspection = inspection;
            this.repairResult = repairResult;
        }

        @Override
        public Collision findCollision(InitialTerritory territory) {
            return Collision.none();
        }

        @Override
        public Inspection inspect(String residenceName, InitialTerritory territory,
                                  Collection<UUID> members) {
            return inspection;
        }

        @Override
        public Inspection inspect(String residenceName, List<Area> areas,
                                  Collection<UUID> members) {
            return inspection;
        }

        @Override
        public Result create(String residenceName, InitialTerritory territory,
                             Collection<UUID> members) {
            return repairResult;
        }

        @Override
        public Result remove(String residenceName, InitialTerritory territory) {
            return repairResult;
        }

        @Override
        public Result reconcile(String residenceName, InitialTerritory territory,
                                Collection<UUID> members, boolean repair) {
            repairCalled = repair;
            return repairResult;
        }

        @Override
        public Result reconcile(String residenceName, List<Area> areas,
                                Collection<UUID> members, boolean repair) {
            repairCalled = repair;
            return repairResult;
        }
    }
}

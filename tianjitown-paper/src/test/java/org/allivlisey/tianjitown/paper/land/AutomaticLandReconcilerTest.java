package org.allivlisey.tianjitown.paper.land;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                LandProtectionService.Inspection.healthyCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY),
                LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROJECTION_CREATE_REJECTED));

        AutomaticLandReconciler.Outcome outcome = AutomaticLandReconciler.reconcile(
                protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertFalse(outcome.repairAttempted());
        assertTrue(outcome.result().success());
        assertFalse(protection.repairCalled);
        assertEquals(1, protection.inspectionCalls);
    }

    @Test
    void repairsMissingProjectionFromDatabaseSnapshot() {
        LandProtectionService.Result repaired = LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
        FakeProtection protection = new FakeProtection(
                LandProtectionService.Inspection.missingCode(LandProtectionService.ResultCode.PROJECTION_MISSING), repaired);
        protection.afterRepair = LandProtectionService.Inspection.healthyCode(
                LandProtectionService.ResultCode.PROJECTION_HEALTHY);

        AutomaticLandReconciler.Outcome outcome = AutomaticLandReconciler.reconcile(
                protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertTrue(outcome.repairAttempted());
        assertTrue(protection.repairCalled);
        assertSame(repaired, outcome.result());
        assertEquals(2, protection.inspectionCalls);
    }

    @Test
    void repairsInvalidProjectionFromDatabaseSnapshot() {
        LandProtectionService.Result failure = LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROJECTION_CREATE_REJECTED);
        FakeProtection protection = new FakeProtection(
                LandProtectionService.Inspection.invalidCode(LandProtectionService.ResultCode.PROJECTION_CREATE_REJECTED), failure);

        AutomaticLandReconciler.Outcome outcome = AutomaticLandReconciler.reconcile(
                protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertTrue(outcome.repairAttempted());
        assertTrue(protection.repairCalled);
        assertSame(failure, outcome.result());
        assertEquals(1, protection.inspectionCalls);
    }

    @Test
    void successfulWriteWithRemainingDifferenceIsReportedAsFailure() {
        var original = LandProtectionService.Inspection.invalidCode(
                LandProtectionService.ResultCode.MEMBER_PADD_PERMISSION_MISMATCH,
                Map.of("member", MEMBER_ID));
        FakeProtection protection = new FakeProtection(original,
                LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_AUTO_REPAIRED));
        protection.afterRepair = LandProtectionService.Inspection.invalidCode(
                LandProtectionService.ResultCode.MEMBER_IGNITE_PERMISSION_MISMATCH,
                Map.of("member", MEMBER_ID));

        AutomaticLandReconciler.Outcome outcome = AutomaticLandReconciler.reconcile(
                protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertTrue(outcome.repairAttempted());
        assertSame(original, outcome.inspection());
        assertFalse(outcome.result().success());
        assertEquals(protection.afterRepair.code(), outcome.result().code());
        assertEquals(protection.afterRepair.parameters(), outcome.result().parameters());
        assertEquals(2, protection.inspectionCalls);
    }

    @Test
    void successfulWriteWithStillMissingResidenceIsNotReportedAsRepaired() {
        FakeProtection protection = new FakeProtection(
                LandProtectionService.Inspection.missingCode(LandProtectionService.ResultCode.PROJECTION_MISSING),
                LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_AUTO_REPAIRED));

        var outcome = AutomaticLandReconciler.reconcile(protection, "TOWN", AREAS, List.of(MEMBER_ID));

        assertFalse(outcome.result().success());
        assertEquals(LandProtectionService.ResultCode.PROJECTION_MISSING, outcome.result().code());
    }

    @Test
    void reinspectionApiFailureCannotReturnRepairSuccess() {
        FakeProtection protection = new FakeProtection(
                LandProtectionService.Inspection.missingCode(LandProtectionService.ResultCode.PROJECTION_MISSING),
                LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_AUTO_REPAIRED));
        protection.verificationFailure = new IllegalStateException("Residence unavailable");

        assertSame(protection.verificationFailure, assertThrows(IllegalStateException.class,
                () -> AutomaticLandReconciler.reconcile(protection, "TOWN", AREAS, List.of(MEMBER_ID))));
    }

    private static final class FakeProtection implements LandProtectionService {
        private final Inspection inspection;
        private final Result repairResult;
        private boolean repairCalled;
        private Inspection afterRepair;
        private int inspectionCalls;
        private RuntimeException verificationFailure;

        private FakeProtection(Inspection inspection, Result repairResult) {
            this.inspection = inspection;
            this.repairResult = repairResult;
            this.afterRepair = inspection;
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
            inspectionCalls++;
            if (repairCalled && verificationFailure != null) throw verificationFailure;
            return repairCalled ? afterRepair : inspection;
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

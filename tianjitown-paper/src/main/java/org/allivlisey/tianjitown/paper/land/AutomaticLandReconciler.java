package org.allivlisey.tianjitown.paper.land;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AutomaticLandReconciler {
    private AutomaticLandReconciler() {
    }

    public static Outcome reconcile(LandProtectionService protection, String residenceName,
                             List<LandProtectionService.Area> areas,
                             Collection<UUID> members) {
        Objects.requireNonNull(protection, "protection");
        LandProtectionService.Inspection inspection = protection.inspect(
                residenceName, areas, members);
        if (inspection.state() == LandProtectionService.ProjectionState.HEALTHY) {
            return new Outcome(inspection, false,
                    LandProtectionService.Result.fromHealthyInspection(inspection));
        }
        LandProtectionService.Result repaired = protection.reconcile(residenceName, areas, members, true);
        if (repaired.success()) {
            LandProtectionService.Inspection verified = protection.inspect(residenceName, areas, members);
            if (verified.state() != LandProtectionService.ProjectionState.HEALTHY) {
                repaired = LandProtectionService.Result.failureCode(verified.code(), verified.parameters());
            }
        }
        return new Outcome(inspection, true, repaired);
    }

    public record Outcome(LandProtectionService.Inspection inspection, boolean repairAttempted,
                   LandProtectionService.Result result) {
        public Outcome {
            Objects.requireNonNull(inspection, "inspection");
            Objects.requireNonNull(result, "result");
        }
    }
}

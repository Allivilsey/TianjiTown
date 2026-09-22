package org.allivlisey.tianjitown.paper.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.ProvisionResult;
import org.allivlisey.tianjitown.paper.land.ProvisionCoordinator;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.entity.Player;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Inspection, cleanup and fee refunds for failed town provisioning. */
final class TownProvisionRecovery {
    private static final String PROVISION_REFRESH_STATE_ACTION =
            "dialog.provision.refresh-state-action";
    private static final String PROVISION_RECOVERY_REFRESH_ACTION =
            "dialog.provision.recovery-refresh-action";
    private static final String PROVISION_RECOVERY_INSPECTION_FAILED_DETAIL =
            "dialog.provision.recovery-inspection-failed-detail";
    private static final String PROVISION_RECOVERY_VERIFY_ACTION =
            "dialog.provision.recovery-verify-action";
    private static final String PROVISION_RECOVERY_CONTROL_CHECK_FAILED_DETAIL =
            "dialog.provision.recovery-control-check-failed-detail";
    private static final String PROVISION_RECOVERY_CLEANUP_FAILED_DETAIL =
            "dialog.provision.recovery-cleanup-failed-detail";
    private static final String PROVISION_RECOVERY_CLEANUP_API_FAILED_DETAIL =
            "dialog.provision.recovery-cleanup-api-failed-detail";
    private static final String PROVISION_RECOVERY_CLEANUP_ACTION =
            "dialog.provision.recovery-cleanup-action";
    private static final String PROVISION_RECOVERY_REFUND_FAILED_DETAIL =
            "dialog.provision.recovery-refund-failed-detail";
    private static final String PROVISION_RECOVERY_REFUND_ACTION =
            "dialog.provision.recovery-refund-action";
    private static final String PROVISION_RECOVERY_REFUND_CONFIRMATION_FAILED_DETAIL =
            "dialog.provision.recovery-refund-confirmation-failed-detail";
    private static final String PROVISION_RECOVERY_REFUND_CONFIRMATION_ACTION =
            "dialog.provision.recovery-refund-confirmation-action";
    private static final String PROVISION_RECOVERY_EXTERNAL_RESIDENCE =
            "log.provision.recovery-external-residence";
    private static final String PROVISION_RECOVERY_PROJECTION_CLEANED =
            "log.provision.recovery-projection-cleaned";
    private static final String PROVISION_RECOVERY_UNLOCK_REASON =
            "log.provision.recovery-unlock-reason";
    private static final String PROVISION_RECOVERY_CANCEL_REFUND_REASON =
            "log.provision.recovery-cancel-refund-reason";
    private static final String PROVISION_RECOVERY_FORCE_CLEANUP_REASON =
            "log.provision.recovery-force-cleanup-reason";
    private static final String PROVISION_RECOVERY_REASON_WITH_INSPECTION =
            "log.provision.recovery-reason-with-inspection";
    private final TianjiTownPlugin plugin;
    private final TownRepository repository;
    private final LandProtectionService landProtection;
    private final VaultPlayerEconomyService wallet;
    private final ProvisionCoordinator provisions;
    private final TownApplicationFeeRuntime applicationFees;

    TownProvisionRecovery(TianjiTownPlugin plugin,
            TownRepository repository,
            LandProtectionService landProtection,
            VaultPlayerEconomyService wallet, ProvisionCoordinator provisions) {
        this.plugin = plugin;
        this.repository = repository;
        this.landProtection = landProtection;
        this.wallet = wallet;
        this.provisions = provisions;
        this.applicationFees = new TownApplicationFeeRuntime(plugin, repository, wallet, provisions);
    }

    void recoverFailedApplication(Player administrator, UUID applicationId,
                                  TownRepository.RecoveryMode mode,
                                  Consumer<ProvisionResult> callback) {
        if (!provisions.tryBegin(applicationId)) {
            callback.accept(ProvisionResult.busy(
                    ProvisionResult.MessageRef.configured("dialog.provision.busy-detail")));
            return;
        }
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<ProvisionResult> completion = result -> {
            if (!delivered.compareAndSet(false, true)) return;
            provisions.finish(applicationId);
            callback.accept(result);
        };
        runAsync(() -> {
            try {
                var existing = repository.findApplication(applicationId).orElse(null);
                if (existing != null && existing.status() == org.allivlisey.tianjitown.core.application.ApplicationStatus.CANCELLED
                        && existing.applicationFeeStatus() == ApplicationSnapshot.FeeStatus.REFUND_PENDING
                        && mode != TownRepository.RecoveryMode.UNLOCK_FOR_CHANGES) {
                    applicationFees.refundWhileLocked(administrator, applicationId, completion);
                    return;
                }
                TownRepository.Provisioning failed = repository.failedProvision(applicationId);
                runMain(() -> verifyAndRecoverFailedApplication(administrator, failed,
                        mode, completion), completion);
            } catch (RuntimeException exception) {
                runMain(() -> completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                        ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_REFRESH_ACTION))), completion);
            }
        }, completion);
    }

    private void verifyAndRecoverFailedApplication(Player administrator,
                                                   TownRepository.Provisioning failed,
                                                   TownRepository.RecoveryMode mode,
                                                   Consumer<ProvisionResult> completion) {
        LandProtectionService.Inspection inspection;
        try {
            inspection = landProtection.inspect(failed.town().residenceName(),
                    failed.town().territory(), failed.members());
        } catch (RuntimeException | LinkageError exception) {
            completion.accept(ProvisionResult.failure(null,
                    ProvisionResult.MessageRef.configured(
                            PROVISION_RECOVERY_INSPECTION_FAILED_DETAIL,
                            Map.of("detail", safeText(safeMessage(exception)))),
                    ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_VERIFY_ACTION)));
            return;
        }
        // A healthy Residence does not mean provisioning completed: teleport setup can fail later.
        if (inspection.state() != LandProtectionService.ProjectionState.MISSING) {
            boolean controlled;
            try {
                controlled = inspection.state() == LandProtectionService.ProjectionState.HEALTHY
                        || landProtection.isControlledProjection(failed.town().residenceName());
            } catch (RuntimeException | LinkageError exception) {
                completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.configured(
                                PROVISION_RECOVERY_CONTROL_CHECK_FAILED_DETAIL,
                                Map.of("detail", safeText(safeMessage(exception)))),
                        ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_VERIFY_ACTION)));
                return;
            }
            if (!controlled) {
                // 同名但属于玩家的 Residence 不是临时小镇投影，必须保留，
                // 同时允许管理员清理 SQLite 临时数据并要求申请人换代码。
                plugin.getLogger().warning(plugin.messages().plainText(
                        PROVISION_RECOVERY_EXTERNAL_RESIDENCE,
                        Map.of("application", failed.applicationId())));
            } else {
                LandProtectionService.Result cleanup;
                String cleanupFailureKey = PROVISION_RECOVERY_CLEANUP_FAILED_DETAIL;
                String cleanupExceptionDetail = null;
                try {
                    cleanup = landProtection.remove(failed.town().residenceName(),
                            failed.town().territory());
                } catch (RuntimeException | LinkageError exception) {
                    cleanup = LandProtectionService.Result.failureCode(
                            LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                            Map.of("detail", safeText(safeMessage(exception))));
                    cleanupFailureKey = PROVISION_RECOVERY_CLEANUP_API_FAILED_DETAIL;
                    cleanupExceptionDetail = safeText(safeMessage(exception));
                }
                if (!cleanup.success()) {
                    String cleanupDetail = cleanupExceptionDetail == null
                            ? safeText(LandProtectionMessages.detail(plugin.messages(), cleanup))
                            : cleanupExceptionDetail;
                    completion.accept(ProvisionResult.failure(null,
                            ProvisionResult.MessageRef.configured(
                                    cleanupFailureKey, Map.of("detail", cleanupDetail)),
                            ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_CLEANUP_ACTION)));
                    return;
                }
                plugin.getLogger().warning(plugin.messages().plainText(
                        PROVISION_RECOVERY_PROJECTION_CLEANED,
                        Map.of("application", failed.applicationId(),
                                "detail", safeText(LandProtectionMessages.detail(
                                        plugin.messages(), cleanup)))));
            }
        }
        String externalInspectionDetail = safeText(LandProtectionMessages.detail(
                plugin.messages(), inspection));
        String reason = switch (mode) {
            case UNLOCK_FOR_CHANGES -> plugin.messages().plainText(
                    PROVISION_RECOVERY_UNLOCK_REASON);
            case CANCEL_AND_REFUND -> plugin.messages().plainText(
                    PROVISION_RECOVERY_CANCEL_REFUND_REASON);
            case FORCE_CLEANUP -> plugin.messages().plainText(
                    PROVISION_RECOVERY_FORCE_CLEANUP_REASON);
        };
        String recoveryReason = plugin.messages().plainText(
                PROVISION_RECOVERY_REASON_WITH_INSPECTION,
                Map.of("reason", safeText(reason), "inspection", externalInspectionDetail));
        runAsync(() -> {
            try {
                ApplicationSnapshot recovered = repository.recoverFailedProvision(
                        failed.applicationId(), administrator.getUniqueId(),
                        administrator.getName(), recoveryReason,
                        mode);
                if (mode == TownRepository.RecoveryMode.UNLOCK_FOR_CHANGES) {
                    runMain(() -> completion.accept(ProvisionResult.success(recovered)), completion);
                    return;
                }
                runMain(() -> refundRecoveredApplication(administrator, recovered,
                        completion), completion);
            } catch (RuntimeException exception) {
                runMain(() -> completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                        ProvisionResult.MessageRef.configured(PROVISION_REFRESH_STATE_ACTION))), completion);
            }
        }, completion);
    }

    private void refundRecoveredApplication(Player administrator,
                                            ApplicationSnapshot application,
                                            Consumer<ProvisionResult> completion) {
        applicationFees.refundWhileLocked(administrator, application.id(), completion);
    }

    private void runAsync(Runnable task, Consumer<ProvisionResult> completion) {
        if (!plugin.runAsync(() -> guarded(task, completion))) rejected(completion);
    }

    private void runMain(Runnable task, Consumer<ProvisionResult> completion) {
        if (!plugin.runMain(() -> guarded(task, completion))) rejected(completion);
    }

    private void guarded(Runnable task, Consumer<ProvisionResult> completion) {
        try {
            task.run();
        } catch (RuntimeException | LinkageError exception) {
            completion.accept(ProvisionResult.failure(null,
                    ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                    ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_REFRESH_ACTION)));
        }
    }

    private void rejected(Consumer<ProvisionResult> completion) {
        completion.accept(ProvisionResult.failure(null,
                ProvisionResult.MessageRef.configured("dialog.provision.lifecycle-start-failed-detail"),
                ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_REFRESH_ACTION)));
    }
}

package org.allivlisey.tianjitown.paper.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.ProvisionResult;
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
    private static final String PROVISION_RECOVERY_HEALTHY_DETAIL =
            "dialog.provision.recovery-healthy-projection-detail";
    private static final String PROVISION_RECOVERY_HEALTHY_ACTION =
            "dialog.provision.recovery-healthy-projection-action";
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
    private final VaultSettlementService settlement;

    TownProvisionRecovery(TianjiTownPlugin plugin,
            TownRepository repository,
            LandProtectionService landProtection,
            VaultSettlementService settlement) {
        this.plugin = plugin;
        this.repository = repository;
        this.landProtection = landProtection;
        this.settlement = settlement;
    }

    void recoverFailedApplication(Player administrator, UUID applicationId,
                                  TownRepository.RecoveryMode mode,
                                  Consumer<ProvisionResult> completion) {
        plugin.runAsync(() -> {
            try {
                TownRepository.Provisioning failed = repository.failedProvision(applicationId);
                plugin.runMain(() -> verifyAndRecoverFailedApplication(administrator, failed,
                        mode, completion));
            } catch (RuntimeException exception) {
                plugin.runMain(() -> completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                        ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_REFRESH_ACTION))));
            }
        });
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
        if (inspection.state() == LandProtectionService.ProjectionState.HEALTHY) {
            completion.accept(ProvisionResult.failure(null,
                    ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_HEALTHY_DETAIL),
                    ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_HEALTHY_ACTION)));
            return;
        }
        if (inspection.state() == LandProtectionService.ProjectionState.INVALID) {
            boolean controlled;
            try {
                controlled = landProtection.isControlledProjection(failed.town().residenceName());
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
        plugin.runAsync(() -> {
            try {
                ApplicationSnapshot recovered = repository.recoverFailedProvision(
                        failed.applicationId(), administrator.getUniqueId(),
                        administrator.getName(), recoveryReason,
                        mode);
                if (mode == TownRepository.RecoveryMode.UNLOCK_FOR_CHANGES) {
                    plugin.runMain(() -> completion.accept(ProvisionResult.success(recovered)));
                    return;
                }
                plugin.runMain(() -> refundRecoveredApplication(administrator, recovered,
                        completion));
            } catch (RuntimeException exception) {
                plugin.runMain(() -> completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                        ProvisionResult.MessageRef.configured(PROVISION_REFRESH_STATE_ACTION))));
            }
        });
    }

    private void refundRecoveredApplication(Player administrator,
                                            ApplicationSnapshot application,
                                            Consumer<ProvisionResult> completion) {
        VaultSettlementService.Result refund = settlement.transferToPlayer(
                plugin.getServer().getOfflinePlayer(application.applicantId()),
                application.applicationFeeMinor());
        if (!refund.success()) {
            completion.accept(ProvisionResult.failure(application,
                    ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_REFUND_FAILED_DETAIL,
                            Map.of("detail", safeText(refund.message()))),
                    ProvisionResult.MessageRef.configured(PROVISION_RECOVERY_REFUND_ACTION)));
            return;
        }
        plugin.runAsync(() -> {
            try {
                ApplicationSnapshot completed = repository.completeApplicationFeeRefund(
                        application.id(), administrator.getUniqueId(), administrator.getName(),
                        refund.message());
                plugin.runMain(() -> completion.accept(ProvisionResult.success(completed)));
            } catch (RuntimeException exception) {
                plugin.runMain(() -> completion.accept(ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.configured(
                                PROVISION_RECOVERY_REFUND_CONFIRMATION_FAILED_DETAIL,
                                Map.of("detail", safeText(safeMessage(exception)))),
                        ProvisionResult.MessageRef.configured(
                                PROVISION_RECOVERY_REFUND_CONFIRMATION_ACTION))));
            }
        });
    }
}

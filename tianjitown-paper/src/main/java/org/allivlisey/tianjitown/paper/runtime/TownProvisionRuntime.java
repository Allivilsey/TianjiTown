package org.allivlisey.tianjitown.paper.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.ProvisionCoordinator;
import org.allivlisey.tianjitown.paper.land.ProvisionResult;
import org.allivlisey.tianjitown.paper.land.SitePolicy;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Application approval, fee charging and initial Residence projection. */
final class TownProvisionRuntime {
    private static final String PROVISION_APPROVAL_STARTED =
            "log.provision.approval-started";
    private static final String PROVISION_DATABASE_PREPARED =
            "log.provision.database-prepared";
    private static final String PROVISION_PROJECTION_STARTED =
            "log.provision.projection-started";
    private static final String PROVISION_REFUND_FAILURE =
            "log.provision.refund-failure";
    private static final String PROVISION_PROJECTION_EXCEPTION =
            "log.provision.projection-exception";
    private static final String PROVISION_UI_CALLBACK =
            "log.provision.ui-callback";
    private static final String PROVISION_STORAGE_UNAVAILABLE_DETAIL =
            "dialog.provision.storage-unavailable-detail";
    private static final String PROVISION_STORAGE_UNAVAILABLE_ACTION =
            "dialog.provision.storage-unavailable-recovery-action";
    private static final String PROVISION_BUSY_DETAIL = "dialog.provision.busy-detail";
    private static final String PROVISION_APPLICATION_NOT_FOUND_DETAIL =
            "dialog.provision.application-not-found-detail";
    private static final String PROVISION_RESIDENCE_CHECK_ACTION =
            "dialog.provision.residence-check-recovery-action";
    private static final String PROVISION_LIFECYCLE_START_FAILED_DETAIL =
            "dialog.provision.lifecycle-start-failed-detail";
    private static final String PROVISION_LIFECYCLE_RETRY_ACTION =
            "dialog.provision.lifecycle-recovery-action";
    private static final String PROVISION_REFRESH_APPLICATION_ACTION =
            "dialog.provision.refresh-application-action";
    private static final String PROVISION_SITE_VALIDATION_ACTION =
            "dialog.provision.site-validation-recovery-action";
    private static final String PROVISION_RESIDENCE_NAME_CONFLICT_DETAIL =
            "dialog.provision.residence-name-conflict-detail";
    private static final String PROVISION_RESIDENCE_NAME_CONFLICT_ACTION =
            "dialog.provision.residence-name-conflict-recovery-action";
    private static final String PROVISION_FEE_FAILURE_DETAIL =
            "dialog.provision.fee-failed-detail";
    private static final String PROVISION_FEE_FAILURE_ACTION =
            "dialog.provision.fee-failed-recovery-action";
    private static final String PROVISION_DATA_WRITE_ACTION =
            "dialog.provision.data-write-recovery-action";
    private static final String PROVISION_PREPARATION_WRITE_FAILED_DETAIL =
            "dialog.provision.preparation-write-failed-detail";
    private static final String PROVISION_PROJECTION_START_FAILED_DETAIL =
            "dialog.provision.projection-start-failed-detail";
    private static final String PROVISION_PROJECTION_SAVE_FAILED_DETAIL =
            "dialog.provision.projection-save-failed-detail";
    private static final String PROVISION_RESULT_READ_FAILED_DETAIL =
            "dialog.provision.result-read-failed-detail";
    private static final String PROVISION_PROJECTION_RESULT_ACTION =
            "dialog.provision.projection-result-recovery-action";
    private static final String PROVISION_RESIDENCE_RETRY_ACTION =
            "dialog.provision.residence-retry-action";
    private static final String PROVISION_RETRY_APPROVAL_ACTION =
            "dialog.provision.retry-approval-action";
    private static final String PROVISION_REFRESH_STATE_ACTION =
            "dialog.provision.refresh-state-action";
    private final TianjiTownPlugin plugin;
    private final TownRepository repository;
    private final LandProtectionService landProtection;
    private final SitePolicy sitePolicy;
    private final VaultSettlementService settlement;
    private final AtomicBoolean databaseAvailable;
    private final Set<String> activeResidenceNames;
    private final TownRuntimeTasks tasks;
    private final Runnable refreshTaxPolicies;
    private final java.util.function.LongSupplier applicationFeeMinor;
    private final ProvisionCoordinator provisions = new ProvisionCoordinator();

    TownProvisionRuntime(TianjiTownPlugin plugin,
            TownRepository repository,
            LandProtectionService landProtection,
            SitePolicy sitePolicy,
            VaultSettlementService settlement,
            AtomicBoolean databaseAvailable,
            Set<String> activeResidenceNames,
            TownRuntimeTasks tasks,
            Runnable refreshTaxPolicies,
            java.util.function.LongSupplier applicationFeeMinor) {
        this.plugin = plugin;
        this.repository = repository;
        this.landProtection = landProtection;
        this.sitePolicy = sitePolicy;
        this.settlement = settlement;
        this.databaseAvailable = databaseAvailable;
        this.activeResidenceNames = activeResidenceNames;
        this.tasks = tasks;
        this.refreshTaxPolicies = refreshTaxPolicies;
        this.applicationFeeMinor = applicationFeeMinor;
    }

    void provision(CommandSender sender, UUID applicationId, UUID reviewerId,
                   String reviewerName, String reason, String idempotencyKey,
        Consumer<ProvisionResult> callback) {
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<ProvisionResult> completion = result -> {
            if (!delivered.compareAndSet(false, true)) return;
            try {
                callback.accept(result);
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Provision result callback failed: application=" + applicationId
                                + " operation=" + idempotencyKey, exception);
            }
        };
        plugin.getLogger().info("Provision entry: application=" + applicationId
                + " actor=" + reviewerId + " operation=" + idempotencyKey);
        plugin.getLogger().info(plugin.messages().plainText(PROVISION_APPROVAL_STARTED,
                Map.of("application", applicationId, "time", Instant.now())));
        if (!databaseAvailable.get()) {
            plugin.messages().send(sender, "chat.runtime.storage-locked");
            completion.accept(ProvisionResult.failure(null,
                    ProvisionResult.MessageRef.configured(PROVISION_STORAGE_UNAVAILABLE_DETAIL),
                    ProvisionResult.MessageRef.configured(PROVISION_STORAGE_UNAVAILABLE_ACTION)));
            return;
        }
        if (!provisions.tryBegin(applicationId)) {
            plugin.messages().send(sender, "chat.runtime.provision-duplicate");
            completion.accept(ProvisionResult.busy(
                    ProvisionResult.MessageRef.configured(PROVISION_BUSY_DETAIL)));
            return;
        }
        if (!plugin.runAsync(() -> {
            try {
                ApplicationSnapshot application = repository.findApplication(applicationId)
                        .orElseThrow(TownRuntimeTasks.ApplicationNotFoundException::new);
                TownSnapshot existingTown = application.townId() == null ? null
                        : repository.findTown(application.townId()).orElseThrow();
                plugin.getLogger().info("Provision state: application=" + applicationId
                        + " status=" + application.status() + " version=" + application.version()
                        + " town=" + application.townId() + " territorySource="
                        + (existingTown == null ? "reservation" : "territory_units"));
                long feeMinor = application.applicationFeeMinor() > 0
                        ? application.applicationFeeMinor()
                        : applicationFeeMinor.getAsLong();
                Runnable start = () -> {
                    try {
                        chargeAndBeginProvision(sender, application, existingTown, reviewerId, reviewerName,
                                reason, idempotencyKey, feeMinor, completion);
                    } catch (RuntimeException | LinkageError exception) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "Provision preflight failed: application=" + application.id()
                                        + " operation=" + idempotencyKey, exception);
                        provisions.finish(application.id());
                        tasks.handleFailure(sender, exception);
                        completion.accept(ProvisionResult.failure(application,
                                ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                                ProvisionResult.MessageRef.configured(
                                        PROVISION_RESIDENCE_CHECK_ACTION)));
                    }
                };
                if (!plugin.runMain(start)) {
                    provisions.finish(application.id());
                    completion.accept(ProvisionResult.failure(application,
                            ProvisionResult.MessageRef.configured(
                                    PROVISION_LIFECYCLE_START_FAILED_DETAIL),
                            ProvisionResult.MessageRef.configured(PROVISION_LIFECYCLE_RETRY_ACTION)));
                }
            } catch (RuntimeException exception) {
                provisions.finish(applicationId);
                tasks.handleFailure(sender, exception);
                ProvisionResult result = exception instanceof TownRuntimeTasks.ApplicationNotFoundException
                        ? ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.configured(PROVISION_APPLICATION_NOT_FOUND_DETAIL),
                        ProvisionResult.MessageRef.configured(PROVISION_REFRESH_APPLICATION_ACTION))
                        : ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                        ProvisionResult.MessageRef.configured(PROVISION_REFRESH_APPLICATION_ACTION));
                if (!plugin.runMain(() -> completion.accept(result))) {
                    completion.accept(result);
                }
            }
        })) {
            provisions.finish(applicationId);
            completion.accept(ProvisionResult.failure(null,
                    ProvisionResult.MessageRef.configured(
                            PROVISION_LIFECYCLE_START_FAILED_DETAIL),
                    ProvisionResult.MessageRef.configured(PROVISION_LIFECYCLE_RETRY_ACTION)));
        }
    }

    private void chargeAndBeginProvision(CommandSender sender, ApplicationSnapshot application,
                                         TownSnapshot existingTown,
                                         UUID reviewerId, String reviewerName, String reason,
                                         String idempotencyKey, long feeMinor,
                                         Consumer<ProvisionResult> completion) {
        if (existingTown != null && existingTown.status() == TownStatus.ARCHIVED) {
            throw new IllegalStateException("小镇已归档，不能继续或重复批准");
        }
        if (application.status() == org.allivlisey.tianjitown.core.application.ApplicationStatus.ACTIVE) {
            if (existingTown == null || existingTown.status() != TownStatus.ACTIVE) {
                throw new IllegalStateException("申请与小镇状态不一致");
            }
            provisions.finish(application.id());
            completion.accept(ProvisionResult.existing(application));
            return;
        }
        if (application.status() == org.allivlisey.tianjitown.core.application.ApplicationStatus.APPROVED_PROVISIONING) {
            provisions.finish(application.id());
            completion.accept(ProvisionResult.busy(
                    ProvisionResult.MessageRef.configured(PROVISION_BUSY_DETAIL)));
            return;
        }
        org.allivlisey.tianjitown.core.application.ApplicationWorkflow.requireAllowed(
                application.status(),
                org.allivlisey.tianjitown.core.application.ApplicationStatus.APPROVED_PROVISIONING,
                org.allivlisey.tianjitown.core.application.ApplicationActor.ADMINISTRATOR);
        var territory = existingTown == null ? application.territory() : existingTown.territory();
        if (existingTown == null && (application.reservationExpiresAt() == null
                || !application.reservationExpiresAt().isAfter(Instant.now()))) {
            throw new IllegalStateException("选址预留已经过期，不能批准");
        }
        List<UUID> expectedMembers = new ArrayList<>();
        expectedMembers.add(application.applicantId());
        application.initialMembers().forEach(member -> expectedMembers.add(member.playerId()));
        SitePolicy.Validation environment = sitePolicy.validateReservationEnvironment(territory);
        if (!environment.valid()) {
            provisions.finish(application.id());
            completion.accept(ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.configured("dialog.provision.site-validation-failed-detail",
                            Map.of("detail", safeText(environment.error()))),
                    ProvisionResult.MessageRef.configured(PROVISION_SITE_VALIDATION_ACTION)));
            return;
        }
        LandProtectionService.Collision nameCollision = landProtection.findNameCollision(
                application.text().normalizedResidenceName());
        if (nameCollision.code() != null) {
            provisions.finish(application.id());
            completion.accept(ProvisionResult.failure(application,
                    ProvisionResult.MessageRef.literal(safeText(
                            LandProtectionMessages.detail(plugin.messages(), nameCollision))),
                    ProvisionResult.MessageRef.configured(PROVISION_RESIDENCE_CHECK_ACTION)));
            return;
        }
        if (nameCollision.occupied()) {
            LandProtectionService.Inspection inspection = application.townId() == null
                    ? LandProtectionService.Inspection.invalidCode(LandProtectionService.ResultCode.PROJECTION_MISSING)
                    : landProtection.inspect(application.text().normalizedResidenceName(),
                    territory, expectedMembers);
            if (inspection.state() != LandProtectionService.ProjectionState.HEALTHY) {
                provisions.finish(application.id());
                completion.accept(ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.configured(
                                PROVISION_RESIDENCE_NAME_CONFLICT_DETAIL),
                        ProvisionResult.MessageRef.configured(
                                PROVISION_RESIDENCE_NAME_CONFLICT_ACTION)));
                return;
            }
        }
        boolean needsCharge = application.applicationFeeMinor() == 0;
        if (needsCharge) {
            VaultSettlementService.Result payment = settlement.transferFromPlayer(
                    plugin.getServer().getOfflinePlayer(application.applicantId()), feeMinor);
            if (!payment.success()) {
                provisions.finish(application.id());
                String paymentDetail = safeText(payment.message());
                plugin.messages().send(sender, "chat.runtime.fee-failed", Map.of(
                        "amount", settlement.formatMinor(feeMinor), "detail", paymentDetail));
                completion.accept(ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.configured(PROVISION_FEE_FAILURE_DETAIL,
                                Map.of("detail", paymentDetail)),
                        ProvisionResult.MessageRef.configured(PROVISION_FEE_FAILURE_ACTION)));
                return;
            }
        }
        if (!plugin.runAsync(() -> {
            TownRepository.Provisioning provisioning;
            try {
                provisioning = repository.beginProvision(
                        application.id(), reviewerId, reviewerName, reason, idempotencyKey,
                        feeMinor, playerName(application.applicantId()));
            } catch (RuntimeException exception) {
                Runnable failed = () -> {
                    if (needsCharge) {
                        VaultSettlementService.Result refund = settlement.transferToPlayer(
                                plugin.getServer().getOfflinePlayer(application.applicantId()),
                                feeMinor);
                        if (!refund.success()) {
                            plugin.getLogger().severe(plugin.messages().plainText(
                                    PROVISION_REFUND_FAILURE,
                                    Map.of("detail", safeText(refund.message()))));
                        }
                    }
                    provisions.finish(application.id());
                    tasks.handleFailure(sender, exception);
                    completion.accept(ProvisionResult.failure(application,
                            ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                            ProvisionResult.MessageRef.configured(PROVISION_DATA_WRITE_ACTION)));
                };
                if (!plugin.runMain(failed)) {
                    failed.run();
                }
                return;
            }
            // Preparation committed the fee. Later projection/notification failures must not refund it.
            databaseAvailable.set(true);
            try {
                plugin.getLogger().info(plugin.messages().plainText(PROVISION_DATABASE_PREPARED,
                        Map.of("application", application.id(), "time", Instant.now())));
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Provision preparation notification failed: application=" + application.id(), exception);
            }
            if (!plugin.runMain(() -> projectProvision(sender, application.id(), provisioning,
                    completion))) {
                provisions.finish(application.id());
                completion.accept(ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.configured(PROVISION_PROJECTION_START_FAILED_DETAIL),
                        ProvisionResult.MessageRef.configured(PROVISION_LIFECYCLE_RETRY_ACTION)));
            }
        })) {
            if (needsCharge) {
                VaultSettlementService.Result refund = settlement.transferToPlayer(
                        plugin.getServer().getOfflinePlayer(application.applicantId()), feeMinor);
                if (!refund.success()) plugin.getLogger().severe(plugin.messages().plainText(
                        PROVISION_REFUND_FAILURE, Map.of("detail", safeText(refund.message()))));
            }
            provisions.finish(application.id());
            completion.accept(ProvisionResult.failure(application,
                ProvisionResult.MessageRef.configured(
                        PROVISION_PREPARATION_WRITE_FAILED_DETAIL),
                ProvisionResult.MessageRef.configured(PROVISION_LIFECYCLE_RETRY_ACTION)));
        }
    }

    private void projectProvision(CommandSender sender, UUID applicationId,
                                  TownRepository.Provisioning provisioning,
                                  Consumer<ProvisionResult> completion) {
        if (provisioning.town().status() == TownStatus.ACTIVE) {
            provisions.finish(applicationId);
            plugin.messages().send(sender, "chat.runtime.provision-already-complete");
            if (!plugin.runAsync(() -> {
                ApplicationSnapshot current = repository.findApplication(applicationId)
                        .orElse(null);
                ProvisionResult result = ProvisionResult.existing(current);
                if (!plugin.runMain(() -> completion.accept(result))) {
                    completion.accept(result);
                }
            })) {
                completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.configured(
                                PROVISION_RESULT_READ_FAILED_DETAIL),
                        ProvisionResult.MessageRef.configured(
                                PROVISION_PROJECTION_RESULT_ACTION)));
            }
            return;
        }
        try {
            plugin.getLogger().info(plugin.messages().plainText(PROVISION_PROJECTION_STARTED,
                    Map.of("application", applicationId, "time", Instant.now())));
            // 碰撞由创建服务检查，使重试能够识别并复用本镇已经创建的系统投影。
            SitePolicy.Validation validation = sitePolicy.validateReservationEnvironment(
                    provisioning.town().territory());
            LandProtectionService.Result land = validation.valid()
                    ? landProtection.create(provisioning.town().residenceName(),
                    provisioning.town().territory(),
                    provisioning.members())
                    : LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_SITE_VALIDATION_DETAIL,
                            Map.of("detail", safeText(validation.error())));
            if (land.success()) {
                land = org.allivlisey.tianjitown.paper.message.TownResidenceMessages.sync(
                        plugin.messages(), landProtection, provisioning.town());
            }
            LandProtectionService.Result completedLand = land.success()
                    ? setDefaultTeleportPoint(provisioning.town(), land) : land;
            if (!plugin.runAsync(() -> finishProvision(sender, applicationId, completedLand,
                    completion))) {
                provisions.finish(applicationId);
                completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.configured(
                                PROVISION_PROJECTION_SAVE_FAILED_DETAIL),
                        ProvisionResult.MessageRef.configured(
                                PROVISION_PROJECTION_RESULT_ACTION)));
            }
        } catch (RuntimeException | LinkageError exception) {
            // create/set-default-tp 可能在主线程直接抛出；仍要把申请落到
            // PROVISION_FAILED，再回调 UI，避免审核页一直停留在 APPROVED_PROVISIONING。
            String detail = safeText(safeMessage(exception));
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    plugin.messages().plainText(PROVISION_PROJECTION_EXCEPTION,
                    Map.of("application", applicationId, "detail", detail)), exception);
            LandProtectionService.Result failed = LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_OPERATION_FAILED, Map.of("detail", detail));
            if (!plugin.runAsync(() -> finishProvision(sender, applicationId, failed, completion))) {
                provisions.finish(applicationId);
                completion.accept(ProvisionResult.failure(null,
                        ProvisionResult.MessageRef.literal(detail),
                        ProvisionResult.MessageRef.configured(PROVISION_RESIDENCE_RETRY_ACTION)));
            }
        }
    }

    private LandProtectionService.Result setDefaultTeleportPoint(TownSnapshot town,
                                                                  LandProtectionService.Result land) {
        org.allivlisey.tianjitown.core.land.ChunkPosition center = town.territory().center();
        org.bukkit.World world = plugin.getServer().getWorld(center.worldId());
        if (world == null) {
            world = plugin.getServer().getWorld(center.worldName());
        }
        if (world == null) {
            return LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_DEFAULT_TELEPORT_WORLD_DETAIL);
        }
        int blockX = Math.addExact(Math.multiplyExact(center.x(), 16), 8);
        int blockZ = Math.addExact(Math.multiplyExact(center.z(), 16), 8);
        int blockY = world.getHighestBlockYAt(blockX, blockZ) + 1;
        if (blockY <= world.getMinHeight() || blockY + 1 >= world.getMaxHeight()) {
            return LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_DEFAULT_TELEPORT_HEIGHT_DETAIL);
        }
        Location location = new Location(world, blockX + 0.5D, blockY, blockZ + 0.5D);
        if (!location.getBlock().isPassable()
                || !location.getBlock().getRelative(0, 1, 0).isPassable()
                || !location.getBlock().getRelative(0, -1, 0).getType().isSolid()) {
            return LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_DEFAULT_TELEPORT_SPACE_DETAIL);
        }
        LandProtectionService.Result teleport = landProtection.setTeleportPoint(
                town.residenceName(), world.getUID(), world.getName(), location.getX(),
                location.getY(), location.getZ(), 0.0F, 0.0F);
        if (!teleport.success()) {
            LandProtectionService.Result cleanup = landProtection.remove(town.residenceName(),
                    town.territory());
            String teleportDetail = safeText(LandProtectionMessages.detail(plugin.messages(), teleport));
            if (cleanup.success()) {
                return LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_DEFAULT_TELEPORT_ROLLED_BACK_DETAIL,
                        Map.of("detail", teleportDetail));
            }
            return LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_DEFAULT_TELEPORT_ROLLBACK_FAILED_DETAIL,
                    Map.of("detail", teleportDetail,
                            "cleanup", safeText(LandProtectionMessages.detail(
                                    plugin.messages(), cleanup))));
        }
        return LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROVISION_LAND_WITH_TELEPORT_DETAIL,
                Map.of("detail", safeText(LandProtectionMessages.detail(plugin.messages(), land))));
    }

    private void finishProvision(CommandSender sender, UUID applicationId,
                                 LandProtectionService.Result land,
                                 Consumer<ProvisionResult> completion) {
        boolean completed = land.success();
        String detail = safeText(LandProtectionMessages.detail(plugin.messages(), land));
        ApplicationSnapshot application;
        try {
            application = repository.finishProvision(applicationId, completed, detail);
            databaseAvailable.set(true);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Provision commit failed: application=" + applicationId, exception);
            tasks.handleFailure(sender, exception);
            provisions.finish(applicationId);
            var failure = ProvisionResult.failure(null,
                    ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                    ProvisionResult.MessageRef.configured(PROVISION_REFRESH_STATE_ACTION));
            if (!plugin.runMain(() -> completion.accept(failure))) completion.accept(failure);
            return;
        }
        // The database outcome is committed. Notification/cache errors cannot turn it into failure.
        if (completed) {
            try {
                repository.findTown(application.townId()).map(TownSnapshot::residenceName)
                        .ifPresent(activeResidenceNames::add);
                refreshTaxPolicies.run();
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Provision post-commit synchronization failed: application=" + applicationId, exception);
            }
        }
        ProvisionResult result = completed ? ProvisionResult.success(application)
                : ProvisionResult.failure(application, ProvisionResult.MessageRef.literal(detail),
                        ProvisionResult.MessageRef.configured(PROVISION_RETRY_APPROVAL_ACTION));
        Runnable callback = () -> {
            try {
                plugin.messages().send(sender, completed ? "chat.runtime.provision-success"
                        : "chat.runtime.provision-failed", completed ? Map.of() : Map.of("detail", detail));
                plugin.getLogger().info(plugin.messages().plainText(PROVISION_UI_CALLBACK,
                        Map.of("application", applicationId, "time", Instant.now(), "success", completed)));
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Provision notification failed: application=" + applicationId, exception);
            } finally {
                completion.accept(result);
                provisions.finish(applicationId);
            }
        };
        if (!plugin.runMain(callback)) {
            completion.accept(result);
            provisions.finish(applicationId);
        }
    }

    private String playerName(UUID playerId) {
        String name = plugin.getServer().getOfflinePlayer(playerId).getName();
        return name == null || name.isBlank() ? playerId.toString() : name;
    }
}

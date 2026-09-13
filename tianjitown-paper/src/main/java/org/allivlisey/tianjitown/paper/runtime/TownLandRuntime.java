package org.allivlisey.tianjitown.paper.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.AutomaticLandReconciler;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.actorId;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Residence inspection, automatic repair, teleport points and audit. */
final class TownLandRuntime {
    private static final String RESIDENCE_RECONCILIATION_FAILURE =
            "log.residence.reconciliation-failure";
    private static final String RESIDENCE_RECONCILIATION_DIFFERENCE =
            "log.residence.reconciliation-difference";
    private static final String RESIDENCE_RECONCILIATION_SQLITE_FAILURE =
            "log.residence.reconciliation-sqlite-read-failure";
    private static final String RESIDENCE_AUTOMATIC_REPAIR_CANCELLED =
            "log.residence.automatic-repair-cancelled";
    private static final String RESIDENCE_AUTOMATIC_REPAIR_DELAYED =
            "log.residence.automatic-repair-delayed";
    private static final String RESIDENCE_AUTOMATIC_REPAIR_SQLITE_FAILURE =
            "log.residence.automatic-repair-sqlite-read-failure";
    private static final String RESIDENCE_AUTOMATIC_REPAIR_API_FAILURE =
            "log.residence.automatic-repair-api-failure";
    private static final String RESIDENCE_AUTOMATIC_REPAIR_CONSISTENT =
            "log.residence.automatic-repair-consistent";
    private static final String RESIDENCE_AUTOMATIC_REPAIR_COMPLETED =
            "log.residence.automatic-repair-completed";
    private static final String RESIDENCE_AUTOMATIC_REPAIR_FAILED =
            "log.residence.automatic-repair-failed";
    private static final String RESIDENCE_TELEPORT_AUDIT_SUCCESS =
            "log.residence.teleport-point-audit-success";
    private static final String RESIDENCE_TELEPORT_AUDIT_FAILURE =
            "log.residence.teleport-point-audit-failure";
    private static final String RESIDENCE_TELEPORT_AUDIT_WRITE_FAILURE =
            "log.residence.teleport-point-audit-write-failure";
    private static final String RESIDENCE_RECONCILIATION_AUDIT_SUCCESS =
            "log.residence.reconciliation-audit-success";
    private static final String RESIDENCE_RECONCILIATION_AUDIT_FAILURE =
            "log.residence.reconciliation-audit-failure";
    private static final String RESIDENCE_RECONCILIATION_AUDIT_WRITE_FAILURE =
            "log.residence.reconciliation-audit-write-failure";
    private final TianjiTownPlugin plugin;
    private final TownRepository repository;
    private final EconomyRepository finance;
    private final LandProtectionService landProtection;
    private final AtomicBoolean databaseAvailable;
    private final TownRuntimeTasks tasks;
    private final Runnable refreshTaxPolicies;
    private final Set<UUID> pendingLandRepairs = ConcurrentHashMap.newKeySet();

    TownLandRuntime(TianjiTownPlugin plugin,
            TownRepository repository,
            EconomyRepository finance,
            LandProtectionService landProtection,
            AtomicBoolean databaseAvailable,
            TownRuntimeTasks tasks,
            Runnable refreshTaxPolicies) {
        this.plugin = plugin;
        this.repository = repository;
        this.finance = finance;
        this.landProtection = landProtection;
        this.databaseAvailable = databaseAvailable;
        this.tasks = tasks;
        this.refreshTaxPolicies = refreshTaxPolicies;
    }

    void reconcileAll() {
        plugin.runAsync(() -> {
            try {
                List<TownLandState> states = repository.listTowns(false).stream()
                        .filter(town -> town.status() == TownStatus.ACTIVE)
                        .map(town -> new TownLandState(town, repository.listLandAccessIds(town.id()),
                                finance.territoryUnits(town.id())))
                        .toList();
                plugin.runMain(() -> {
                    for (TownLandState state : states) {
                        if (hasUnsettledProjection(state)) {
                            continue;
                        }
                        List<LandProtectionService.Area> areas = state.units().stream()
                                .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                                .map(unit -> new LandProtectionService.Area(unit.residenceAreaName(),
                                        unit.unit().territory())).toList();
                        LandProtectionService.Inspection inspection;
                        try {
                            inspection = landProtection.inspect(state.town().residenceName(), areas,
                                    state.members());
                        } catch (RuntimeException | LinkageError exception) {
                            LandProtectionService.Result failure =
                                    LandProtectionService.Result.failureCode(
                                            LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                                            Map.of("detail", safeText(safeMessage(exception))));
                            plugin.getLogger().severe(plugin.messages().plainText(
                                    RESIDENCE_RECONCILIATION_FAILURE, Map.of(
                                            "town", state.town().id(),
                                            "detail", safeText(LandProtectionMessages.detail(
                                                    plugin.messages(), failure)))));
                            recordLandAudit(null, "SYSTEM", state.town().id(), false, failure);
                            continue;
                        }
                        if (inspection.state()
                                == LandProtectionService.ProjectionState.HEALTHY) {
                            var messages = org.allivlisey.tianjitown.paper.message.TownResidenceMessages.sync(
                                    plugin.messages(), landProtection, state.town());
                            recordLandAudit(null, "SYSTEM", state.town().id(), false, messages);
                            continue;
                        }
                        String detectedDifference = safeText(LandProtectionMessages.detail(
                                plugin.messages(), inspection));
                        plugin.getLogger().warning(plugin.messages().plainText(
                                RESIDENCE_RECONCILIATION_DIFFERENCE, Map.of(
                                        "town", state.town().id(),
                                        "detail", detectedDifference)));
                        repairLandFromDatabase(state.town().id(), detectedDifference);
                    }
                });
            } catch (RuntimeException exception) {
                databaseAvailable.set(false);
                plugin.getLogger().severe(plugin.messages().plainText(
                        RESIDENCE_RECONCILIATION_SQLITE_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    private void repairLandFromDatabase(UUID townId, String detectedDifference) {
        if (!pendingLandRepairs.add(townId)) {
            return;
        }
        boolean submitted = plugin.runAsync(() -> {
            try {
                TownSnapshot town = repository.findTown(townId).orElse(null);
                if (town == null || town.status() != TownStatus.ACTIVE) {
                    pendingLandRepairs.remove(townId);
                    plugin.getLogger().warning(plugin.messages().plainText(
                            RESIDENCE_AUTOMATIC_REPAIR_CANCELLED,
                            Map.of("town", townId)));
                    return;
                }
                TownLandState latest = new TownLandState(town, repository.listLandAccessIds(townId),
                        finance.territoryUnits(townId));
                if (hasUnsettledProjection(latest)) {
                    pendingLandRepairs.remove(townId);
                    plugin.getLogger().info(plugin.messages().plainText(
                            RESIDENCE_AUTOMATIC_REPAIR_DELAYED,
                            Map.of("town", townId)));
                    return;
                }
                databaseAvailable.set(true);
                if (!plugin.runMain(() -> applyAutomaticLandRepair(latest,
                        detectedDifference))) {
                    pendingLandRepairs.remove(townId);
                }
            } catch (RuntimeException exception) {
                pendingLandRepairs.remove(townId);
                if (exception instanceof TownRepository.StorageUnavailableException
                        || exception instanceof EconomyRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe(plugin.messages().plainText(
                        RESIDENCE_AUTOMATIC_REPAIR_SQLITE_FAILURE, Map.of(
                                "town", townId,
                                "detail", safeText(safeMessage(exception)))));
            }
        });
        if (!submitted) {
            pendingLandRepairs.remove(townId);
        }
    }

    private static boolean hasUnsettledProjection(TownLandState state) {
        return state.units().stream()
                .anyMatch(unit -> !unit.projectionStatus().equals("ACTIVE"));
    }

    private void applyAutomaticLandRepair(TownLandState state, String detectedDifference) {
        try {
            List<LandProtectionService.Area> areas = state.units().stream()
                    .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                    .map(unit -> new LandProtectionService.Area(unit.residenceAreaName(),
                            unit.unit().territory())).toList();
            AutomaticLandReconciler.Outcome outcome;
            try {
                outcome = AutomaticLandReconciler.reconcile(landProtection,
                        state.town().residenceName(), areas, state.members());
            } catch (RuntimeException | LinkageError exception) {
                LandProtectionService.Result failure = LandProtectionService.Result.failureCode(
                        LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                        Map.of("detail", safeText(safeMessage(exception))));
                plugin.getLogger().severe(plugin.messages().plainText(
                        RESIDENCE_AUTOMATIC_REPAIR_API_FAILURE, Map.of(
                                "town", state.town().id(),
                                "detail", safeText(LandProtectionMessages.detail(
                                        plugin.messages(), failure)))));
                recordLandAudit(null, "SYSTEM", state.town().id(), true, failure);
                return;
            }
            if (!outcome.repairAttempted()) {
                plugin.getLogger().info(plugin.messages().plainText(
                        RESIDENCE_AUTOMATIC_REPAIR_CONSISTENT, Map.of(
                                "town", state.town().id(),
                                "detail", safeText(detectedDifference))));
            } else if (outcome.result().success()) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        RESIDENCE_AUTOMATIC_REPAIR_COMPLETED, Map.of(
                                "town", state.town().id(),
                                "detail", safeText(LandProtectionMessages.detail(
                                        plugin.messages(), outcome.inspection())))));
            } else {
                plugin.getLogger().severe(plugin.messages().plainText(
                        RESIDENCE_AUTOMATIC_REPAIR_FAILED, Map.of(
                                "town", state.town().id(),
                                "inspection", safeText(LandProtectionMessages.detail(
                                        plugin.messages(), outcome.inspection())),
                                "repair", safeText(LandProtectionMessages.detail(
                                        plugin.messages(), outcome.result())))));
            }
            var result = outcome.result().success()
                    ? org.allivlisey.tianjitown.paper.message.TownResidenceMessages.sync(
                            plugin.messages(), landProtection, state.town()) : outcome.result();
            recordLandAudit(null, "SYSTEM", state.town().id(), outcome.repairAttempted(), result);
        } finally {
            pendingLandRepairs.remove(state.town().id());
        }
    }

    void setTownTeleportPoint(Player actor, TownSnapshot town, Location location,
                              Consumer<LandProtectionService.Result> completion) {
        LandProtectionService.Result result;
        try {
            result = landProtection.setTeleportPoint(town.residenceName(),
                    location.getWorld().getUID(), location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
        } catch (RuntimeException | LinkageError exception) {
            result = LandProtectionService.Result.failureCode(
                    LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", safeText(safeMessage(exception))));
        }
        LandProtectionService.Result completed = result;
        String completedDetail = safeText(LandProtectionMessages.detail(plugin.messages(), completed));
        String auditReason = plugin.messages().plainText(completed.success()
                ? RESIDENCE_TELEPORT_AUDIT_SUCCESS : RESIDENCE_TELEPORT_AUDIT_FAILURE);
        plugin.runAsync(() -> {
            try {
                repository.recordAudit(actor.getUniqueId(), actor.getName(),
                        "TOWN_TELEPORT_POINT_SET", "TOWN", town.id().toString(),
                        auditReason,
                        location.getWorld().getName() + " " + location.getX() + ","
                                + location.getY() + "," + location.getZ() + " · "
                                + completedDetail);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        RESIDENCE_TELEPORT_AUDIT_WRITE_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
        completion.accept(completed);
    }

    void reconcile(CommandSender sender, TownSnapshot town, List<UUID> members, boolean repair) {
        reconcileAction(sender, town, members, repair, result -> plugin.messages().send(sender,
                        "chat.runtime.reconcile", Map.of(
                                "color", result.success() ? "§a" : "§c",
                                "town", town.profile().name(), "detail",
                                LandProtectionMessages.detail(plugin.messages(), result))),
                exception -> tasks.handleFailure(sender, exception));
    }

    void reconcileAction(CommandSender sender, TownSnapshot town, List<UUID> members,
                         boolean repair, Consumer<LandProtectionService.Result> success,
                         Consumer<RuntimeException> failure) {
        plugin.runAsync(() -> {
            try {
                List<LandProtectionService.Area> areas = finance.territoryUnits(town.id()).stream()
                        .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                        .map(unit -> new LandProtectionService.Area(unit.residenceAreaName(),
                                unit.unit().territory())).toList();
                refreshTaxPolicies.run();
                plugin.runMain(() -> {
                    LandProtectionService.Result result;
                    try {
                        result = landProtection.reconcile(town.residenceName(), areas, members,
                                repair);
                        if (repair && result.success()) {
                            result = org.allivlisey.tianjitown.paper.message.TownResidenceMessages.sync(
                                    plugin.messages(), landProtection, town);
                        }
                    } catch (RuntimeException | LinkageError exception) {
                        result = LandProtectionService.Result.failureCode(
                                LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                                Map.of("detail", safeText(safeMessage(exception))));
                    }
                    recordLandAudit(actorId(sender), sender.getName(), town.id(), repair, result);
                    success.accept(result);
                });
            } catch (RuntimeException exception) {
                tasks.reportActionFailure(exception, failure);
            }
        });
    }

    private void recordLandAudit(UUID actorId, String actorName, UUID townId, boolean repair,
                                  LandProtectionService.Result result) {
        String resultDetail = safeText(LandProtectionMessages.detail(plugin.messages(), result));
        String auditReason = plugin.messages().plainText(result.success()
                ? RESIDENCE_RECONCILIATION_AUDIT_SUCCESS
                : RESIDENCE_RECONCILIATION_AUDIT_FAILURE);
        plugin.runAsync(() -> {
            try {
                repository.recordAudit(actorId, actorName,
                        repair ? "LAND_RECONCILE_REPAIR" : "LAND_RECONCILE_CHECK", "TOWN",
                        townId.toString(), auditReason,
                        resultDetail);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        RESIDENCE_RECONCILIATION_AUDIT_WRITE_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }
}

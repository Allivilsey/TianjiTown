package org.allivlisey.tianjitown.paper.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.allivlisey.tianjitown.core.land.ExpansionDirection;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.SitePolicy;
import org.allivlisey.tianjitown.paper.land.TerritoryService;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Expansion previews, Residence projection and rollback recovery. */
final class TownExpansionRuntime {
    private static final String CONSUMPTION_PAUSED = "chat.runtime.consumption-paused";
    private static final String EXPANSION_VALIDATION_FAILED =
            "chat.lifecycle.expansion-validation-failed";
    private static final String EXPANSION_BATCH_REQUEST_ID_REQUIRED =
            "validation.territory.batch-request-id-required";
    private static final String EXPANSION_BATCH_ALREADY_REFUNDED =
            "validation.territory.batch-already-refunded";
    private static final String EXPANSION_FAILED_REFUNDED =
            "chat.lifecycle.expansion-failed-refunded";
    private static final String EXPANSION_AREA_PRESENCE_CHECK_FAILED =
            "chat.lifecycle.expansion-area-presence-check-failed";
    private static final String EXPANSION_BATCH_FAILED =
            "chat.lifecycle.expansion-batch-failed";
    private static final String EXPANSION_BATCH_ROLLBACK_FAILED =
            "log.expansion.batch-rollback-failed";
    private static final String EXPANSION_BATCH_ROLLBACK_PARTIAL =
            "chat.lifecycle.expansion-batch-rollback-partial";
    private static final String EXPANSION_RECOVERED = "log.expansion.recovered";
    private static final String EXPANSION_RECOVERY_FAILED = "log.expansion.recovery-failed";
    private static final String EXPANSION_BATCH_RECOVERED = "log.expansion.batch-recovered";
    private static final String EXPANSION_BATCH_RECOVERY_FAILED =
            "log.expansion.batch-recovery-failed";
    private final TianjiTownPlugin plugin;
    private final TownRepository repository;
    private final EconomyRepository finance;
    private final LandProtectionService landProtection;
    private final TerritoryService territories;
    private final TownRuntimeTasks tasks;
    private final java.util.function.BooleanSupplier consumptionEnabled;

    TownExpansionRuntime(TianjiTownPlugin plugin,
            TownRepository repository,
            EconomyRepository finance,
            LandProtectionService landProtection,
            TerritoryService territories,
            TownRuntimeTasks tasks,
            java.util.function.BooleanSupplier consumptionEnabled) {
        this.plugin = plugin;
        this.repository = repository;
        this.finance = finance;
        this.landProtection = landProtection;
        this.territories = territories;
        this.tasks = tasks;
        this.consumptionEnabled = consumptionEnabled;
    }

    TerritoryService.ExpansionPreview expansionPreview(UUID playerId,
                                                        ExpansionDirection direction) {
        return territories.preview(playerId, direction);
    }

    TerritoryService.ExpansionPreview expansionPreview(UUID playerId, int gridX, int gridZ) {
        return territories.preview(playerId, gridX, gridZ);
    }

    TerritoryService.ExpansionBatchPreview expansionBatchPreview(UUID playerId,
                                                                  Set<TerritoryService.GridSelection> selections) {
        return territories.batchPreview(playerId, selections);
    }

    void loadTerritoryMap(Player player, Consumer<TerritoryService.TerritoryMap> success) {
        tasks.read(player, () -> territories.map(player.getUniqueId()),
                map -> success.accept(territories.validate(map)));
    }

    SitePolicy.Validation validateExpansionPreview(
            TerritoryService.ExpansionPreview preview) {
        return territories.validate(preview);
    }

    void expandAction(Player mayor, ExpansionDirection direction,
                      Consumer<EconomyRepository.ExpansionOperation> success,
                      Consumer<RuntimeException> failure) {
        expandAction(mayor, () -> expansionPreview(mayor.getUniqueId(), direction),
                success, failure);
    }

    void expandAction(Player mayor, int gridX, int gridZ,
                      Consumer<EconomyRepository.ExpansionOperation> success,
                      Consumer<RuntimeException> failure) {
        expandAction(mayor, () -> expansionPreview(mayor.getUniqueId(), gridX, gridZ),
                success, failure);
    }

    void expandBatchAction(Player mayor, Set<TerritoryService.GridSelection> selections,
                           Consumer<EconomyRepository.ExpansionBatchOperation> success,
                           Consumer<RuntimeException> failure) {
        expandBatchAction(mayor, selections, UUID.randomUUID().toString(), success, failure);
    }

    void expandBatchAction(Player mayor, Set<TerritoryService.GridSelection> selections,
                           String requestId,
                           Consumer<EconomyRepository.ExpansionBatchOperation> success,
                           Consumer<RuntimeException> failure) {
        if (!consumptionEnabled.getAsBoolean()) {
            failure.accept(new IllegalStateException(
                    plugin.messages().plainText(CONSUMPTION_PAUSED)));
            return;
        }
        tasks.readAction(mayor, () -> territories.batchPreview(mayor.getUniqueId(), selections),
                preview -> {
                    for (TerritoryService.ExpansionPreview candidate : preview.candidates()) {
                        SitePolicy.Validation validation = territories.validate(candidate);
                        if (!validation.valid()) {
                            failure.accept(new IllegalArgumentException(
                                    plugin.messages().plainText(EXPANSION_VALIDATION_FAILED,
                                            Map.of("detail", safeText(validation.error())))));
                            return;
                        }
                    }
                    if (requestId == null || requestId.isBlank()) {
                        failure.accept(new IllegalArgumentException(
                                plugin.messages().plainText(EXPANSION_BATCH_REQUEST_ID_REQUIRED)));
                        return;
                    }
                    String key = "expansion-batch:" + preview.account().townId() + ":"
                            + requestId;
                    List<EconomyRepository.ExpansionBatchItem> items = preview.candidates().stream()
                            .map(candidate -> new EconomyRepository.ExpansionBatchItem(
                                    candidate.candidate(), candidate.residenceName(),
                                    candidate.areaName(), candidate.priceMinor()))
                            .toList();
                    plugin.runAsync(() -> {
                        try {
                            EconomyRepository.ExpansionBatchOperation batch =
                                    finance.prepareExpansionBatch(
                                            new EconomyRepository.ExpansionBatchRequest(
                                                    preview.account().townId(), items,
                                                    preview.totalPriceMinor(), mayor.getUniqueId(),
                                                    mayor.getName(), key));
                            if (batch.status().equals("COMPLETED")) {
                                plugin.runMain(() -> success.accept(batch));
                                return;
                            }
                            if (batch.status().equals("REFUNDED")) {
                                throw new EconomyRepository.ConflictException(
                                        plugin.messages().plainText(
                                                EXPANSION_BATCH_ALREADY_REFUNDED));
                            }
                            plugin.runMain(() -> projectExpansionBatch(mayor, batch, success,
                                    failure));
                        } catch (RuntimeException exception) {
                            tasks.reportActionFailure(exception, failure);
                        }
                    });
                }, failure);
    }

    private void expandAction(Player mayor,
                              Supplier<TerritoryService.ExpansionPreview> previewSupplier,
                              Consumer<EconomyRepository.ExpansionOperation> success,
                              Consumer<RuntimeException> failure) {
        if (!consumptionEnabled.getAsBoolean()) {
            failure.accept(new IllegalStateException(
                    plugin.messages().plainText(CONSUMPTION_PAUSED)));
            return;
        }
        tasks.readAction(mayor, previewSupplier, preview -> {
            SitePolicy.Validation validation = territories.validate(preview);
            if (!validation.valid()) {
                failure.accept(new IllegalArgumentException(
                        plugin.messages().plainText(EXPANSION_VALIDATION_FAILED,
                                Map.of("detail", safeText(validation.error())))));
                return;
            }
            plugin.runAsync(() -> {
                try {
                    EconomyRepository.ExpansionOperation operation = finance.prepareExpansion(
                            new EconomyRepository.ExpansionRequest(preview.account().townId(),
                                    preview.candidate(), preview.residenceName(), preview.areaName(),
                                    preview.priceMinor(), mayor.getUniqueId(), mayor.getName(),
                                    "expansion:" + UUID.randomUUID()));
                    plugin.runMain(
                            () -> projectExpansion(mayor, operation, success, failure));
                } catch (RuntimeException exception) {
                    tasks.reportActionFailure(exception, failure);
                }
            });
        }, failure);
    }

    private void projectExpansion(CommandSender sender,
                                  EconomyRepository.ExpansionOperation operation,
                                  Consumer<EconomyRepository.ExpansionOperation> success,
                                  Consumer<RuntimeException> failure) {
        plugin.runAsync(() -> {
            try {
                List<UUID> loaded = repository.listLandAccessIds(operation.townId());
                plugin.runMain(
                        () -> addExpansionArea(sender, operation, loaded, success, failure));
            } catch (RuntimeException exception) {
                tasks.reportActionFailure(exception, failure);
            }
        });
    }

    private void addExpansionArea(CommandSender sender,
                                  EconomyRepository.ExpansionOperation operation,
                                  List<UUID> members,
                                  Consumer<EconomyRepository.ExpansionOperation> success,
                                  Consumer<RuntimeException> failure) {
        LandProtectionService.Result attempted;
        try {
            attempted = landProtection.addArea(operation.residenceName(),
                    new LandProtectionService.Area(operation.residenceAreaName(),
                            operation.unit().territory()), members);
        } catch (RuntimeException | LinkageError exception) {
            attempted = LandProtectionService.Result.failureCode(
                    LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", safeText(safeMessage(exception))));
        }
        LandProtectionService.Result result = attempted;
        String resultDetail = LandProtectionMessages.detail(plugin.messages(), result);
        plugin.runAsync(() -> {
            try {
                if (result.success()) {
                    finance.completeExpansion(operation.expansionId());
                } else {
                    finance.refundExpansion(operation.expansionId(),
                            resultDetail);
                }
                plugin.runMain(() -> {
                    if (result.success()) {
                        success.accept(operation);
                    } else {
                        failure.accept(new IllegalStateException(
                                plugin.messages().plainText(EXPANSION_FAILED_REFUNDED,
                                        Map.of("detail", safeText(resultDetail)))));
                    }
                });
            } catch (RuntimeException exception) {
                tasks.reportActionFailure(exception, failure);
            }
        });
    }

    private void projectExpansionBatch(CommandSender mayor,
                                       EconomyRepository.ExpansionBatchOperation batch,
                                       Consumer<EconomyRepository.ExpansionBatchOperation> success,
                                       Consumer<RuntimeException> failure) {
        plugin.runAsync(() -> {
            try {
                List<UUID> members = repository.listLandAccessIds(batch.townId());
                plugin.runMain(() -> addExpansionBatchAreas(mayor, batch, members, 0,
                        new ArrayList<>(), success, failure));
            } catch (RuntimeException exception) {
                tasks.reportActionFailure(exception, failure);
            }
        });
    }

    private void addExpansionBatchAreas(CommandSender mayor,
                                        EconomyRepository.ExpansionBatchOperation batch,
                                        List<UUID> members, int index, List<String> addedAreas,
                                        Consumer<EconomyRepository.ExpansionBatchOperation> success,
                                        Consumer<RuntimeException> failure) {
        if (index >= batch.expansions().size()) {
            plugin.runAsync(() -> {
                try {
                    EconomyRepository.ExpansionBatchOperation completed =
                            finance.completeExpansionBatch(batch.batchId());
                    plugin.runMain(() -> success.accept(completed));
                } catch (RuntimeException exception) {
                    rollbackExpansionBatchAreas(mayor, batch, addedAreas, exception, failure);
                }
            });
            return;
        }
        EconomyRepository.ExpansionOperation expansion = batch.expansions().get(index);
        boolean alreadyPresent;
        try {
            alreadyPresent = landProtection.hasArea(expansion.residenceName(),
                    expansion.residenceAreaName());
        } catch (RuntimeException | LinkageError exception) {
            rollbackExpansionBatchAreas(mayor, batch, addedAreas,
                    new IllegalStateException(plugin.messages().plainText(
                            EXPANSION_AREA_PRESENCE_CHECK_FAILED,
                            Map.of("detail", safeText(safeMessage(exception)))), exception), failure);
            return;
        }
        LandProtectionService.Result result;
        try {
            result = landProtection.addArea(expansion.residenceName(),
                    new LandProtectionService.Area(expansion.residenceAreaName(),
                            expansion.unit().territory()), members);
        } catch (RuntimeException | LinkageError exception) {
            result = LandProtectionService.Result.failureCode(
                    LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                    Map.of("detail", safeText(safeMessage(exception))));
        }
        if (!result.success()) {
            rollbackExpansionBatchAreas(mayor, batch, addedAreas,
                    new IllegalStateException(plugin.messages().plainText(
                            EXPANSION_BATCH_FAILED,
                            Map.of("detail", safeText(
                                    LandProtectionMessages.detail(plugin.messages(), result))))), failure);
            return;
        }
        if (!alreadyPresent) {
            addedAreas.add(expansion.residenceAreaName());
        }
        addExpansionBatchAreas(mayor, batch, members, index + 1, addedAreas, success, failure);
    }

    private void rollbackExpansionBatchAreas(CommandSender mayor,
                                              EconomyRepository.ExpansionBatchOperation batch,
                                              List<String> addedAreas, RuntimeException cause,
                                              Consumer<RuntimeException> failure) {
        if (!plugin.getServer().isPrimaryThread()) {
            plugin.runMain(() -> rollbackExpansionBatchAreas(mayor, batch, addedAreas, cause,
                    failure));
            return;
        }
        List<String> remaining = new ArrayList<>(addedAreas);
        java.util.Collections.reverse(remaining);
        List<String> cleanupErrors = new ArrayList<>();
        for (String area : remaining) {
            try {
                LandProtectionService.Result cleanup = landProtection.removeArea(
                        batch.expansions().getFirst().residenceName(), area);
                if (!cleanup.success()) {
                    cleanupErrors.add(area + ": " + safeText(
                            LandProtectionMessages.detail(plugin.messages(), cleanup)));
                }
            } catch (RuntimeException | LinkageError exception) {
                cleanupErrors.add(area + ": " + safeText(safeMessage(exception)));
            }
        }
        if (!cleanupErrors.isEmpty()) {
            plugin.getLogger().severe(plugin.messages().plainText(
                    EXPANSION_BATCH_ROLLBACK_FAILED,
                    Map.of("batch", batch.batchId(),
                            "cleanup", safeText(String.join("; ", cleanupErrors)))));
            failure.accept(new IllegalStateException(plugin.messages().plainText(
                    EXPANSION_BATCH_ROLLBACK_PARTIAL,
                    Map.of("cause", safeText(cause.getMessage()))), cause));
            return;
        }
        plugin.runAsync(() -> {
            try {
                finance.refundExpansionBatch(batch.batchId(), cause.getMessage());
                plugin.runMain(() -> failure.accept(cause));
            } catch (RuntimeException exception) {
                tasks.reportActionFailure(exception, failure);
            }
        });
    }

    void recoverExpansions(List<EconomyRepository.ExpansionOperation> expansions) {
        for (EconomyRepository.ExpansionOperation expansion : expansions) {
            plugin.runAsync(() -> {
                try {
                    List<UUID> members = repository.listLandAccessIds(expansion.townId());
                    plugin.runMain(
                            () -> addExpansionArea(org.bukkit.Bukkit.getConsoleSender(),
                                    expansion, members,
                                    ignored -> plugin.getLogger().info(
                                            plugin.messages().plainText(EXPANSION_RECOVERED,
                                                    Map.of("expansion", expansion.expansionId()))),
                                    exception -> plugin.getLogger().severe(
                                            plugin.messages().plainText(EXPANSION_RECOVERY_FAILED,
                                                    Map.of("expansion", expansion.expansionId(),
                                                            "detail", safeText(
                                                                    safeMessage(exception)))))));
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe(plugin.messages().plainText(
                            EXPANSION_RECOVERY_FAILED,
                            Map.of("expansion", expansion.expansionId(),
                                    "detail", safeText(safeMessage(exception)))));
                }
            });
        }
    }

    void recoverExpansionBatches(
            List<EconomyRepository.ExpansionBatchOperation> batches) {
        for (EconomyRepository.ExpansionBatchOperation batch : batches) {
            projectExpansionBatch(org.bukkit.Bukkit.getConsoleSender(), batch,
                    completed -> plugin.getLogger().info(plugin.messages().plainText(
                            EXPANSION_BATCH_RECOVERED,
                            Map.of("batch", safeText(batch.batchId())))),
                    exception -> plugin.getLogger().severe(plugin.messages().plainText(
                            EXPANSION_BATCH_RECOVERY_FAILED,
                            Map.of("batch", safeText(batch.batchId()),
                                    "detail", safeText(safeMessage(exception))))));
        }
    }
}

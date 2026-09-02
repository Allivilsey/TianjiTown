package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.ports.WorldBoundaryService;
import cn.tianji.town.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import cn.tianji.town.integrations.jobs.JobsIncomeTaxAdapter;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.integrations.quickshop.QuickShopTaxAdapter;
import cn.tianji.town.integrations.vault.VaultSettlementService;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.town.ApplicationSnapshot;
import cn.tianji.town.storage.town.TownRepository;
import cn.tianji.town.storage.town.TownSnapshot;
import cn.tianji.town.storage.governance.GovernanceRepository;
import cn.tianji.town.storage.governance.VoteSnapshot;
import cn.tianji.town.storage.economy.EconomyRepository;
import cn.tianji.town.storage.commerce.CommerceRepository;
import cn.tianji.town.storage.bonus.TownBonusRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.time.Instant;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class TownRuntime {
    private static final BigDecimal APPLICATION_FEE = new BigDecimal("2000.00");
    private static final String SCHEDULER_LIFECYCLE_STOPPED =
            "log.scheduler.lifecycle-stopped";
    private static final String QUICK_SHOP_TAX_REFRESH_FAILURE =
            "log.scheduler.quick-shop-tax-refresh-failure";
    private static final String QUICK_SHOP_TAX_SUBSIDY_QUOTA_EXHAUSTED =
            "log.quick-shop.subsidy-quota-exhausted";
    private static final String QUICK_SHOP_TAX_SUBSIDY_SETTLEMENT_FAILURE =
            "log.quick-shop.subsidy-settlement-failure";
    private static final String QUICK_SHOP_TAX_LEDGER_WRITE_FAILURE =
            "log.quick-shop.tax-ledger-write-failure";
    private static final String GLOBAL_MARKET_PLUS_INCOME_TAX_DEBIT_FAILURE =
            "log.global-market-plus.income-tax-debit-failure";
    private static final String GLOBAL_MARKET_PLUS_SUBSIDY_SETTLEMENT_REFUNDED =
            "log.global-market-plus.subsidy-settlement-failure-refunded";
    private static final String GLOBAL_MARKET_PLUS_SUBSIDY_SETTLEMENT_REFUND_FAILED =
            "log.global-market-plus.subsidy-settlement-failure-refund-failed";
    private static final String JOBS_INCOME_TAX_SETTLEMENT_FAILURE =
            "log.jobs.income-tax-settlement-failure";
    private static final String EXTERNAL_INCOME_TAX_LEDGER_WRITE_FAILURE =
            "log.external-income-tax.ledger-write-failure";
    private static final String SETTLEMENT_BALANCE_READ_FAILURE =
            "log.settlement.balance-read-failure";
    private static final String SETTLEMENT_SHORTFALL = "log.settlement.shortfall";
    private static final String SETTLEMENT_RECONCILIATION_FAILURE =
            "log.settlement.reconciliation-failure";
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
    private static final String STORAGE_UNAVAILABLE = "chat.lifecycle.storage-unavailable";
    private static final String STORAGE_WRITE_LOCKED = "chat.lifecycle.storage-write-locked";
    private static final String EXTERNAL_PREFLIGHT_FAILED =
            "chat.lifecycle.external-preflight-failed";
    private static final String EXTERNAL_OPERATION_FAILED = "chat.lifecycle.external-failed";
    private static final String COMPENSATION_AUTO = "chat.lifecycle.compensation-auto";
    private static final String COMPENSATION_MANUAL = "chat.lifecycle.compensation-manual";
    private static final String EXTERNAL_OPERATION_COMPENSATION_AUTO =
            "log.external-operation.compensation-auto";
    private static final String EXTERNAL_OPERATION_COMPENSATION_MANUAL =
            "log.external-operation.compensation-manual";
    private static final String LEDGER_ACTOR_NAME_BACKFILL_FAILURE =
            "log.lifecycle.ledger-actor-name-backfill-failure";
    private static final String LEDGER_ACTOR_SCAN_FAILURE =
            "log.lifecycle.ledger-actor-scan-failure";
    private static final String TOWN_REQUIRED = "validation.territory.town-required";
    private static final String DONATION_OPERATION_REASON = "log.donation.operation-reason";
    private static final String TAX_RATE_CHANGE_REASON = "log.tax.rate-change-reason";
    private static final String SQLITE_RECOVERED = "log.lifecycle.sqlite-recovered";
    private static final String SQLITE_INTERRUPTED = "log.lifecycle.sqlite-interrupted";
    private static final String INTERRUPTED_PROVISION_REASON =
            "log.lifecycle.interrupted-provision-reason";
    private static final String INTERRUPTED_PROVISIONS_RECOVERED =
            "log.lifecycle.interrupted-provisions-recovered";
    private static final String INTERRUPTED_PROVISION_RECOVERY_FAILURE =
            "log.lifecycle.interrupted-provision-recovery-failure";
    private static final String DONATION_COMPENSATION_RETRY_FAILED =
            "log.donation.compensation-retry-failed";
    private static final String DONATION_COMPENSATION_FINALIZATION_FAILED =
            "log.donation.compensation-finalization-failed";
    private static final String DONATION_COMPENSATION_RECOVERED =
            "log.donation.compensation-recovered";
    private static final String DONATION_COMPENSATION_EXHAUSTED =
            "log.donation.compensation-exhausted";
    private static final String DONATION_SETTLEMENT_BALANCE_READ_FAILURE =
            "log.donation.settlement-balance-read-failure";
    private static final String DONATION_SETTLEMENT_SHORTFALL =
            "log.donation.settlement-shortfall";
    private static final String DONATION_SETTLEMENT_RECONCILIATION_FAILURE =
            "log.donation.settlement-reconciliation-failure";
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
    private static final String PERIODIC_VOTE_SETTLEMENT_FAILURE =
            "log.scheduler.periodic.vote-settlement-failure";
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
    private static final String PROVISION_SITE_VALIDATION_DETAIL =
            "dialog.provision.site-validation-failed-detail";
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
    private static final String PROVISION_DEFAULT_TELEPORT_WORLD_DETAIL =
            "dialog.provision.default-teleport-world-unloaded-detail";
    private static final String PROVISION_DEFAULT_TELEPORT_HEIGHT_DETAIL =
            "dialog.provision.default-teleport-height-invalid-detail";
    private static final String PROVISION_DEFAULT_TELEPORT_SPACE_DETAIL =
            "dialog.provision.default-teleport-space-invalid-detail";
    private static final String PROVISION_DEFAULT_TELEPORT_ROLLED_BACK_DETAIL =
            "dialog.provision.default-teleport-failed-rolled-back-detail";
    private static final String PROVISION_DEFAULT_TELEPORT_ROLLBACK_FAILED_DETAIL =
            "dialog.provision.default-teleport-failed-rollback-failed-detail";
    private static final String PROVISION_LAND_WITH_TELEPORT_DETAIL =
            "dialog.provision.land-created-with-default-teleport-detail";
    private static final String PROVISION_RETRY_APPROVAL_ACTION =
            "dialog.provision.retry-approval-action";
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
    private final DatabaseGate database;
    private final TownRepository repository;
    private final GovernanceRepository governance;
    private final EconomyRepository finance;
    private final LandProtectionService landProtection;
    private final SitePolicy sitePolicy;
    private final TerritoryService territories;
    private final EconomySettings economySettings;
    private final VaultSettlementService settlement;
    private final BuffRuntime buffs;
    private final TownBonusRuntime bonuses;
    private final Map<UUID, QuickShopTaxAdapter.TaxPolicy> taxPolicies = new ConcurrentHashMap<>();
    private final RetryingWorkQueue<QuickShopTaxAdapter.SuccessfulTax> pendingTaxes;
    private final RetryingWorkQueue<EconomyRepository.ExternalIncomeTax> pendingIncomeTaxes;
    private final DonationCompensationCoordinator donationCompensations;
    private final AtomicBoolean databaseAvailable = new AtomicBoolean(true);
    private final AtomicBoolean quickShopTaxAvailable = new AtomicBoolean(false);
    private final ProvisionCoordinator provisions = new ProvisionCoordinator();
    private final Set<UUID> pendingLandRepairs = ConcurrentHashMap.newKeySet();
    private final Set<String> activeResidenceNames;

    TownRuntime(TianjiTownPlugin plugin, DatabaseGate database,
                    LandProtectionService landProtection,
                    WorldBoundaryService worldBoundaries,
                    Set<String> activeResidenceNames) {
        this.plugin = plugin;
        this.database = database;
        this.landProtection = landProtection;
        this.activeResidenceNames = java.util.Objects.requireNonNull(activeResidenceNames,
                "activeResidenceNames");
        this.sitePolicy = new SitePolicy(plugin, landProtection, worldBoundaries);
        this.repository = new TownRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.governance = new GovernanceRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.finance = new EconomyRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.economySettings = EconomySettings.load(plugin.getConfig(),
                plugin.messages()::plainText);
        this.settlement = new VaultSettlementService(plugin.getServer(),
                economySettings.settlementAccount(), economySettings.fallbackScale(),
                plugin.messages()::plainText);
        this.territories = new TerritoryService(finance, sitePolicy, plugin.messages(),
                economySettings, settlement.scale());
        this.buffs = new BuffRuntime(plugin, this,
                new CommerceRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                BuffSettings.load(plugin.getConfig(), settlement.scale(),
                        plugin.messages()::plainText));
        this.bonuses = new TownBonusRuntime(plugin, this,
                new TownBonusRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                TownBonusSettings.load(plugin.getConfig(), plugin.messages()::plainText),
                java.util.Objects.requireNonNull(
                plugin.getServer().getPluginManager().getPlugin("QuickShop-Hikari"),
                "QuickShop-Hikari"));
        this.pendingTaxes = new RetryingWorkQueue<>(new RetryingWorkQueue.Scheduler() {
            @Override
            public void executeAsync(Runnable task) {
                if (!plugin.runAsync(task)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            plugin.messages().plainText(SCHEDULER_LIFECYCLE_STOPPED));
                }
            }

            @Override
            public void schedule(Runnable task, long delayTicks) {
                if (!plugin.runMainLater(task, delayTicks)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            plugin.messages().plainText(SCHEDULER_LIFECYCLE_STOPPED));
                }
            }
        }, 20L * 5, 20L * 30, this::recordQuickShopTax, this::handleQuickShopTaxFailure);
        this.pendingIncomeTaxes = new RetryingWorkQueue<>(new RetryingWorkQueue.Scheduler() {
            @Override
            public void executeAsync(Runnable task) {
                if (!plugin.runAsync(task)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            plugin.messages().plainText(SCHEDULER_LIFECYCLE_STOPPED));
                }
            }

            @Override
            public void schedule(Runnable task, long delayTicks) {
                if (!plugin.runMainLater(task, delayTicks)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            plugin.messages().plainText(SCHEDULER_LIFECYCLE_STOPPED));
                }
            }
        }, 20L * 5, 20L * 30, this::recordExternalIncomeTax,
                this::handleExternalIncomeTaxFailure);
        this.donationCompensations = createDonationCompensationCoordinator();
    }

    private DonationCompensationCoordinator createDonationCompensationCoordinator() {
        return new DonationCompensationCoordinator(new DonationCompensationCoordinator.Scheduler() {
            @Override
            public void runMainLater(Runnable task, long delayTicks) {
                plugin.runMainLater(task, delayTicks);
            }

            @Override
            public void runAsync(Runnable task) {
                plugin.runAsync(task);
            }
        }, (playerId, amountMinor) -> settlement.refundDebitedPlayer(
                plugin.getServer().getOfflinePlayer(playerId), amountMinor),
                (operationId, detail) -> finance.resolveCompensation(operationId, detail),
                new DonationCompensationCoordinator.Listener() {
                    @Override
                    public void retryFailed(EconomyRepository.EconomyOperation operation,
                                            int attempt, String detail) {
                        plugin.getLogger().warning(plugin.messages().plainText(
                                DONATION_COMPENSATION_RETRY_FAILED, Map.of(
                                        "attempt", attempt,
                                        "operation", operation.operationId(),
                                        "detail", safeText(detail))));
                    }

                    @Override
                    public void finalizationFailed(EconomyRepository.EconomyOperation operation,
                                                   int attempt, String detail) {
                        plugin.getLogger().warning(plugin.messages().plainText(
                                DONATION_COMPENSATION_FINALIZATION_FAILED, Map.of(
                                        "attempt", attempt,
                                        "operation", operation.operationId(),
                                        "detail", safeText(detail))));
                    }

                    @Override
                    public void recovered(EconomyRepository.EconomyOperation operation,
                                          int attempts) {
                        plugin.getLogger().info(plugin.messages().plainText(
                                DONATION_COMPENSATION_RECOVERED, Map.of(
                                        "operation", operation.operationId(),
                                        "attempts", attempts)));
                        reconcileSettlementAfterCompensation(operation);
                    }

                    @Override
                    public void exhausted(EconomyRepository.EconomyOperation operation,
                                          String detail) {
                        plugin.getLogger().severe(plugin.messages().plainText(
                                DONATION_COMPENSATION_EXHAUSTED, Map.of(
                                        "operation", operation.operationId(),
                                        "detail", safeText(detail))));
                    }
                }, plugin.messages()::plainText);
    }

    private void reconcileSettlementAfterCompensation(
            EconomyRepository.EconomyOperation operation) {
        plugin.runMain(() -> {
            long externalBalance;
            try {
                externalBalance = settlement.balanceMinor();
            } catch (RuntimeException exception) {
                plugin.getLogger().severe(plugin.messages().plainText(
                        DONATION_SETTLEMENT_BALANCE_READ_FAILURE, Map.of(
                                "operation", operation.operationId(),
                                "detail", safeText(safeMessage(exception)))));
                return;
            }
            plugin.runAsync(() -> {
                try {
                    EconomyRepository.Reconciliation reconciliation =
                            finance.reconcileSettlement(externalBalance);
                    if (!reconciliation.healthy()) {
                        plugin.getLogger().severe(plugin.messages().plainText(
                                DONATION_SETTLEMENT_SHORTFALL, Map.of(
                                        "operation", operation.operationId(),
                                        "external", reconciliation.externalBalanceMinor(),
                                        "required", reconciliation.requiredMinor())));
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe(plugin.messages().plainText(
                            DONATION_SETTLEMENT_RECONCILIATION_FAILURE, Map.of(
                                    "operation", operation.operationId(),
                                    "detail", safeText(safeMessage(exception)))));
                }
            });
        });
    }

    TownRepository repository() {
        return repository;
    }

    GovernanceRepository governance() {
        return governance;
    }

    EconomyRepository finance() {
        return finance;
    }

    EconomySettings economySettings() {
        return economySettings;
    }

    VaultSettlementService settlement() {
        return settlement;
    }

    BuffRuntime buffs() {
        return buffs;
    }

    TownBonusRuntime bonuses() {
        return bonuses;
    }

    DatabaseGate database() {
        return database;
    }

    QuickShopTaxAdapter.TaxPolicy taxPolicy(UUID receiverId) {
        return taxEnabled() ? taxPolicies.get(receiverId) : null;
    }

    boolean taxEnabled() {
        return plugin.getConfig().getBoolean("economy.tax.enabled",
                economySettings.taxEnabled());
    }

    boolean quickShopTaxEnabled() {
        return taxEnabled() && quickShopTaxAvailable.get();
    }

    void setQuickShopTaxAvailable(boolean available) {
        quickShopTaxAvailable.set(available);
    }

    boolean consumptionEnabled() {
        return plugin.getConfig().getBoolean("economy.consumption.enabled",
                economySettings.consumptionEnabled());
    }

    LandProtectionService landProtection() {
        return landProtection;
    }

    SitePolicy sitePolicy() {
        return sitePolicy;
    }

    boolean databaseAvailable() {
        return databaseAvailable.get();
    }

    void checkRecovery() {
        plugin.runAsync(() -> {
            boolean healthy = database.ping();
            if (healthy) {
                try {
                    refreshTaxPolicies();
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            QUICK_SHOP_TAX_REFRESH_FAILURE,
                            Map.of("detail", safeText(safeMessage(exception)))));
                }
            }
            boolean previous = databaseAvailable.getAndSet(healthy);
            if (healthy && !previous) {
                plugin.getLogger().info(plugin.messages().plainText(SQLITE_RECOVERED));
                flushPendingTaxes();
            } else if (!healthy && previous) {
                plugin.getLogger().severe(plugin.messages().plainText(SQLITE_INTERRUPTED));
            }
        });
    }

    void recoverStartupState() {
        plugin.runAsync(() -> {
            try {
                finance.initializeAccounts();
                refreshTaxPolicies();
                int recovered = repository.recoverInterruptedProvisions(
                        plugin.messages().plainText(INTERRUPTED_PROVISION_REASON));
                databaseAvailable.set(true);
                if (recovered > 0) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            INTERRUPTED_PROVISIONS_RECOVERED,
                            Map.of("count", recovered)));
                }
                List<EconomyRepository.ExpansionOperation> expansions =
                        finance.pendingExpansions();
                if (!expansions.isEmpty()) {
                    plugin.runMain(
                            () -> recoverExpansions(expansions));
                }
                List<EconomyRepository.ExpansionBatchOperation> batches =
                        finance.pendingExpansionBatches();
                if (!batches.isEmpty()) {
                    plugin.runMain(() -> recoverExpansionBatches(batches));
                }
            } catch (RuntimeException exception) {
                if (exception instanceof TownRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe(plugin.messages().plainText(
                        INTERRUPTED_PROVISION_RECOVERY_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    void reconcileAll() {
        plugin.runAsync(() -> {
            try {
                List<TownMembers> states = repository.listTowns(false).stream()
                        .filter(town -> town.status() == TownStatus.ACTIVE)
                        .map(town -> new TownMembers(town, repository.listLandAccessIds(town.id()),
                                finance.territoryUnits(town.id())))
                        .toList();
                plugin.runMain(() -> {
                    for (TownMembers state : states) {
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
                            recordLandAudit(null, "SYSTEM", state.town().id(), false,
                                    LandProtectionService.Result.fromHealthyInspection(inspection));
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
                TownMembers latest = new TownMembers(town, repository.listLandAccessIds(townId),
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

    private static boolean hasUnsettledProjection(TownMembers state) {
        return state.units().stream()
                .anyMatch(unit -> !unit.projectionStatus().equals("ACTIVE"));
    }

    private void applyAutomaticLandRepair(TownMembers state, String detectedDifference) {
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
            recordLandAudit(null, "SYSTEM", state.town().id(),
                    outcome.repairAttempted(), outcome.result());
        } finally {
            pendingLandRepairs.remove(state.town().id());
        }
    }

    void settleDueVotes() {
        plugin.runAsync(() -> {
            try {
                List<VoteSnapshot> settled = governance.settleDueVotes();
                List<TownMembers> changedMemberships = settled.stream()
                        .filter(vote -> vote.passed()
                                && vote.type() == cn.tianji.town.core.governance.VoteType.KICK_MEMBER)
                        .map(VoteSnapshot::townId).distinct()
                        .map(townId -> repository.findTown(townId)
                                .map(town -> new TownMembers(town,
                                        repository.listLandAccessIds(townId),
                                        finance.territoryUnits(townId)))
                                .orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                databaseAvailable.set(true);
                if (!changedMemberships.isEmpty()) {
                    plugin.runMain(() -> {
                        for (TownMembers state : changedMemberships) {
                            reconcile(org.bukkit.Bukkit.getConsoleSender(), state.town(),
                                    state.members(), true);
                        }
                        buffs.refreshAllPlayers();
                    });
                }
            } catch (RuntimeException exception) {
                if (exception instanceof GovernanceRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe(plugin.messages().plainText(
                        PERIODIC_VOTE_SETTLEMENT_FAILURE, Map.of(
                                "detail", safeText(safeMessage(exception)))));
            }
        });
    }

    <T> void read(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, false, operation, success);
    }

    <T> void write(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, true, operation, success);
    }

    void provision(CommandSender sender, UUID applicationId, UUID reviewerId,
                   String reviewerName, String reason, String idempotencyKey,
        Consumer<ProvisionResult> completion) {
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
                        .orElseThrow(ApplicationNotFoundException::new);
                long feeMinor = application.applicationFeeMinor() > 0
                        ? application.applicationFeeMinor()
                        : APPLICATION_FEE.movePointRight(settlement.scale())
                        .longValueExact();
                Runnable start = () -> {
                    try {
                        chargeAndBeginProvision(sender, application, reviewerId, reviewerName,
                                reason, idempotencyKey, feeMinor, completion);
                    } catch (RuntimeException | LinkageError exception) {
                        provisions.finish(application.id());
                        handleFailure(sender, exception);
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
                handleFailure(sender, exception);
                ProvisionResult result = exception instanceof ApplicationNotFoundException
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
                                         UUID reviewerId, String reviewerName, String reason,
                                         String idempotencyKey, long feeMinor,
                                         Consumer<ProvisionResult> completion) {
        List<UUID> expectedMembers = new ArrayList<>();
        expectedMembers.add(application.applicantId());
        application.initialMembers().forEach(member -> expectedMembers.add(member.playerId()));
        SitePolicy.Validation environment = sitePolicy.validateEnvironment(application.territory());
        if (!environment.valid()) {
            provisions.finish(application.id());
            completion.accept(ProvisionResult.failure(application,
                    ProvisionResult.MessageRef.configured(PROVISION_SITE_VALIDATION_DETAIL,
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
                    ? LandProtectionService.Inspection.invalid("MISSING_TEMPORARY_TOWN")
                    : landProtection.inspect(application.text().normalizedResidenceName(),
                    application.territory(), expectedMembers);
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
                        "amount", money(feeMinor), "detail", paymentDetail));
                completion.accept(ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.configured(PROVISION_FEE_FAILURE_DETAIL,
                                Map.of("detail", paymentDetail)),
                        ProvisionResult.MessageRef.configured(PROVISION_FEE_FAILURE_ACTION)));
                return;
            }
        }
        if (!plugin.runAsync(() -> {
            try {
                TownRepository.Provisioning provisioning = repository.beginProvision(
                        application.id(), reviewerId, reviewerName, reason, idempotencyKey,
                        feeMinor, playerName(application.applicantId()));
                databaseAvailable.set(true);
                plugin.getLogger().info(plugin.messages().plainText(PROVISION_DATABASE_PREPARED,
                        Map.of("application", application.id(), "time", Instant.now())));
                if (!plugin.runMain(() -> projectProvision(sender, application.id(), provisioning,
                        completion))) {
                    provisions.finish(application.id());
                    completion.accept(ProvisionResult.failure(application,
                            ProvisionResult.MessageRef.configured(
                                    PROVISION_PROJECTION_START_FAILED_DETAIL),
                            ProvisionResult.MessageRef.configured(PROVISION_LIFECYCLE_RETRY_ACTION)));
                }
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
                    handleFailure(sender, exception);
                    completion.accept(ProvisionResult.failure(application,
                            ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                            ProvisionResult.MessageRef.configured(PROVISION_DATA_WRITE_ACTION)));
                };
                if (!plugin.runMain(failed)) {
                    failed.run();
                }
            }
        })) {
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
                ProvisionResult result = ProvisionResult.success(current);
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
            SitePolicy.Validation validation = sitePolicy.validateEnvironment(
                    provisioning.town().territory());
            LandProtectionService.Result land = validation.valid()
                    ? landProtection.create(provisioning.town().residenceName(),
                    provisioning.town().territory(),
                    provisioning.members())
                    : LandProtectionService.Result.failure(plugin.messages().plainText(
                            PROVISION_SITE_VALIDATION_DETAIL,
                            Map.of("detail", safeText(validation.error()))));
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
            plugin.getLogger().severe(plugin.messages().plainText(PROVISION_PROJECTION_EXCEPTION,
                    Map.of("application", applicationId, "detail", detail)));
            LandProtectionService.Result failed = LandProtectionService.Result.failure(detail);
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
        cn.tianji.town.core.land.ChunkPosition center = town.territory().center();
        org.bukkit.World world = plugin.getServer().getWorld(center.worldId());
        if (world == null) {
            world = plugin.getServer().getWorld(center.worldName());
        }
        if (world == null) {
            return LandProtectionService.Result.failure(plugin.messages().plainText(
                    PROVISION_DEFAULT_TELEPORT_WORLD_DETAIL));
        }
        int blockX = Math.addExact(Math.multiplyExact(center.x(), 16), 8);
        int blockZ = Math.addExact(Math.multiplyExact(center.z(), 16), 8);
        int blockY = world.getHighestBlockYAt(blockX, blockZ) + 1;
        if (blockY <= world.getMinHeight() || blockY + 1 >= world.getMaxHeight()) {
            return LandProtectionService.Result.failure(plugin.messages().plainText(
                    PROVISION_DEFAULT_TELEPORT_HEIGHT_DETAIL));
        }
        Location location = new Location(world, blockX + 0.5D, blockY, blockZ + 0.5D);
        if (!location.getBlock().isPassable()
                || !location.getBlock().getRelative(0, 1, 0).isPassable()
                || !location.getBlock().getRelative(0, -1, 0).getType().isSolid()) {
            return LandProtectionService.Result.failure(plugin.messages().plainText(
                    PROVISION_DEFAULT_TELEPORT_SPACE_DETAIL));
        }
        LandProtectionService.Result teleport = landProtection.setTeleportPoint(
                town.residenceName(), world.getUID(), world.getName(), location.getX(),
                location.getY(), location.getZ(), 0.0F, 0.0F);
        if (!teleport.success()) {
            LandProtectionService.Result cleanup = landProtection.remove(town.residenceName(),
                    town.territory());
            String teleportDetail = safeText(LandProtectionMessages.detail(plugin.messages(), teleport));
            if (cleanup.success()) {
                return LandProtectionService.Result.failure(plugin.messages().plainText(
                        PROVISION_DEFAULT_TELEPORT_ROLLED_BACK_DETAIL,
                        Map.of("detail", teleportDetail)));
            }
            return LandProtectionService.Result.failure(plugin.messages().plainText(
                    PROVISION_DEFAULT_TELEPORT_ROLLBACK_FAILED_DETAIL,
                    Map.of("detail", teleportDetail,
                            "cleanup", safeText(LandProtectionMessages.detail(
                                    plugin.messages(), cleanup)))));
        }
        return LandProtectionService.Result.ok(plugin.messages().plainText(
                PROVISION_LAND_WITH_TELEPORT_DETAIL,
                Map.of("detail", safeText(LandProtectionMessages.detail(plugin.messages(), land)))));
    }

    private void finishProvision(CommandSender sender, UUID applicationId,
                                 LandProtectionService.Result land,
                                 Consumer<ProvisionResult> completion) {
        try {
            boolean completed = land.success();
            String completedDetail = safeText(LandProtectionMessages.detail(plugin.messages(), land));
            ApplicationSnapshot application = repository.finishProvision(
                    applicationId, completed, completedDetail);
            if (completed) {
                repository.findApplication(applicationId).map(ApplicationSnapshot::townId)
                        .flatMap(repository::findTown).map(TownSnapshot::residenceName)
                        .ifPresent(activeResidenceNames::add);
                refreshTaxPolicies();
            }
            databaseAvailable.set(true);
            Runnable callback = () -> {
                plugin.messages().send(sender, completed ? "chat.runtime.provision-success"
                        : "chat.runtime.provision-failed", completed
                        ? Map.of() : Map.of("detail", completedDetail));
                plugin.getLogger().info(plugin.messages().plainText(PROVISION_UI_CALLBACK,
                        Map.of("application", applicationId, "time", Instant.now(),
                                "success", completed)));
                completion.accept(completed ? ProvisionResult.success(application)
                        : ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.literal(completedDetail),
                        ProvisionResult.MessageRef.configured(PROVISION_RETRY_APPROVAL_ACTION)));
            };
            if (!plugin.runMain(callback)) {
                completion.accept(completed ? ProvisionResult.success(application)
                        : ProvisionResult.failure(application,
                        ProvisionResult.MessageRef.literal(completedDetail),
                        ProvisionResult.MessageRef.configured(PROVISION_RETRY_APPROVAL_ACTION)));
            }
        } catch (RuntimeException exception) {
            handleFailure(sender, exception);
            ProvisionResult result = ProvisionResult.failure(null,
                    ProvisionResult.MessageRef.literal(safeText(safeMessage(exception))),
                    ProvisionResult.MessageRef.configured(PROVISION_REFRESH_STATE_ACTION));
            if (!plugin.runMain(() -> completion.accept(result))) {
                completion.accept(result);
            }
        } finally {
            provisions.finish(applicationId);
        }
    }

    private String playerName(UUID playerId) {
        String name = plugin.getServer().getOfflinePlayer(playerId).getName();
        return name == null || name.isBlank() ? playerId.toString() : name;
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

    void deactivateResidence(String residenceName) {
        activeResidenceNames.remove(residenceName.toLowerCase(java.util.Locale.ROOT));
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
                exception -> handleFailure(sender, exception));
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
                refreshTaxPolicies();
                plugin.runMain(() -> {
                    LandProtectionService.Result result;
                    try {
                        result = landProtection.reconcile(town.residenceName(), areas, members,
                                repair);
                    } catch (RuntimeException | LinkageError exception) {
                        result = LandProtectionService.Result.failureCode(
                                LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                                Map.of("detail", safeText(safeMessage(exception))));
                    }
                    recordLandAudit(actorId(sender), sender.getName(), town.id(), repair, result);
                    success.accept(result);
                });
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
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

    void acceptQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        plugin.runAsync(() -> {
            try {
                EconomyRepository.SubsidyReservation reservation =
                        finance.reserveQuickShopSubsidy(tax.townId(), tax.businessKey(),
                                tax.taxMinor(), economySettings.weeklySubsidyLimitMinor(
                                        settlement.scale()),
                                economySettings.twelveHourSubsidyLimitMinor(settlement.scale()),
                                Instant.now(), ZoneId.systemDefault());
                plugin.runMain(() -> applyQuickShopSubsidy(tax, reservation));
            } catch (RuntimeException exception) {
                handleQuickShopTaxFailure(tax, exception);
            }
        });
    }

    private void applyQuickShopSubsidy(QuickShopTaxAdapter.SuccessfulTax tax,
                                       EconomyRepository.SubsidyReservation reservation) {
        VaultSettlementService.Result subsidy = reservation.grantedMinor() == 0
                ? VaultSettlementService.Result.success(plugin.messages().plainText(
                        QUICK_SHOP_TAX_SUBSIDY_QUOTA_EXHAUSTED))
                : settlement.adjustSettlement(reservation.grantedMinor());
        if (subsidy.success()) {
            pendingTaxes.submit(tax);
            return;
        }
        String subsidyDetail = subsidy.message();
        plugin.runAsync(() -> finance.cancelQuickShopSubsidy(tax.businessKey(),
                subsidyDetail));
        plugin.getLogger().severe(plugin.messages().plainText(
                QUICK_SHOP_TAX_SUBSIDY_SETTLEMENT_FAILURE,
                Map.of("detail", safeText(subsidyDetail))));
    }

    void flushPendingTaxes() {
        pendingTaxes.flush();
        pendingIncomeTaxes.flush();
    }

    private void recordQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        finance.recordQuickShopTax(new EconomyRepository.QuickShopTax(
                tax.townId(), tax.businessKey(), tax.shopId(), tax.shopType(),
                tax.receiverId(), tax.receiverName(), tax.interactingId(), tax.grossMinor(),
                tax.basisPoints(), tax.taxMinor(), tax.worldName()));
        databaseAvailable.set(true);
    }

    EconomyRepository.SubsidyQuota quickShopSubsidyQuota(UUID townId) {
        return finance.quickShopSubsidyQuota(townId,
                economySettings.weeklySubsidyLimitMinor(settlement.scale()),
                economySettings.twelveHourSubsidyLimitMinor(settlement.scale()),
                Instant.now(), ZoneId.systemDefault());
    }

    private void handleQuickShopTaxFailure(QuickShopTaxAdapter.SuccessfulTax tax,
                                           RuntimeException exception) {
        if (exception instanceof EconomyRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
        }
        plugin.getLogger().severe(plugin.messages().plainText(
                QUICK_SHOP_TAX_LEDGER_WRITE_FAILURE,
                Map.of("businessKey", safeText(tax.businessKey()),
                        "detail", safeText(safeMessage(exception)))));
    }

    JobsIncomeTaxAdapter.TaxResult acceptJobsIncomeTax(
            JobsIncomeTaxAdapter.Earning earning) {
        EconomyRepository.ExternalIncomeTax tax = externalIncomeTax("JOBS",
                earning.player(), earning.player().getName(), earning.grossAmount(),
                "jobs:" + UUID.randomUUID());
        if (tax == null) {
            return JobsIncomeTaxAdapter.TaxResult.unchanged(earning.grossAmount());
        }
        VaultSettlementService.Result transferred = settlement.adjustSettlement(
                Math.multiplyExact(tax.taxMinor(), 2));
        if (!transferred.success()) {
            plugin.getLogger().severe(plugin.messages().plainText(
                    JOBS_INCOME_TAX_SETTLEMENT_FAILURE,
                    Map.of("detail", safeText(transferred.message()))));
            return JobsIncomeTaxAdapter.TaxResult.unchanged(earning.grossAmount());
        }
        pendingIncomeTaxes.submit(tax);
        double net = BigDecimal.valueOf(tax.grossMinor() - tax.taxMinor(),
                settlement.scale()).doubleValue();
        return JobsIncomeTaxAdapter.TaxResult.taxed(net);
    }

    void acceptGlobalMarketPlusIncomeTax(GlobalMarketPlusIncomeTaxAdapter.Earning earning) {
        EconomyRepository.ExternalIncomeTax tax = externalIncomeTax("GLOBALMARKETPLUS",
                earning.player(), earning.receiverName(), earning.grossAmount(),
                earning.businessKey());
        if (tax == null) {
            return;
        }
        VaultSettlementService.Result transferred = settlement.transferFromPlayer(
                earning.player(), tax.taxMinor());
        if (!transferred.success()) {
            plugin.getLogger().severe(plugin.messages().plainText(
                    GLOBAL_MARKET_PLUS_INCOME_TAX_DEBIT_FAILURE,
                    Map.of("detail", safeText(transferred.message()))));
            return;
        }
        VaultSettlementService.Result subsidy = settlement.adjustSettlement(tax.taxMinor());
        if (!subsidy.success()) {
            VaultSettlementService.Result refunded = settlement.transferToPlayer(
                    earning.player(), tax.taxMinor());
            String messageKey = refunded.success()
                    ? GLOBAL_MARKET_PLUS_SUBSIDY_SETTLEMENT_REFUNDED
                    : GLOBAL_MARKET_PLUS_SUBSIDY_SETTLEMENT_REFUND_FAILED;
            plugin.getLogger().severe(plugin.messages().plainText(messageKey,
                    Map.of("detail", safeText(subsidy.message()))));
            return;
        }
        pendingIncomeTaxes.submit(tax);
        Player receiver = earning.player().getPlayer();
        if (receiver != null) {
            plugin.messages().send(receiver, "chat.runtime.global-market-income", Map.of(
                    "gross", money(tax.grossMinor()), "tax", money(tax.taxMinor()),
                    "net", money(tax.grossMinor() - tax.taxMinor())));
        }
    }

    private EconomyRepository.ExternalIncomeTax externalIncomeTax(
            String source, org.bukkit.OfflinePlayer receiver, String receiverName, double gross,
            String businessKey) {
        if (!taxEnabled() || !Double.isFinite(gross) || gross <= 0) {
            return null;
        }
        QuickShopTaxAdapter.TaxPolicy policy = taxPolicies.get(receiver.getUniqueId());
        if (policy == null || policy.basisPoints() <= 0) {
            return null;
        }
        long grossMinor = BigDecimal.valueOf(gross).movePointRight(settlement.scale())
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long taxMinor = BigDecimal.valueOf(grossMinor)
                .multiply(BigDecimal.valueOf(policy.basisPoints()))
                .movePointLeft(4).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (grossMinor <= 0 || taxMinor <= 0 || taxMinor >= grossMinor) {
            return null;
        }
        String safeName = receiverName == null || receiverName.isBlank()
                ? receiver.getUniqueId().toString() : receiverName;
        return new EconomyRepository.ExternalIncomeTax(policy.townId(), businessKey, source,
                receiver.getUniqueId(), safeName, grossMinor, policy.basisPoints(), taxMinor);
    }

    private void recordExternalIncomeTax(EconomyRepository.ExternalIncomeTax tax) {
        finance.recordExternalIncomeTax(tax);
        databaseAvailable.set(true);
    }

    private void handleExternalIncomeTaxFailure(EconomyRepository.ExternalIncomeTax tax,
                                                RuntimeException exception) {
        if (exception instanceof EconomyRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
        }
        plugin.getLogger().severe(plugin.messages().plainText(
                EXTERNAL_INCOME_TAX_LEDGER_WRITE_FAILURE,
                Map.of("source", safeText(tax.source()),
                        "businessKey", safeText(tax.businessKey()),
                        "detail", safeText(safeMessage(exception)))));
    }

    void reconcileSettlement() {
        long external;
        try {
            external = settlement.balanceMinor();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(plugin.messages().plainText(
                    SETTLEMENT_BALANCE_READ_FAILURE,
                    Map.of("detail", safeText(safeMessage(exception)))));
            return;
        }
        plugin.runAsync(() -> {
            try {
                EconomyRepository.Reconciliation result = finance.reconcileSettlement(external);
                if (!result.healthy()) {
                    plugin.getLogger().severe(plugin.messages().plainText(SETTLEMENT_SHORTFALL,
                            Map.of("external", money(result.externalBalanceMinor()),
                                    "required", money(result.requiredMinor()))));
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().severe(plugin.messages().plainText(
                        SETTLEMENT_RECONCILIATION_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    void donateAction(Player player, long amountMinor,
                      Consumer<EconomyRepository.LedgerMutation> success,
                      Consumer<RuntimeException> failure) {
        if (!consumptionEnabled()) {
            failure.accept(new IllegalStateException(
                    plugin.messages().plainText(CONSUMPTION_PAUSED)));
            return;
        }
        executeExternalOperation(player, () -> {
            EconomyRepository.TownFinance account = finance.findFinanceByPlayer(
                    player.getUniqueId()).orElseThrow(() ->
                    new IllegalArgumentException(plugin.messages().plainText(TOWN_REQUIRED)));
            String key = "donation:" + UUID.randomUUID();
            return finance.prepareOperation(account.townId(), "DONATION", amountMinor,
                    player.getUniqueId(), player.getName(), key,
                    plugin.messages().plainText(DONATION_OPERATION_REASON));
        }, operation -> settlement.transferFromPlayer(player, operation.amountMinor()), success,
                failure);
    }

    void adjustFunds(CommandSender sender, UUID townId, long amountMinor, String reason) {
        executeExternalOperation(sender, () -> finance.prepareOperation(townId,
                        "ADMIN_ADJUSTMENT", amountMinor, actorId(sender), sender.getName(),
                        "admin-adjustment:" + UUID.randomUUID(), reason),
                operation -> settlement.adjustSettlement(operation.amountMinor()),
                mutation -> plugin.messages().send(sender, "chat.runtime.funds-adjusted",
                        Map.of("balance", money(mutation.balanceAfterMinor()))));
    }

    void changeTaxRate(Player mayor, UUID townId, int basisPoints) {
        if (!taxEnabled()) {
            plugin.messages().send(mayor, "chat.runtime.tax-paused");
            return;
        }
        if (!economySettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(mayor);
            return;
        }
        write(mayor, () -> {
            EconomyRepository.TaxChange change = finance.changeTaxRate(townId,
                    mayor.getUniqueId(), basisPoints, mayor.getName(),
                    plugin.messages().plainText(TAX_RATE_CHANGE_REASON));
            refreshTaxPolicies();
            return change;
        }, change -> {
            plugin.messages().send(mayor, "chat.runtime.tax-changed", Map.of(
                    "rate", percent(change.basisPoints())));
            TownUiController ui = plugin.townUi();
            if (ui != null) {
                ui.openFinance(mayor, 0);
            }
        });
    }

    void forceTaxRate(CommandSender sender, UUID townId, int basisPoints, String reason) {
        if (!economySettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(sender);
            return;
        }
        write(sender, () -> {
            EconomyRepository.TaxChange change = finance.forceTaxRate(townId,
                    actorId(sender), basisPoints, sender.getName(), reason);
            refreshTaxPolicies();
            return change;
        }, change -> plugin.messages().send(sender, "chat.runtime.tax-forced", Map.of(
                "rate", percent(change.basisPoints()))));
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
        read(player, () -> territories.map(player.getUniqueId()),
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
        if (!consumptionEnabled()) {
            failure.accept(new IllegalStateException(
                    plugin.messages().plainText(CONSUMPTION_PAUSED)));
            return;
        }
        readAction(mayor, () -> territories.batchPreview(mayor.getUniqueId(), selections),
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
                            reportActionFailure(exception, failure);
                        }
                    });
                }, failure);
    }

    private void expandAction(Player mayor,
                              Supplier<TerritoryService.ExpansionPreview> previewSupplier,
                              Consumer<EconomyRepository.ExpansionOperation> success,
                              Consumer<RuntimeException> failure) {
        if (!consumptionEnabled()) {
            failure.accept(new IllegalStateException(
                    plugin.messages().plainText(CONSUMPTION_PAUSED)));
            return;
        }
        readAction(mayor, previewSupplier, preview -> {
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
                    reportActionFailure(exception, failure);
                }
            });
        }, failure);
    }

    <T> void writeAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                         Consumer<RuntimeException> failure) {
        executeAction(sender, true, operation, success, failure);
    }

    <T> void readAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                        Consumer<RuntimeException> failure) {
        executeAction(sender, false, operation, success, failure);
    }

    String money(long minorUnits) {
        return java.math.BigDecimal.valueOf(minorUnits, settlement.scale()).toPlainString();
    }

    static String percent(int basisPoints) {
        return java.math.BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString()
                + "%";
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
                reportActionFailure(exception, failure);
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
                reportActionFailure(exception, failure);
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
                reportActionFailure(exception, failure);
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
                reportActionFailure(exception, failure);
            }
        });
    }

    private void recoverExpansions(List<EconomyRepository.ExpansionOperation> expansions) {
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

    private void recoverExpansionBatches(
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

    private void executeExternalOperation(CommandSender sender,
                                          Supplier<EconomyRepository.EconomyOperation> prepare,
                                          java.util.function.Function<EconomyRepository.EconomyOperation,
                                                  VaultSettlementService.Result> external,
                                          Consumer<EconomyRepository.LedgerMutation> success) {
        executeExternalOperation(sender, prepare, external, success,
                exception -> handleFailure(sender, exception));
    }

    private void executeExternalOperation(CommandSender sender,
                                          Supplier<EconomyRepository.EconomyOperation> prepare,
                                          java.util.function.Function<EconomyRepository.EconomyOperation,
                                                  VaultSettlementService.Result> external,
                                          Consumer<EconomyRepository.LedgerMutation> success,
                                          Consumer<RuntimeException> failure) {
        if (!databaseAvailable.get()) {
            failure.accept(new EconomyRepository.StorageUnavailableException(
                    plugin.messages().plainText(STORAGE_UNAVAILABLE), null));
            return;
        }
        plugin.runAsync(() -> {
            try {
                EconomyRepository.EconomyOperation operation = prepare.get();
                plugin.runMain(
                        () -> preflightExternalOperation(sender, operation, external, success,
                                failure));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void preflightExternalOperation(CommandSender sender,
                                            EconomyRepository.EconomyOperation operation,
                                            java.util.function.Function<
                                                    EconomyRepository.EconomyOperation,
                                                    VaultSettlementService.Result> external,
                                            Consumer<EconomyRepository.LedgerMutation> success,
                                            Consumer<RuntimeException> failure) {
        VaultSettlementService.Result availability;
        try {
            availability = settlement.checkAvailability();
        } catch (RuntimeException exception) {
            availability = VaultSettlementService.Result.failure(
                    plugin.messages().plainText(EXTERNAL_PREFLIGHT_FAILED,
                            Map.of("detail", safeText(safeMessage(exception)))),
                    false, false);
        }
        if (!availability.success()) {
            finishFailedExternalOperation(sender, operation, availability, failure);
            return;
        }
        plugin.runAsync(() -> {
            try {
                finance.markOperationExternalApplied(operation.operationId());
                plugin.runMain(
                        () -> applyExternalOperation(sender, operation, external, success,
                                failure));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void applyExternalOperation(CommandSender sender,
                                        EconomyRepository.EconomyOperation operation,
                                        java.util.function.Function<
                                                EconomyRepository.EconomyOperation,
                                                VaultSettlementService.Result> external,
                                        Consumer<EconomyRepository.LedgerMutation> success,
                                        Consumer<RuntimeException> failure) {
        VaultSettlementService.Result result;
        try {
            result = external.apply(operation);
        } catch (RuntimeException exception) {
            result = VaultSettlementService.Result.failure(
                    plugin.messages().plainText(EXTERNAL_OPERATION_FAILED,
                            Map.of("detail", safeText(safeMessage(exception)))),
                    false, true);
        }
        if (!result.success()) {
            finishFailedExternalOperation(sender, operation, result, failure);
            return;
        }
        plugin.runAsync(() -> {
            try {
                EconomyRepository.LedgerMutation mutation =
                        finance.completeOperation(operation.operationId());
                plugin.runMain(() -> success.accept(mutation));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void finishFailedExternalOperation(CommandSender sender,
                                               EconomyRepository.EconomyOperation operation,
                                               VaultSettlementService.Result result,
                                               Consumer<RuntimeException> failure) {
        plugin.runAsync(() -> {
            try {
                if (result.compensationRequired()) {
                    finance.requireCompensation(operation.operationId(), result.message());
                    boolean automaticRefund = result.playerRefundRequired()
                            && operation.operationType().equals("DONATION")
                            && operation.actorId() != null;
                    if (automaticRefund) {
                        donationCompensations.submit(operation);
                        plugin.getLogger().warning(plugin.messages().plainText(
                                EXTERNAL_OPERATION_COMPENSATION_AUTO,
                                Map.of("operation", safeText(operation.operationId()),
                                        "town", safeText(operation.townId()),
                                        "detail", safeText(result.message()))));
                    } else {
                        plugin.getLogger().severe(plugin.messages().plainText(
                                EXTERNAL_OPERATION_COMPENSATION_MANUAL,
                                Map.of("operation", safeText(operation.operationId()),
                                        "town", safeText(operation.townId()),
                                        "detail", safeText(result.message()))));
                    }
                } else {
                    finance.cancelOperation(operation.operationId(), result.message());
                }
                plugin.runMain(() -> failure.accept(
                        new IllegalStateException(result.message()
                                + compensationHint(operation, result))));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private String compensationHint(EconomyRepository.EconomyOperation operation,
                                    VaultSettlementService.Result result) {
        if (!result.compensationRequired()) {
            return "";
        }
        String key = result.playerRefundRequired()
                && operation.operationType().equals("DONATION")
                && operation.actorId() != null
                ? COMPENSATION_AUTO : COMPENSATION_MANUAL;
        return plugin.messages().plainText(key);
    }

    void refreshTaxPolicies() {
        Map<UUID, QuickShopTaxAdapter.TaxPolicy> loaded = new java.util.HashMap<>();
        for (EconomyRepository.MemberTaxPolicy policy : finance.loadMemberTaxPolicies()) {
            loaded.put(policy.playerId(), new QuickShopTaxAdapter.TaxPolicy(policy.townId(),
                    policy.taxRateBps()));
        }
        taxPolicies.clear();
        taxPolicies.putAll(loaded);
    }

    void backfillKnownPlayerNames() {
        plugin.runAsync(() -> {
            try {
                List<UUID> unresolved = finance.unresolvedLedgerActorIds();
                plugin.runMain(() -> {
                    Map<UUID, String> confirmed = new java.util.HashMap<>();
                    for (UUID playerId : unresolved) {
                        org.bukkit.OfflinePlayer player = plugin.getServer()
                                .getOfflinePlayer(playerId);
                        if ((player.hasPlayedBefore() || player.isOnline())
                                && player.getName() != null && !player.getName().isBlank()) {
                            confirmed.put(playerId, player.getName());
                        }
                    }
                    plugin.runAsync(() -> confirmed.forEach((playerId, name) -> {
                        try {
                            finance.backfillLedgerActorName(playerId, name);
                        } catch (RuntimeException exception) {
                            plugin.getLogger().warning(plugin.messages().plainText(
                                    LEDGER_ACTOR_NAME_BACKFILL_FAILURE,
                                    Map.of("playerId", safeText(playerId),
                                            "detail", safeText(safeMessage(exception)))));
                        }
                    }));
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        LEDGER_ACTOR_SCAN_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    private static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private void sendTaxRangeError(CommandSender sender) {
        plugin.messages().send(sender, "chat.runtime.tax-range");
    }

    private <T> void execute(CommandSender sender, boolean write, Supplier<T> operation,
                             Consumer<T> success) {
        if (write && !databaseAvailable.get()) {
            plugin.messages().send(sender, "chat.runtime.storage-locked");
            return;
        }
        plugin.runAsync(() -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.runMain(() -> success.accept(result));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private <T> void executeAction(CommandSender sender, boolean write, Supplier<T> operation,
                                   Consumer<T> success, Consumer<RuntimeException> failure) {
        if (write && !databaseAvailable.get()) {
            failure.accept(new TownRepository.StorageUnavailableException(
                    plugin.messages().plainText(STORAGE_WRITE_LOCKED), null));
            return;
        }
        plugin.runAsync(() -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.runMain(() -> success.accept(result));
            } catch (RuntimeException exception) {
                markStorageFailure(exception);
                plugin.runMain(() -> failure.accept(exception));
            }
        });
    }

    private void handleFailure(CommandSender sender, Throwable exception) {
        if (exception instanceof RuntimeException runtimeException) {
            markStorageFailure(runtimeException);
        }
        String detail = exception instanceof ApplicationNotFoundException
                ? plugin.messages().plainText(PROVISION_APPLICATION_NOT_FOUND_DETAIL)
                : safeText(safeMessage(exception));
        plugin.runMain(
                () -> plugin.messages().send(sender, "chat.runtime.operation-failed",
                        Map.of("detail", detail)));
    }

    private void reportActionFailure(RuntimeException exception,
                                     Consumer<RuntimeException> failure) {
        markStorageFailure(exception);
        if (plugin.getServer().isPrimaryThread()) {
            failure.accept(exception);
        } else {
            plugin.runMain(() -> failure.accept(exception));
        }
    }

    private void markStorageFailure(RuntimeException exception) {
        if (exception instanceof TownRepository.StorageUnavailableException
                || exception instanceof GovernanceRepository.StorageUnavailableException
                || exception instanceof EconomyRepository.StorageUnavailableException
                || exception instanceof CommerceRepository.StorageUnavailableException
                || exception instanceof TownBonusRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
            plugin.getLogger().severe(exception.getMessage());
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private static final class ApplicationNotFoundException extends RuntimeException {
    }

    private record TownMembers(TownSnapshot town, List<UUID> members,
                               List<EconomyRepository.TerritoryUnitSnapshot> units) {
    }
}

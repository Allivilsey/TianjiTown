package org.allivlisey.tianjitown.paper.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.allivlisey.tianjitown.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.jobs.JobsIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopTaxAdapter;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.task.RetryingWorkQueue;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Tax policy cache, external tax settlement and retry queues. */
final class TownTaxRuntime {
    private static final String QUICK_SHOP_TAX_SUBSIDY_QUOTA_EXHAUSTED =
            "log.quick-shop.subsidy-quota-exhausted";
    private static final String QUICK_SHOP_TAX_SUBSIDY_SETTLEMENT_FAILURE =
            "log.quick-shop.subsidy-settlement-failure";
    private static final String QUICK_SHOP_TAX_LEDGER_WRITE_FAILURE =
            "log.quick-shop.tax-ledger-write-failure";
    private static final String GLOBAL_MARKET_PLUS_INCOME_TAX_DEBIT_FAILURE =
            "log.global-market-plus.income-tax-debit-failure";
    private static final String EXTERNAL_SUBSIDY_SETTLEMENT_FAILURE =
            "log.external-income-tax.subsidy-settlement-failure";
    private static final String EXTERNAL_SUBSIDY_SETTLEMENT_AMBIGUOUS =
            "log.external-income-tax.subsidy-settlement-ambiguous";
    private static final String JOBS_INCOME_TAX_SETTLEMENT_FAILURE =
            "log.jobs.income-tax-settlement-failure";
    private static final String EXTERNAL_INCOME_TAX_LEDGER_WRITE_FAILURE =
            "log.external-income-tax.ledger-write-failure";
    private final TianjiTownPlugin plugin;
    private final EconomyRepository finance;
    private final EconomySettings economySettings;
    private final VaultSettlementService settlement;
    private final AtomicBoolean databaseAvailable;
    private final Map<UUID, QuickShopTaxAdapter.TaxPolicy> taxPolicies = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> archivedTowns = ConcurrentHashMap.newKeySet();
    private final Object taxPolicyLock = new Object();
    private final TownIncomeTaxCollectionRuntime incomeCollections;
    private final RetryingWorkQueue<PendingQuickShopTax> pendingTaxes;
    private final RetryingWorkQueue<PendingQuickShopTax> pendingQuickShopPreparations;
    private final RetryingWorkQueue<PendingQuickShopTax> pendingQuickShopPayments;
    private final RetryingWorkQueue<PendingIncomeSubsidy> pendingIncomeTaxes;
    private final RetryingWorkQueue<PendingIncomeSubsidy> pendingIncomeSubsidies;
    private final RetryingWorkQueue<PendingIncomeSubsidy> pendingIncomeSubsidyPayments;
    private final AtomicBoolean quickShopTaxAvailable = new AtomicBoolean(false);
    private static final String SCHEDULER_LIFECYCLE_STOPPED = "log.scheduler.lifecycle-stopped";

    TownTaxRuntime(TianjiTownPlugin plugin,
            EconomyRepository finance,
            EconomySettings economySettings,
            VaultSettlementService settlement,
            AtomicBoolean databaseAvailable) {
        this.plugin = plugin;
        this.finance = finance;
        this.economySettings = economySettings;
        this.settlement = settlement;
        this.databaseAvailable = databaseAvailable;
        RetryingWorkQueue.Scheduler asyncScheduler = taxScheduler(false);
        this.pendingTaxes = new RetryingWorkQueue<>(asyncScheduler,
                20L * 5, 20L * 30, this::recordQuickShopTax,
                (pending, exception) -> handleQuickShopTaxFailure(pending.tax, exception));
        this.pendingQuickShopPayments = new RetryingWorkQueue<>(taxScheduler(true),
                20L * 5, 20L * 30, this::applyQuickShopSubsidy,
                (pending, exception) -> handleQuickShopTaxFailure(pending.tax, exception));
        this.pendingQuickShopPreparations = new RetryingWorkQueue<>(asyncScheduler,
                20L * 5, 20L * 30, pending -> {
                    if (pending.reservation == null) {
                        pending.reservation = finance.reserveTaxSubsidy(pending.tax.townId(),
                                pending.tax.businessKey(), pending.tax.taxMinor(),
                                economySettings.weeklySubsidyLimitMinor(settlement.scale()),
                                economySettings.twelveHourSubsidyLimitMinor(settlement.scale()),
                                Instant.now(), org.allivlisey.tianjitown.core.time.TownTime.ZONE);
                    }
                    pendingQuickShopPayments.submit(pending);
                }, (pending, exception) -> handleQuickShopTaxFailure(pending.tax, exception));
        this.pendingIncomeTaxes = new RetryingWorkQueue<>(asyncScheduler,
                20L * 5, 20L * 30, this::recordExternalIncomeTax,
                (pending, exception) -> handleExternalIncomeTaxFailure(pending.tax, exception));
        this.pendingIncomeSubsidyPayments = new RetryingWorkQueue<>(taxScheduler(true),
                20L * 5, 20L * 30, this::payExternalSubsidy,
                (pending, exception) -> plugin.getLogger().severe(plugin.messages().plainText(
                        EXTERNAL_SUBSIDY_SETTLEMENT_FAILURE,
                        Map.of("businessKey", safeText(pending.tax.businessKey()),
                                "detail", safeText(safeMessage(exception))))));
        this.pendingIncomeSubsidies = new RetryingWorkQueue<>(asyncScheduler,
                20L * 5, 20L * 30, pending -> {
                    if (pending.reservation == null) {
                        pending.reservation = finance.reserveTaxSubsidy(pending.tax.townId(),
                                pending.tax.businessKey(), pending.tax.taxMinor(),
                                economySettings.weeklySubsidyLimitMinor(settlement.scale()),
                                economySettings.twelveHourSubsidyLimitMinor(settlement.scale()),
                                Instant.now(), org.allivlisey.tianjitown.core.time.TownTime.ZONE);
                    }
                    pendingIncomeSubsidyPayments.submit(pending);
                }, (pending, exception) -> handleExternalIncomeTaxFailure(pending.tax, exception));
        this.incomeCollections = new TownIncomeTaxCollectionRuntime(plugin, finance, settlement,
                databaseAvailable, collection -> pendingIncomeSubsidies.submit(
                        new PendingIncomeSubsidy(collection.tax(), collection.operationId())),
                townId -> !archivedTowns.contains(townId));
    }

    private RetryingWorkQueue.Scheduler taxScheduler(boolean mainThread) {
        return new RetryingWorkQueue.Scheduler() {
            @Override
            public void executeAsync(Runnable task) {
                boolean accepted = mainThread ? plugin.runMain(task) : plugin.runAsync(task);
                if (!accepted) {
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
        };
    }

    private void payExternalSubsidy(PendingIncomeSubsidy pending) {
        if (pending.result == null) {
            try {
                pending.result = pending.reservation.grantedMinor() == 0
                        ? VaultSettlementService.Result.success("No subsidy payable")
                        : settlement.adjustSettlement(pending.reservation.grantedMinor());
            } catch (RuntimeException | LinkageError exception) {
                pending.result = VaultSettlementService.Result.failure(safeMessage(exception), false, true);
            }
            if (pending.result == null) {
                pending.result = VaultSettlementService.Result.failure("Missing subsidy response", false, true);
            }
        }
        // The tax already belongs to the town. A subsidy failure must not hold it hostage.
        // Preserve the external result across database retries; reconciliation never repeats Vault.
        pendingIncomeTaxes.submit(pending);
    }

    private static final class PendingIncomeSubsidy {
        private final EconomyRepository.ExternalIncomeTax tax;
        private EconomyRepository.SubsidyReservation reservation;
        private VaultSettlementService.Result result;
        private final UUID collectionId;

        private PendingIncomeSubsidy(EconomyRepository.ExternalIncomeTax tax, UUID collectionId) {
            this.tax = tax;
            this.collectionId = collectionId;
        }
    }

    QuickShopTaxAdapter.TaxPolicy taxPolicy(UUID receiverId) {
        QuickShopTaxAdapter.TaxPolicy policy = taxEnabled() ? taxPolicies.get(receiverId) : null;
        return policy != null && !archivedTowns.contains(policy.townId()) ? policy : null;
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

    void acceptQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        pendingQuickShopPreparations.submit(new PendingQuickShopTax(tax));
    }

    private void applyQuickShopSubsidy(PendingQuickShopTax pending) {
        if (pending.result == null) {
            try {
                pending.result = pending.reservation.grantedMinor() == 0
                        ? VaultSettlementService.Result.success("No subsidy payable")
                        : settlement.adjustSettlement(pending.reservation.grantedMinor());
            } catch (RuntimeException | LinkageError exception) {
                pending.result = VaultSettlementService.Result.failure(safeMessage(exception), false, true);
            }
            if (pending.result == null) {
                pending.result = VaultSettlementService.Result.failure("Missing subsidy response", false, true);
            }
        }
        // Even failed or uncertain subsidies must not discard the already collected tax.
        pendingTaxes.submit(pending);
    }

    private static final class PendingQuickShopTax {
        private final QuickShopTaxAdapter.SuccessfulTax tax;
        private EconomyRepository.SubsidyReservation reservation;
        private VaultSettlementService.Result result;

        private PendingQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
            this.tax = tax;
        }
    }

    void flushPendingTaxes() {
        incomeCollections.flush();
        pendingQuickShopPreparations.flush();
        pendingQuickShopPayments.flush();
        pendingTaxes.flush();
        pendingIncomeSubsidies.flush();
        pendingIncomeSubsidyPayments.flush();
        pendingIncomeTaxes.flush();
    }

    private void recordQuickShopTax(PendingQuickShopTax pending) {
        QuickShopTaxAdapter.SuccessfulTax tax = pending.tax;
        EconomyRepository.QuickShopTax record = new EconomyRepository.QuickShopTax(
                tax.townId(), tax.businessKey(), tax.shopId(), tax.shopType(),
                tax.receiverId(), tax.receiverName(), tax.interactingId(), tax.grossMinor(),
                tax.basisPoints(), tax.taxMinor(), tax.worldName());
        if (pending.result.success() && !pending.result.compensationRequired()) {
            finance.recordQuickShopTax(record);
        } else {
            if (!pending.result.compensationRequired()) {
                finance.cancelTaxSubsidy(tax.businessKey(), pending.result.message());
            }
            finance.recordQuickShopTaxWithoutSubsidy(record, pending.result.message());
            plugin.getLogger().severe(plugin.messages().plainText(
                    QUICK_SHOP_TAX_SUBSIDY_SETTLEMENT_FAILURE,
                    Map.of("detail", safeText(pending.result.message()))));
        }
        databaseAvailable.set(true);
    }

    EconomyRepository.SubsidyQuota taxSubsidyQuota(UUID townId) {
        return finance.taxSubsidyQuota(townId,
                economySettings.weeklySubsidyLimitMinor(settlement.scale()),
                economySettings.twelveHourSubsidyLimitMinor(settlement.scale()),
                Instant.now(), org.allivlisey.tianjitown.core.time.TownTime.ZONE);
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
        // The wage is already deposited. Persist the collection before asynchronously debiting tax.
        incomeCollections.collect(tax, earning.player());
        return JobsIncomeTaxAdapter.TaxResult.unchanged(earning.grossAmount());
    }

    void acceptGlobalMarketPlusIncomeTax(GlobalMarketPlusIncomeTaxAdapter.Earning earning) {
        EconomyRepository.ExternalIncomeTax tax = externalIncomeTax("GLOBALMARKETPLUS",
                earning.player(), earning.receiverName(), earning.grossAmount(),
                earning.businessKey());
        if (tax == null) {
            return;
        }
        incomeCollections.collect(tax, earning.player());
    }

    private EconomyRepository.ExternalIncomeTax externalIncomeTax(
            String source, org.bukkit.OfflinePlayer receiver, String receiverName, double gross,
            String businessKey) {
        if (!taxEnabled() || !Double.isFinite(gross) || gross <= 0) {
            return null;
        }
        QuickShopTaxAdapter.TaxPolicy policy = taxPolicy(receiver.getUniqueId());
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

    private void recordExternalIncomeTax(PendingIncomeSubsidy pending) {
        EconomyRepository.ExternalIncomeTax tax = pending.tax;
        if (pending.result.success() && !pending.result.compensationRequired()) {
            finance.recordExternalIncomeTax(tax);
        } else {
            finance.recordExternalIncomeTaxWithoutSubsidy(tax, pending.result.message());
            plugin.getLogger().severe(plugin.messages().plainText(
                    pending.result.compensationRequired() ? EXTERNAL_SUBSIDY_SETTLEMENT_AMBIGUOUS
                            : EXTERNAL_SUBSIDY_SETTLEMENT_FAILURE,
                    Map.of("businessKey", safeText(tax.businessKey()),
                            "detail", safeText(pending.result.message()))));
        }
        finance.markIncomeTaxRecorded(pending.collectionId);
        incomeCollections.recorded(pending.collectionId);
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

    void refreshTaxPolicies() {
        Map<UUID, QuickShopTaxAdapter.TaxPolicy> loaded = new java.util.HashMap<>();
        for (EconomyRepository.MemberTaxPolicy policy : finance.loadMemberTaxPolicies()) {
            loaded.put(policy.playerId(), new QuickShopTaxAdapter.TaxPolicy(policy.townId(),
                    policy.taxRateBps()));
        }
        synchronized (taxPolicyLock) {
            loaded.values().removeIf(policy -> archivedTowns.contains(policy.townId()));
            taxPolicies.clear();
            taxPolicies.putAll(loaded);
        }
    }

    void townArchived(UUID townId) {
        synchronized (taxPolicyLock) {
            archivedTowns.add(townId);
            taxPolicies.values().removeIf(policy -> policy.townId().equals(townId));
        }
    }

    void prepareStartupRecovery() { incomeCollections.prepareStartupRecovery(); }
    void recoverStartupState() { incomeCollections.recoverStartupState(); }
    void recordConfirmedIncomeTax(EconomyRepository.IncomeTaxCollection collection) {
        incomeCollections.recordConfirmedCollection(collection);
    }
    void resolveIncomeTaxCollection(org.bukkit.command.CommandSender sender,
            EconomyRepository.IncomeTaxCollection expected, boolean paid, String reason,
            java.util.function.Consumer<EconomyRepository.IncomeTaxCollection> completed) {
        incomeCollections.resolve(sender, expected, paid, reason, completed);
    }
    void refundIncomeTaxCollection(org.bukkit.command.CommandSender sender,
            EconomyRepository.IncomeTaxCollection expected,
            java.util.function.Consumer<EconomyRepository.IncomeTaxCollection> completed) {
        incomeCollections.refund(sender, expected, completed);
    }
}

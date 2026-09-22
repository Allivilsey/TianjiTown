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
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.task.RetryingWorkQueue;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Tax policy cache, player deductions and atomic town tax posting. */
final class TownTaxRuntime {
    private static final String QUICK_SHOP_TAX_LEDGER_WRITE_FAILURE =
            "log.quick-shop.tax-ledger-write-failure";
    private static final String EXTERNAL_INCOME_TAX_LEDGER_WRITE_FAILURE =
            "log.external-income-tax.ledger-write-failure";
    private final TianjiTownPlugin plugin;
    private final EconomyRepository finance;
    private final EconomySettings economySettings;
    private final VaultPlayerEconomyService wallet;
    private final AtomicBoolean databaseAvailable;
    private final Map<UUID, QuickShopTaxAdapter.TaxPolicy> taxPolicies = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> archivedTowns = ConcurrentHashMap.newKeySet();
    private final Object taxPolicyLock = new Object();
    private final TownIncomeTaxCollectionRuntime incomeCollections;
    private final RetryingWorkQueue<PendingQuickShopTax> pendingTaxes;
    private final RetryingWorkQueue<PendingIncomeSubsidy> pendingIncomeTaxes;
    private final AtomicBoolean quickShopTaxAvailable = new AtomicBoolean(false);
    private static final String SCHEDULER_LIFECYCLE_STOPPED = "log.scheduler.lifecycle-stopped";

    TownTaxRuntime(TianjiTownPlugin plugin,
            EconomyRepository finance,
            EconomySettings economySettings,
            VaultPlayerEconomyService wallet,
            AtomicBoolean databaseAvailable) {
        this.plugin = plugin;
        this.finance = finance;
        this.economySettings = economySettings;
        this.wallet = wallet;
        this.databaseAvailable = databaseAvailable;
        RetryingWorkQueue.Scheduler asyncScheduler = taxScheduler(false);
        this.pendingTaxes = new RetryingWorkQueue<>(asyncScheduler,
                20L * 5, 20L * 30, this::recordQuickShopTax,
                (pending, exception) -> handleQuickShopTaxFailure(pending.tax, exception));
        this.pendingIncomeTaxes = new RetryingWorkQueue<>(asyncScheduler,
                20L * 5, 20L * 30, this::recordExternalIncomeTax,
                (pending, exception) -> handleExternalIncomeTaxFailure(pending.tax, exception));
        this.incomeCollections = new TownIncomeTaxCollectionRuntime(plugin, finance, wallet,
                databaseAvailable, collection -> pendingIncomeTaxes.submit(
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

    private static final class PendingIncomeSubsidy {
        private final Instant receivedAt = Instant.now();
        private final EconomyRepository.ExternalIncomeTax tax;
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
        pendingTaxes.submit(new PendingQuickShopTax(tax));
    }

    private static final class PendingQuickShopTax {
        private final Instant receivedAt = Instant.now();
        private final QuickShopTaxAdapter.SuccessfulTax tax;

        private PendingQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
            this.tax = tax;
        }
    }

    void flushPendingTaxes() {
        incomeCollections.flush();
        pendingTaxes.flush();
        pendingIncomeTaxes.flush();
    }

    private void recordQuickShopTax(PendingQuickShopTax pending) {
        QuickShopTaxAdapter.SuccessfulTax tax = pending.tax;
        EconomyRepository.QuickShopTax record = new EconomyRepository.QuickShopTax(
                tax.townId(), tax.businessKey(), tax.shopId(), tax.shopType(),
                tax.receiverId(), tax.receiverName(), tax.interactingId(), tax.grossMinor(),
                tax.basisPoints(), tax.taxMinor(), tax.worldName());
        finance.recordQuickShopTax(record,
                economySettings.weeklySubsidyLimitMinor(wallet.scale()),
                economySettings.twelveHourSubsidyLimitMinor(wallet.scale()),
                pending.receivedAt, org.allivlisey.tianjitown.core.time.TownTime.ZONE);
        databaseAvailable.set(true);
    }

    EconomyRepository.SubsidyQuota taxSubsidyQuota(UUID townId) {
        return finance.taxSubsidyQuota(townId,
                economySettings.weeklySubsidyLimitMinor(wallet.scale()),
                economySettings.twelveHourSubsidyLimitMinor(wallet.scale()),
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
        long grossMinor = BigDecimal.valueOf(gross).movePointRight(wallet.scale())
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
        finance.recordExternalIncomeTax(tax,
                economySettings.weeklySubsidyLimitMinor(wallet.scale()),
                economySettings.twelveHourSubsidyLimitMinor(wallet.scale()),
                pending.receivedAt, org.allivlisey.tianjitown.core.time.TownTime.ZONE);
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

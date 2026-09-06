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
import org.bukkit.entity.Player;

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
    private final RetryingWorkQueue<QuickShopTaxAdapter.SuccessfulTax> pendingTaxes;
    private final RetryingWorkQueue<EconomyRepository.ExternalIncomeTax> pendingIncomeTaxes;
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
                20L * 5, 20L * 30, this::recordQuickShopTax, this::handleQuickShopTaxFailure);
        this.pendingIncomeTaxes = new RetryingWorkQueue<>(asyncScheduler,
                20L * 5, 20L * 30, this::recordExternalIncomeTax,
                this::handleExternalIncomeTaxFailure);
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
        // Preserve successful and ambiguous Vault results across queue retries.
        // Only definite failures may safely invoke Vault again.
        if (pending.result == null || (!pending.result.success()
                && !pending.result.compensationRequired())) {
            pending.result = pending.reservation.grantedMinor() == 0
                    ? VaultSettlementService.Result.success(plugin.messages().plainText(
                            QUICK_SHOP_TAX_SUBSIDY_QUOTA_EXHAUSTED))
                    : settlement.adjustSettlement(pending.reservation.grantedMinor());
        }
        if (pending.result.compensationRequired()) {
            // Keep the reservation for manual reconciliation; do not block unrelated payments.
            plugin.getLogger().severe(plugin.messages().plainText(EXTERNAL_SUBSIDY_SETTLEMENT_AMBIGUOUS,
                    Map.of("businessKey", safeText(pending.tax.businessKey()),
                            "detail", safeText(pending.result.message()))));
            return;
        }
        if (!pending.result.success()) {
            throw new IllegalStateException(pending.result.message());
        }
        pendingIncomeTaxes.submit(pending.tax);
    }

    private static final class PendingIncomeSubsidy {
        private final EconomyRepository.ExternalIncomeTax tax;
        private EconomyRepository.SubsidyReservation reservation;
        private VaultSettlementService.Result result;

        private PendingIncomeSubsidy(EconomyRepository.ExternalIncomeTax tax) {
            this.tax = tax;
        }
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

    void acceptQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        plugin.runAsync(() -> {
            try {
                EconomyRepository.SubsidyReservation reservation =
                        finance.reserveTaxSubsidy(tax.townId(), tax.businessKey(),
                                tax.taxMinor(), economySettings.weeklySubsidyLimitMinor(
                                        settlement.scale()),
                                economySettings.twelveHourSubsidyLimitMinor(settlement.scale()),
                                Instant.now(), org.allivlisey.tianjitown.core.time.TownTime.ZONE);
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
        plugin.runAsync(() -> finance.cancelTaxSubsidy(tax.businessKey(),
                subsidyDetail));
        plugin.getLogger().severe(plugin.messages().plainText(
                QUICK_SHOP_TAX_SUBSIDY_SETTLEMENT_FAILURE,
                Map.of("detail", safeText(subsidyDetail))));
    }

    void flushPendingTaxes() {
        pendingTaxes.flush();
        pendingIncomeSubsidies.flush();
        pendingIncomeSubsidyPayments.flush();
        pendingIncomeTaxes.flush();
    }

    private void recordQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        finance.recordQuickShopTax(new EconomyRepository.QuickShopTax(
                tax.townId(), tax.businessKey(), tax.shopId(), tax.shopType(),
                tax.receiverId(), tax.receiverName(), tax.interactingId(), tax.grossMinor(),
                tax.basisPoints(), tax.taxMinor(), tax.worldName()));
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
        VaultSettlementService.Result transferred = settlement.adjustSettlement(tax.taxMinor());
        if (!transferred.success()) {
            plugin.getLogger().severe(plugin.messages().plainText(
                    JOBS_INCOME_TAX_SETTLEMENT_FAILURE,
                    Map.of("detail", safeText(transferred.message()))));
            return JobsIncomeTaxAdapter.TaxResult.unchanged(earning.grossAmount());
        }
        pendingIncomeSubsidies.submit(new PendingIncomeSubsidy(tax));
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
        pendingIncomeSubsidies.submit(new PendingIncomeSubsidy(tax));
        Player receiver = earning.player().getPlayer();
        if (receiver != null) {
            plugin.messages().send(receiver, "chat.runtime.global-market-income", Map.of(
                    "gross", settlement.formatMinor(tax.grossMinor()), "tax", settlement.formatMinor(tax.taxMinor()),
                    "net", settlement.formatMinor(tax.grossMinor() - tax.taxMinor())));
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

    void refreshTaxPolicies() {
        Map<UUID, QuickShopTaxAdapter.TaxPolicy> loaded = new java.util.HashMap<>();
        for (EconomyRepository.MemberTaxPolicy policy : finance.loadMemberTaxPolicies()) {
            loaded.put(policy.playerId(), new QuickShopTaxAdapter.TaxPolicy(policy.townId(),
                    policy.taxRateBps()));
        }
        taxPolicies.clear();
        taxPolicies.putAll(loaded);
    }
}

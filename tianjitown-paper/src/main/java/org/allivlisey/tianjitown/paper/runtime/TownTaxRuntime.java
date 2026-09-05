package org.allivlisey.tianjitown.paper.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
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
    private static final String GLOBAL_MARKET_PLUS_SUBSIDY_SETTLEMENT_REFUNDED =
            "log.global-market-plus.subsidy-settlement-failure-refunded";
    private static final String GLOBAL_MARKET_PLUS_SUBSIDY_SETTLEMENT_REFUND_FAILED =
            "log.global-market-plus.subsidy-settlement-failure-refund-failed";
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
                        finance.reserveQuickShopSubsidy(tax.townId(), tax.businessKey(),
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

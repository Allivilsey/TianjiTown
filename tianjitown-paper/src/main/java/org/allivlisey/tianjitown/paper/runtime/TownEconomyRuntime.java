package org.allivlisey.tianjitown.paper.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.economy.DonationRefundCoordinator;
import org.allivlisey.tianjitown.paper.task.RetryingWorkQueue;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.actorId;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.percent;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

/** Donations, external settlement, compensation and tax-rate commands. */
final class TownEconomyRuntime {
    private static final String SETTLEMENT_BALANCE_READ_FAILURE =
            "log.settlement.balance-read-failure";
    private static final String SETTLEMENT_SHORTFALL = "log.settlement.shortfall";
    private static final String SETTLEMENT_RECONCILIATION_FAILURE =
            "log.settlement.reconciliation-failure";
    private static final String CONSUMPTION_PAUSED = "chat.runtime.consumption-paused";
    private static final String STORAGE_UNAVAILABLE = "chat.lifecycle.storage-unavailable";
    private static final String EXTERNAL_PREFLIGHT_FAILED =
            "chat.lifecycle.external-preflight-failed";
    private static final String EXTERNAL_OPERATION_FAILED = "chat.lifecycle.external-failed";
    private static final String REFUND_AUTO = "chat.lifecycle.refund-auto";
    private static final String MANUAL_REVIEW = "chat.lifecycle.manual-review";
    private static final String EXTERNAL_OPERATION_REFUND_AUTO =
            "log.external-operation.refund-auto";
    private static final String EXTERNAL_OPERATION_MANUAL_REVIEW =
            "log.external-operation.manual-review";
    private static final String TOWN_REQUIRED = "validation.territory.town-required";
    private static final String DONATION_OPERATION_REASON = "log.donation.operation-reason";
    private static final String TAX_RATE_CHANGE_REASON = "log.tax.rate-change-reason";
    private static final String DONATION_REFUND_RETRY_FAILED =
            "log.donation.refund-retry-failed";
    private static final String DONATION_REFUND_FINALIZATION_FAILED =
            "log.donation.refund-finalization-failed";
    private static final String DONATION_REFUND_RECOVERED =
            "log.donation.refund-recovered";
    private static final String DONATION_REFUND_EXHAUSTED =
            "log.donation.refund-exhausted";
    private static final String DONATION_SETTLEMENT_BALANCE_READ_FAILURE =
            "log.donation.settlement-balance-read-failure";
    private static final String DONATION_SETTLEMENT_SHORTFALL =
            "log.donation.settlement-shortfall";
    private static final String DONATION_SETTLEMENT_RECONCILIATION_FAILURE =
            "log.donation.settlement-reconciliation-failure";
    private final TianjiTownPlugin plugin;
    private final EconomyRepository finance;
    private final EconomySettings economySettings;
    private final VaultSettlementService settlement;
    private final AtomicBoolean databaseAvailable;
    private final TownRuntimeTasks tasks;
    private final TownTaxRuntime taxes;
    private final java.util.function.BooleanSupplier consumptionEnabled;
    private final DonationRefundCoordinator donationRefunds;
    private final RetryingWorkQueue<Finalization> finalizations;
    private Consumer<Player> taxChangeNotifier = player -> { };

    TownEconomyRuntime(TianjiTownPlugin plugin,
            EconomyRepository finance,
            EconomySettings economySettings,
            VaultSettlementService settlement,
            AtomicBoolean databaseAvailable,
            TownRuntimeTasks tasks,
            TownTaxRuntime taxes,
            java.util.function.BooleanSupplier consumptionEnabled) {
        this.plugin = plugin;
        this.finance = finance;
        this.economySettings = economySettings;
        this.settlement = settlement;
        this.databaseAvailable = databaseAvailable;
        this.tasks = tasks;
        this.taxes = taxes;
        this.consumptionEnabled = consumptionEnabled;
        this.donationRefunds = createDonationRefundCoordinator();
        this.finalizations = new RetryingWorkQueue<>(new RetryingWorkQueue.Scheduler() {
            @Override
            public void executeAsync(Runnable task) {
                if (!plugin.runAsync(task)) throw new java.util.concurrent.RejectedExecutionException();
            }

            @Override
            public void schedule(Runnable task, long delayTicks) {
                if (!plugin.runMainLater(task, delayTicks)) {
                    throw new java.util.concurrent.RejectedExecutionException();
                }
            }
        }, 20L * 5, 20L * 30, pending -> {
            EconomyRepository.LedgerMutation mutation = finance.completeOperation(pending.operationId);
            databaseAvailable.set(true);
            plugin.runMain(() -> pending.success.accept(mutation));
        }, (pending, exception) -> {
            tasks.markStorageFailure(exception);
            plugin.getLogger().severe(plugin.messages().plainText(
                    "log.external-operation.finalization-retry", Map.of(
                            "operation", pending.operationId, "detail", safeText(safeMessage(exception)))));
            if (pending.notified.compareAndSet(false, true)) {
                plugin.runMain(() -> plugin.messages().send(pending.sender,
                        "chat.lifecycle.external-finalization-pending"));
            }
        });
    }

    private DonationRefundCoordinator createDonationRefundCoordinator() {
        return new DonationRefundCoordinator(new DonationRefundCoordinator.Scheduler() {
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
                new DonationRefundCoordinator.Listener() {
                    @Override
                    public void retryFailed(EconomyRepository.EconomyOperation operation,
                                            int attempt, String detail) {
                        plugin.getLogger().warning(plugin.messages().plainText(
                                DONATION_REFUND_RETRY_FAILED, Map.of(
                                        "attempt", attempt,
                                        "operation", operation.operationId(),
                                        "detail", safeText(detail))));
                    }

                    @Override
                    public void finalizationFailed(EconomyRepository.EconomyOperation operation,
                                                   int attempt, String detail) {
                        plugin.getLogger().warning(plugin.messages().plainText(
                                DONATION_REFUND_FINALIZATION_FAILED, Map.of(
                                        "attempt", attempt,
                                        "operation", operation.operationId(),
                                        "detail", safeText(detail))));
                    }

                    @Override
                    public void recovered(EconomyRepository.EconomyOperation operation,
                                          int attempts) {
                        plugin.getLogger().info(plugin.messages().plainText(
                                DONATION_REFUND_RECOVERED, Map.of(
                                        "operation", operation.operationId(),
                                        "attempts", attempts)));
                        reconcileSettlementAfterRefund(operation);
                    }

                    @Override
                    public void exhausted(EconomyRepository.EconomyOperation operation,
                                          String detail) {
                        plugin.getLogger().severe(plugin.messages().plainText(
                                DONATION_REFUND_EXHAUSTED, Map.of(
                                        "operation", operation.operationId(),
                                        "detail", safeText(detail))));
                    }
                }, plugin.messages()::plainText);
    }

    private void reconcileSettlementAfterRefund(
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
                        plugin.runMain(() -> plugin.getLogger().severe(plugin.messages().plainText(
                                DONATION_SETTLEMENT_SHORTFALL, Map.of(
                                        "operation", operation.operationId(),
                                        "external", settlement.formatMinor(reconciliation.externalBalanceMinor()),
                                        "required", settlement.formatMinor(reconciliation.requiredMinor())))));
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

    void setTaxChangeNotifier(Consumer<Player> notifier) {
        this.taxChangeNotifier = java.util.Objects.requireNonNull(notifier, "notifier");
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
                    plugin.runMain(() -> plugin.getLogger().severe(plugin.messages().plainText(
                            SETTLEMENT_SHORTFALL, Map.of(
                                    "external", settlement.formatMinor(result.externalBalanceMinor()),
                                    "required", settlement.formatMinor(result.requiredMinor())))));
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
        if (!consumptionEnabled.getAsBoolean()) {
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
                        Map.of("balance", settlement.formatMinor(mutation.balanceAfterMinor()))));
    }

    void changeTaxRate(Player mayor, UUID townId, int basisPoints) {
        if (!taxes.taxEnabled()) {
            plugin.messages().send(mayor, "chat.runtime.tax-paused");
            return;
        }
        if (!economySettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(mayor);
            return;
        }
        tasks.write(mayor, () -> {
            EconomyRepository.TaxChange change = finance.changeTaxRate(townId,
                    mayor.getUniqueId(), basisPoints, mayor.getName(),
                    plugin.messages().plainText(TAX_RATE_CHANGE_REASON));
            taxes.refreshTaxPolicies();
            return change;
        }, change -> {
            plugin.messages().send(mayor, "chat.runtime.tax-changed", Map.of(
                    "rate", percent(change.basisPoints())));
            taxChangeNotifier.accept(mayor);
        });
    }

    void forceTaxRate(CommandSender sender, UUID townId, int basisPoints, String reason) {
        if (!economySettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(sender);
            return;
        }
        tasks.write(sender, () -> {
            EconomyRepository.TaxChange change = finance.forceTaxRate(townId,
                    actorId(sender), basisPoints, sender.getName(), reason);
            taxes.refreshTaxPolicies();
            return change;
        }, change -> plugin.messages().send(sender, "chat.runtime.tax-forced", Map.of(
                "rate", percent(change.basisPoints()))));
    }

    private void executeExternalOperation(CommandSender sender,
                                          Supplier<EconomyRepository.EconomyOperation> prepare,
                                          java.util.function.Function<EconomyRepository.EconomyOperation,
                                                  VaultSettlementService.Result> external,
                                          Consumer<EconomyRepository.LedgerMutation> success) {
        executeExternalOperation(sender, prepare, external, success,
                exception -> tasks.handleFailure(sender, exception));
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
                tasks.reportActionFailure(exception, failure);
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
                tasks.reportActionFailure(exception, failure);
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
        // Vault succeeded: retries must only post the idempotent database ledger, never debit again.
        finalizations.submit(new Finalization(operation.operationId(), sender, success));
    }

    private static final class Finalization {
        private final UUID operationId;
        private final CommandSender sender;
        private final Consumer<EconomyRepository.LedgerMutation> success;
        private final AtomicBoolean notified = new AtomicBoolean();

        private Finalization(UUID operationId, CommandSender sender,
                             Consumer<EconomyRepository.LedgerMutation> success) {
            this.operationId = operationId;
            this.sender = sender;
            this.success = success;
        }
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
                            && "DONATION".equals(operation.operationType())
                            && operation.actorId() != null;
                    if (automaticRefund) {
                        donationRefunds.submit(operation);
                        plugin.getLogger().warning(plugin.messages().plainText(
                                EXTERNAL_OPERATION_REFUND_AUTO,
                                Map.of("operation", safeText(operation.operationId()),
                                        "town", safeText(operation.townId()),
                                        "detail", safeText(result.message()))));
                    } else {
                        plugin.getLogger().severe(plugin.messages().plainText(
                                EXTERNAL_OPERATION_MANUAL_REVIEW,
                                Map.of("operation", safeText(operation.operationId()),
                                        "town", safeText(operation.townId()),
                                        "detail", safeText(result.message()))));
                    }
                } else {
                    finance.cancelOperation(operation.operationId(), result.message());
                }
                plugin.runMain(() -> failure.accept(
                        new IllegalStateException(result.message()
                                + refundHint(operation, result))));
            } catch (RuntimeException exception) {
                tasks.reportActionFailure(exception, failure);
            }
        });
    }

    private String refundHint(EconomyRepository.EconomyOperation operation,
                              VaultSettlementService.Result result) {
        if (!result.compensationRequired()) {
            return "";
        }
        String key = result.playerRefundRequired()
                && "DONATION".equals(operation.operationType())
                && operation.actorId() != null
                ? REFUND_AUTO : MANUAL_REVIEW;
        return plugin.messages().plainText(key);
    }

    private void sendTaxRangeError(CommandSender sender) {
        plugin.messages().send(sender, "chat.runtime.tax-range");
    }
}

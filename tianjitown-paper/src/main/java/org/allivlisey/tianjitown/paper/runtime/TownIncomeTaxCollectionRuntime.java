package org.allivlisey.tianjitown.paper.runtime;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.task.RetryingWorkQueue;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExternalIncomeTax;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.IncomeTaxCollection;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

/** Durable, at-most-once external tax collection; uncertain outcomes remain independently recoverable. */
final class TownIncomeTaxCollectionRuntime {
    private final TianjiTownPlugin plugin;
    private final EconomyRepository finance;
    private final VaultSettlementService settlement;
    private final AtomicBoolean databaseAvailable;
    private final Consumer<IncomeTaxCollection> collected;
    private final Predicate<UUID> townActive;
    private final RetryingWorkQueue<PendingCollection> preparations;
    private final RetryingWorkQueue<PendingCollection> payments;
    private final RetryingWorkQueue<PendingCollection> outcomes;
    private final RetryingWorkQueue<IncomeTaxCollection> recoveredTaxes;
    private List<IncomeTaxCollection> startupRecovery = List.of();
    private final TownRuntimeTasks tasks;
    private final Object lifecycle = new Object();
    private final java.util.Set<UUID> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final RetryingWorkQueue<PendingRefund> refundPayments;
    private final RetryingWorkQueue<PendingRefund> refundOutcomes;

    TownIncomeTaxCollectionRuntime(TianjiTownPlugin plugin, EconomyRepository finance,
                                   VaultSettlementService settlement, AtomicBoolean databaseAvailable,
                                   Consumer<IncomeTaxCollection> collected, Predicate<UUID> townActive) {
        this.plugin = plugin;
        this.finance = finance;
        this.settlement = settlement;
        this.databaseAvailable = databaseAvailable;
        this.collected = collected;
        this.townActive = townActive;
        this.tasks = new TownRuntimeTasks(plugin, databaseAvailable);
        preparations = new RetryingWorkQueue<>(scheduler(false), 100, 600, this::prepare,
                (pending, failure) -> failure(pending.tax, failure));
        payments = new RetryingWorkQueue<>(scheduler(true), 100, 600, this::pay,
                (pending, failure) -> failure(pending.tax, failure));
        outcomes = new RetryingWorkQueue<>(scheduler(false), 100, 600, this::recordOutcome,
                (pending, failure) -> failure(pending.tax, failure));
        recoveredTaxes = new RetryingWorkQueue<>(scheduler(false), 100, 600, pending -> {
            // A successful collection can survive a crash before the subsidy outcome was saved.
            // Recover only the tax; a reserved subsidy is left for explicit reconciliation.
            finance.recordExternalIncomeTaxWithoutSubsidy(pending.tax(),
                    "重启恢复已收税款；如有补贴预留，请核对补贴是否已支付");
            finance.markIncomeTaxRecorded(pending.operationId());
            databaseAvailable.set(true);
        }, (pending, failure) -> failure(pending.tax(), failure));
        refundOutcomes = new RetryingWorkQueue<>(scheduler(false), 100, 600, pending -> {
            if (pending.callbackDispatched) return;
            var result = pending.result;
            String status = result.success() ? "REFUNDED" : result.compensationRequired() ? "AMBIGUOUS" : "REFUND_REQUIRED";
            IncomeTaxCollection completed = finance.finishIncomeTaxRefund(pending.operation, status, result.message());
            active.remove(completed.operationId());
            databaseAvailable.set(true);
            if (!pending.callbackDispatched) {
                pending.callbackDispatched = true;
                plugin.runMain(() -> pending.completed.accept(completed));
            }
        }, (pending, failure) -> failure(pending.operation.tax(), failure));
        refundPayments = new RetryingWorkQueue<>(scheduler(true), 100, 600, pending -> {
            if (pending.outcomeDispatched) return;
            if (pending.result == null) {
                try {
                    pending.result = settlement.refundDebitedPlayer(plugin.getServer().getOfflinePlayer(
                            pending.operation.tax().receiverId()), pending.operation.tax().taxMinor());
                } catch (RuntimeException | LinkageError failure) {
                    pending.result = VaultSettlementService.Result.failure(RuntimeText.safeMessage(failure), false, true);
                }
                if (pending.result == null) pending.result = VaultSettlementService.Result.failure("Vault未返回退款结果", false, true);
            }
            pending.outcomeDispatched = true;
            refundOutcomes.submit(pending);
        }, (pending, failure) -> failure(pending.operation.tax(), failure));

    }

    void collect(ExternalIncomeTax tax, OfflinePlayer player) {
        preparations.submit(new PendingCollection(tax, player));
    }

    private void prepare(PendingCollection pending) {
        try {
            if (pending.operation == null) pending.operation = finance.prepareIncomeTaxCollection(pending.tax);
            if (!pending.operation.status().equals("PREPARED")) return;
            synchronized (lifecycle) {
                active.add(pending.operation.operationId());
                pending.operation = finance.claimIncomeTaxCollection(pending.operation);
            }
            if (pending.operation.status().equals("ATTEMPTED")) payments.submit(pending);
            else active.remove(pending.operation.operationId());
        } catch (EconomyRepository.ConflictException conflict) {
            // Changed membership or another recovery action must never schedule a fresh debit.
            failure(pending.tax, conflict);
            if (pending.operation != null) active.remove(pending.operation.operationId());
        }
    }

    private void pay(PendingCollection pending) {
        if (pending.outcomeDispatched) return;
        if (pending.result == null && !townActive.test(pending.tax.townId())) {
            pending.result = VaultSettlementService.Result.failure("小镇已归档，取消尚未扣款的收入税", false, false);
        }
        if (pending.result == null) {
            try {
                pending.result = settlement.transferFromPlayer(pending.player, pending.tax.taxMinor());
            } catch (RuntimeException | LinkageError failure) {
                pending.result = VaultSettlementService.Result.failure(RuntimeText.safeMessage(failure), false, true);
            }
            if (pending.result == null) {
                pending.result = VaultSettlementService.Result.failure("Vault未返回收税结果", false, true);
            }
        }
        pending.outcomeDispatched = true;
        outcomes.submit(pending);
    }

    private void recordOutcome(PendingCollection pending) {
        // Forwarding can enqueue the next stage before its scheduler rejects. Once dispatched,
        // that stage may already have marked RECORDED; never finish the old claim again.
        if (pending.successDispatched) return;
        VaultSettlementService.Result result = pending.result;
        String status = result.success() ? "SUCCEEDED"
                : result.playerRefundRequired() ? "REFUND_REQUIRED"
                : result.compensationRequired() ? "AMBIGUOUS" : "FAILED";
        IncomeTaxCollection completed = finance.finishIncomeTaxCollection(pending.operation, status, result.message());
        databaseAvailable.set(true);
        if (completed.status().equals("SUCCEEDED")) {
            if (!pending.successDispatched) {
                // submit enqueues before asking its scheduler; a rejected scheduler must not enqueue twice.
                pending.successDispatched = true;
                collected.accept(completed);
            }
        }
        else {
            active.remove(completed.operationId());
            plugin.getLogger().severe(plugin.messages().plainText("log.external-income-tax.collection-pending",
                    Map.of("businessKey", pending.tax.businessKey(), "status", completed.status(),
                            "detail", RuntimeText.safeText(result.message()))));
        }
    }

    /** Worker-side preparation, before event listeners can accept fresh collections. */
    void prepareStartupRecovery() {
        finance.recoverInterruptedIncomeTaxCollections();
        startupRecovery = List.copyOf(finance.pendingIncomeTaxCollections());
    }

    void recoverStartupState() {
        for (IncomeTaxCollection pending : startupRecovery) {
            if (pending.status().equals("SUCCEEDED")) recoveredTaxes.submit(pending);
        }
        startupRecovery = List.of();
    }

    void recordConfirmedCollection(IncomeTaxCollection confirmed) {
        if (!confirmed.status().equals("SUCCEEDED")) throw new IllegalArgumentException("税款尚未确认到账");
        recoveredTaxes.submit(confirmed);
    }

    void recorded(UUID operationId) { active.remove(operationId); }

    void resolve(CommandSender sender, IncomeTaxCollection expected, boolean paid, String reason,
                 Consumer<IncomeTaxCollection> completed) {
        tasks.write(sender, () -> {
            synchronized (lifecycle) {
                requireInactive(expected);
                IncomeTaxCollection resolved = finance.resolveIncomeTaxCollection(expected, paid,
                        RuntimeText.actorId(sender), sender.getName(), reason);
                if (resolved.status().equals("SUCCEEDED")) {
                    finance.recordExternalIncomeTaxWithoutSubsidy(resolved.tax(),
                            "管理员已核实收税到账；补贴需另行核实");
                    return finance.markIncomeTaxRecorded(resolved.operationId());
                }
                return resolved;
            }
        }, completed);
    }

    void refund(CommandSender sender, IncomeTaxCollection expected, Consumer<IncomeTaxCollection> completed) {
        tasks.write(sender, () -> {
            synchronized (lifecycle) {
                requireInactive(expected);
                IncomeTaxCollection claimed = finance.claimIncomeTaxRefund(expected, RuntimeText.actorId(sender), sender.getName());
                active.add(claimed.operationId());
                return claimed;
            }
        }, claimed -> refundPayments.submit(new PendingRefund(claimed, completed)));
    }

    private void requireInactive(IncomeTaxCollection expected) {
        if (active.contains(expected.operationId())) {
            throw new IllegalStateException("该税款仍在收取、退款或补贴结算，不能同时人工核实");
        }
    }

    void flush() {
        preparations.flush();
        payments.flush();
        outcomes.flush();
        recoveredTaxes.flush();
        refundPayments.flush();
        refundOutcomes.flush();
    }

    private void failure(ExternalIncomeTax tax, RuntimeException failure) {
        if (failure instanceof EconomyRepository.StorageUnavailableException) databaseAvailable.set(false);
        plugin.getLogger().severe(plugin.messages().plainText("log.external-income-tax.collection-failure",
                Map.of("source", tax.source(), "businessKey", tax.businessKey(),
                        "detail", RuntimeText.safeText(RuntimeText.safeMessage(failure)))));
    }

    private RetryingWorkQueue.Scheduler scheduler(boolean main) {
        return new RetryingWorkQueue.Scheduler() {
            @Override public void executeAsync(Runnable task) {
                if (!(main ? plugin.runMain(task) : plugin.runAsync(task))) {
                    throw new java.util.concurrent.RejectedExecutionException("Runtime stopped during income-tax collection");
                }
            }
            @Override public void schedule(Runnable task, long delayTicks) {
                if (!plugin.runMainLater(task, delayTicks)) {
                    throw new java.util.concurrent.RejectedExecutionException("Runtime stopped during income-tax collection");
                }
            }
        };
    }

    private static final class PendingCollection {
        private final ExternalIncomeTax tax;
        private final OfflinePlayer player;
        private IncomeTaxCollection operation;
        private VaultSettlementService.Result result;
        private boolean outcomeDispatched;
        private boolean successDispatched;
        private PendingCollection(ExternalIncomeTax tax, OfflinePlayer player) {
            this.tax = tax;
            this.player = player;
        }
    }

    private static final class PendingRefund {
        private final IncomeTaxCollection operation;
        private final Consumer<IncomeTaxCollection> completed;
        private VaultSettlementService.Result result;
        private boolean outcomeDispatched;
        private boolean callbackDispatched;
        private PendingRefund(IncomeTaxCollection operation, Consumer<IncomeTaxCollection> completed) {
            this.operation = operation;
            this.completed = completed;
        }
    }
}

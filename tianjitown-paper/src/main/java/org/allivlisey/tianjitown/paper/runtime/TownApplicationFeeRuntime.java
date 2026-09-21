package org.allivlisey.tianjitown.paper.runtime;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.ProvisionCoordinator;
import org.allivlisey.tianjitown.paper.land.ProvisionResult;
import org.allivlisey.tianjitown.storage.town.*;
import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Only the claimed operation may cross the Vault boundary; retries of persistence never pay again. */
public final class TownApplicationFeeRuntime {
    private final TianjiTownPlugin plugin;
    private final TownRepository repository;
    private final VaultSettlementService settlement;
    private final ProvisionCoordinator provisions;

    public TownApplicationFeeRuntime(TianjiTownPlugin plugin, TownRepository repository,
            VaultSettlementService settlement, ProvisionCoordinator provisions) {
        this.plugin = plugin;
        this.repository = repository;
        this.settlement = settlement;
        this.provisions = provisions;
    }

    void collectWhileLocked(CommandSender sender, ApplicationSnapshot application, long amount,
            UUID actor, String actorName, Runnable collected, Consumer<ProvisionResult> failure) {
        async(() -> {
            var claim = repository.claimApplicationFeeCollection(application.id(), application.version(),
                    amount, actor, actorName);
            main(() -> execute(claim, actor, actorName, operation -> {
                if (operation.state() == State.ESCROWED) collected.run();
                else failure.accept(failure(application, operation.state() + "：" + operation.detail()));
            }, failure), failure);
        }, failure);
    }

    public void refund(CommandSender sender, UUID applicationId, Consumer<ProvisionResult> callback) {
        Consumer<ProvisionResult> completion = lock(applicationId, callback);
        if (completion != null) refundWhileLocked(sender, applicationId, completion);
    }

    public void refund(CommandSender sender, UUID applicationId, long expectedVersion, Consumer<ProvisionResult> callback) {
        Consumer<ProvisionResult> completion = lock(applicationId, callback);
        if (completion != null) refundWhileLocked(sender, applicationId, expectedVersion, completion);
    }

    void refundWhileLocked(CommandSender sender, UUID applicationId, Consumer<ProvisionResult> completion) {
        refundWhileLocked(sender, applicationId, Long.MIN_VALUE, completion);
    }

    private void refundWhileLocked(CommandSender sender, UUID applicationId, long expectedVersion,
            Consumer<ProvisionResult> completion) {
        UUID actor = actorId(sender);
        String actorName = sender.getName();
        async(() -> {
            var claim = expectedVersion == Long.MIN_VALUE
                    ? repository.claimApplicationFeeRefund(applicationId, actor, actorName)
                    : repository.claimApplicationFeeRefund(applicationId, actor, actorName, expectedVersion);
            main(() -> execute(claim, actor, actorName, operation -> {
                if (operation.state() == State.REFUNDED || operation.state() == State.UNPAID) {
                    async(() -> {
                        var application = repository.findApplication(applicationId).orElseThrow();
                        main(() -> completion.accept(ProvisionResult.existing(application)), completion);
                    }, completion);
                } else completion.accept(failure(null, operation.state() + "：" + operation.detail()));
            }, completion), completion);
        }, completion);
    }

    public void resolve(CommandSender sender, UUID applicationId, long expectedVersion,
            Resolution resolution, String reason, Consumer<ProvisionResult> callback) {
        Consumer<ProvisionResult> completion = lock(applicationId, callback);
        if (completion == null) return;
        UUID actor = actorId(sender);
        String actorName = sender.getName();
        async(() -> {
            repository.resolveApplicationFee(applicationId, expectedVersion, resolution, actor, actorName, reason);
            var application = repository.findApplication(applicationId).orElseThrow();
            main(() -> completion.accept(ProvisionResult.existing(application)), completion);
        }, completion);
    }

    private void execute(ApplicationFeeOperation claim, UUID actor, String actorName,
            Consumer<ApplicationFeeOperation> success, Consumer<ProvisionResult> failure) {
        Outcome outcome;
        String detail;
        try {
            var player = plugin.getServer().getOfflinePlayer(claim.applicantId());
            var result = switch (claim.state()) {
                case COLLECTING -> settlement.transferFromPlayer(player, claim.amountMinor());
                case PLAYER_REFUNDING -> settlement.refundDebitedPlayer(player, claim.amountMinor());
                case REFUNDING -> settlement.transferToPlayer(player, claim.amountMinor());
                default -> throw new IllegalStateException("未认领的资金操作");
            };
            outcome = result.success() ? Outcome.SUCCESS
                    : result.playerRefundRequired() ? Outcome.PLAYER_REFUND_REQUIRED
                    : result.compensationRequired() ? Outcome.UNKNOWN : Outcome.FAILED;
            detail = result.message();
        } catch (RuntimeException | LinkageError exception) {
            outcome = Outcome.UNKNOWN;
            detail = RuntimeText.safeMessage(exception);
        }
        persist(claim, outcome, detail, actor, actorName, success, failure, 3);
    }

    private void persist(ApplicationFeeOperation claim, Outcome outcome, String detail,
            UUID actor, String actorName, Consumer<ApplicationFeeOperation> success,
            Consumer<ProvisionResult> failure, int attempts) {
        if (!plugin.runAsync(() -> {
            ApplicationFeeOperation completed;
            try {
                completed = repository.completeApplicationFeeOperation(claim, outcome, detail, actor, actorName);
            } catch (RuntimeException exception) {
                // This retries the known result only. The external payment is never called here.
                if (attempts > 1 && exception instanceof TownRepository.StorageUnavailableException) {
                    persist(claim, outcome, detail, actor, actorName, success, failure, attempts - 1);
                } else {
                    plugin.getLogger().severe("Application fee result requires reconciliation: application="
                            + claim.applicationId() + " operation=" + claim.state() + " outcome=" + outcome
                            + " detail=" + detail + " error=" + RuntimeText.safeMessage(exception));
                    main(() -> failure.accept(failure(null,
                            "付款结果写入失败；已保留操作认领，禁止重复付款。" + RuntimeText.safeMessage(exception))), failure);
                }
                return;
            }
            main(() -> success.accept(completed), failure);
        })) failure.accept(failure(null, "插件正在停止，已认领的资金操作需要核实"));
    }

    private Consumer<ProvisionResult> lock(UUID id, Consumer<ProvisionResult> callback) {
        if (!provisions.tryBegin(id)) {
            callback.accept(ProvisionResult.busy("该申请正在处理，请等待当前操作结束"));
            return null;
        }
        AtomicBoolean delivered = new AtomicBoolean();
        return result -> {
            if (!delivered.compareAndSet(false, true)) return;
            provisions.finish(id);
            callback.accept(result);
        };
    }

    private void async(Runnable task, Consumer<ProvisionResult> completion) {
        if (!plugin.runAsync(() -> guarded(task, completion))) completion.accept(failure(null, "插件正在停止，请稍后重试"));
    }

    private void main(Runnable task, Consumer<ProvisionResult> completion) {
        if (!plugin.runMain(() -> guarded(task, completion))) completion.accept(failure(null, "插件正在停止，请稍后查询资金状态"));
    }

    private void guarded(Runnable task, Consumer<ProvisionResult> completion) {
        try { task.run(); }
        catch (RuntimeException | LinkageError exception) {
            if (!plugin.runMain(() -> completion.accept(failure(null, RuntimeText.safeMessage(exception)))))
                completion.accept(failure(null, RuntimeText.safeMessage(exception)));
        }
    }

    private static ProvisionResult failure(ApplicationSnapshot application, String detail) {
        return ProvisionResult.failure(application, RuntimeText.safeText(detail),
                "使用 /tianjitown application fee list 或 inspect 查询；明确待退款可 retry，结果不明须核账后 resolve");
    }

    private static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
    }
}

package org.allivlisey.tianjitown.paper.command;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.ProvisionResult;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.*;

/** Reconciliation is explicit, version checked, audited, and never repeats a payment. */
public final class TownAdminApplicationFeeCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    public TownAdminApplicationFeeCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    @Command("tianjitown application fee list")
    @Usage("/tianjitown application fee list")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void list(CommandSender sender, TownRuntime runtime) {
        runtime.read(sender, () -> runtime.repository().pendingApplicationFees(100), operations -> {
            facade.send(sender, "chat.admin.application-fee-list", Map.of("count", operations.size()));
            operations.forEach(operation -> show(sender, runtime, operation));
            facade.send(sender, "chat.admin.application-fee-help");
        });
    }

    @Command("tianjitown application fee inspect")
    @Usage("/tianjitown application fee inspect <申请UUID>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void inspect(CommandSender sender, TownRuntime runtime, UUID applicationId) {
        runtime.read(sender, () -> runtime.repository().applicationFeeOperation(applicationId), operation -> {
            show(sender, runtime, operation);
            facade.send(sender, "chat.admin.application-fee-help");
        });
    }

    @Command("tianjitown application fee retry")
    @Usage("/tianjitown application fee retry <申请UUID>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void retry(CommandSender sender, TownRuntime runtime, UUID applicationId) {
        runtime.read(sender, () -> runtime.repository().applicationFeeOperation(applicationId), operation -> {
            show(sender, runtime, operation);
            facade.requestConfirmation(sender, plugin.messages().text("chat.admin.application-fee-refund-confirmation",
                    Map.of("id", applicationId, "amount", runtime.wallet().formatMinor(operation.amountMinor()),
                            "state", operation.state())),
                    () -> {
                        facade.requireRecoveryAccess(sender, runtime);
                        runtime.applicationFees().refund(sender, applicationId, operation.version(), result -> showResult(sender, result));
                    });
        });
    }

    @Command("tianjitown application fee resolve")
    @Usage("/tianjitown application fee resolve <申请UUID> <核实结论> <核实依据>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void resolve(CommandSender sender, TownRuntime runtime, UUID applicationId,
            @Single @Suggest({"COLLECTED", "NO_PAYMENT", "PLAYER_DEBIT_ONLY", "REFUNDED", "REFUND_NOT_PAID"}) String resolution,
            String reason) {
        var selected = ApplicationFeeOperation.Resolution.valueOf(resolution.toUpperCase(Locale.ROOT));
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("必须填写核实依据");
        runtime.read(sender, () -> runtime.repository().applicationFeeOperation(applicationId), operation -> {
            show(sender, runtime, operation);
            facade.send(sender, "chat.admin.application-fee-help");
            facade.requestConfirmation(sender, plugin.messages().text("chat.admin.application-fee-resolve-confirmation",
                    Map.of("id", applicationId, "state", operation.state(), "resolution", selected,
                            "version", operation.version(), "reason", TownAdminCommand.safeText(reason))),
                    () -> {
                        facade.requireRecoveryAccess(sender, runtime);
                        runtime.applicationFees().resolve(sender, applicationId, operation.version(), selected,
                                reason, result -> showResult(sender, result));
                    });
        });
    }

    private void show(CommandSender sender, TownRuntime runtime, ApplicationFeeOperation operation) {
        facade.send(sender, "chat.admin.application-fee-entry", Map.of("id", operation.applicationId(),
                "player", operation.applicantId(), "amount", runtime.wallet().formatMinor(operation.amountMinor()),
                "state", operation.state(), "version", operation.version(),
                "detail", TownAdminCommand.safeText(operation.detail())));
    }

    private void showResult(CommandSender sender, ProvisionResult result) {
        facade.send(sender, "chat.admin.application-fee-result", Map.of("state", result.status(),
                "detail", result.status() == ProvisionResult.Status.SUCCESS ? "资金状态已更新" : result.detail(plugin.messages()),
                "action", result.recoveryAction(plugin.messages())));
    }
}

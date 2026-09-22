package org.allivlisey.tianjitown.paper.command;

import java.util.Map;
import java.util.UUID;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Suggest;
import revxrsal.commands.annotation.Usage;

/** Manual reconciliation records externally verified outcomes; it never calls Vault. */
public final class TownAdminRecoveryCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    public TownAdminRecoveryCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    @Command("tianjitown money pending")
    @Usage("/tianjitown money pending")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void pending(CommandSender sender, TownRuntime runtime) {
        runtime.read(sender, () -> runtime.finance().pendingOperations(), operations -> {
            facade.send(sender, "chat.admin.recovery-list", Map.of("count", operations.size()));
            operations.forEach(operation -> show(sender, runtime, operation));
            facade.send(sender, "chat.admin.recovery-help");
        });
    }

    @Command("tianjitown money resolve")
    @Usage("/tianjitown money resolve <操作UUID> <applied|cancelled> <核实依据>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void resolve(CommandSender sender, TownRuntime runtime, UUID operationId,
                        @Single @Suggest({"applied", "cancelled"}) String resolution, String reason) {
        boolean applied = choice(resolution, "applied");
        requireReason(reason);
        runtime.read(sender, () -> runtime.finance().pendingOperations().stream()
                .filter(operation -> operation.operationId().equals(operationId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到待核实的资金操作，请重新查询")), operation -> {
            show(sender, runtime, operation);
            facade.requestConfirmation(sender, plugin.messages().text("chat.admin.recovery-confirmation", Map.of(
                    "id", operationId, "state", operation.status(), "resolution", applied ? "applied" : "cancelled",
                    "reason", TownAdminCommand.safeText(reason))), () -> {
                facade.requireRecoveryAccess(sender, runtime);
                runtime.resolveEconomyOperation(sender, operation, applied, reason, resolved ->
                        facade.send(sender, "chat.admin.recovery-result", Map.of(
                                "id", resolved.operationId(), "state", resolved.status())));
            });
        });
    }

    @Command("tianjitown money subsidy pending")
    @Usage("/tianjitown money subsidy pending")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void subsidies(CommandSender sender, TownRuntime runtime) {
        runtime.read(sender, () -> runtime.finance().pendingTaxSubsidies(100), reservations -> {
            facade.send(sender, "chat.admin.recovery-list", Map.of("count", reservations.size()));
            reservations.forEach(reservation -> showSubsidy(sender, runtime, reservation));
            facade.send(sender, "chat.admin.subsidy-recovery-help");
        });
    }

    @Command("tianjitown money subsidy resolve")
    @Usage("/tianjitown money subsidy resolve <业务键> <paid|cancelled> <核实依据>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void resolveSubsidy(CommandSender sender, TownRuntime runtime, @Single String businessKey,
            @Single @Suggest({"paid", "cancelled"}) String resolution, String reason) {
        boolean paid = choice(resolution, "paid");
        requireReason(reason);
        runtime.read(sender, () -> runtime.finance().pendingTaxSubsidies(100).stream()
                .filter(reservation -> reservation.businessKey().equals(businessKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到待核实的补贴，请重新查询")), reservation -> {
            showSubsidy(sender, runtime, reservation);
            if (!reservation.taxRecorded() || reservation.lastError() == null || reservation.lastError().isBlank()) {
                facade.send(sender, "chat.admin.subsidy-recovery-running");
                return;
            }
            facade.requestConfirmation(sender, plugin.messages().text("chat.admin.recovery-confirmation", Map.of(
                    "id", TownAdminCommand.safeText(businessKey), "state", reservation.status(),
                    "resolution", paid ? "paid" : "cancelled", "reason", TownAdminCommand.safeText(reason))), () -> {
                facade.requireRecoveryAccess(sender, runtime);
                runtime.write(sender, () -> runtime.finance().resolveTaxSubsidy(reservation, paid,
                        TownAdminCommand.actorId(sender), sender.getName(), reason), result -> {
                    facade.send(sender, "chat.admin.recovery-result", Map.of(
                            "id", TownAdminCommand.safeText(businessKey), "state", paid ? "APPLIED" : "CANCELLED"));
                });
            });
        });
    }

    private void show(CommandSender sender, TownRuntime runtime, EconomyRepository.EconomyOperation operation) {
        facade.send(sender, "chat.admin.recovery-entry", Map.of("id", operation.operationId(),
                "town", operation.townId(), "type", operation.operationType(), "state", operation.status(),
                "amount", runtime.wallet().formatMinor(operation.amountMinor()),
                "detail", TownAdminCommand.safeText(operation.lastError())));
    }

    @Command("tianjitown money tax pending")
    @Usage("/tianjitown money tax pending")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void incomeTaxes(CommandSender sender, TownRuntime runtime) {
        runtime.read(sender, () -> runtime.finance().pendingIncomeTaxCollections(), collections -> {
            facade.send(sender, "chat.admin.recovery-list", Map.of("count", collections.size()));
            collections.forEach(collection -> showIncomeTax(sender, runtime, collection));
            facade.send(sender, "chat.admin.income-tax-recovery-help");
        });
    }

    @Command("tianjitown money tax resolve")
    @Usage("/tianjitown money tax resolve <操作UUID> <paid|cancelled> <核实依据>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void resolveIncomeTax(CommandSender sender, TownRuntime runtime, UUID operationId,
            @Single @Suggest({"paid", "cancelled"}) String resolution, String reason) {
        boolean paid = choice(resolution, "paid");
        requireReason(reason);
        runtime.read(sender, () -> findIncomeTax(runtime, operationId), collection -> {
            showIncomeTax(sender, runtime, collection);
            facade.requestConfirmation(sender, plugin.messages().text("chat.admin.recovery-confirmation", Map.of(
                    "id", operationId, "state", collection.status(), "resolution", paid ? "paid" : "cancelled",
                    "reason", TownAdminCommand.safeText(reason))), () -> {
                facade.requireRecoveryAccess(sender, runtime);
                runtime.resolveIncomeTaxCollection(sender, collection, paid, reason,
                        result -> showIncomeTax(sender, runtime, result));
            });
        });
    }

    @Command("tianjitown money tax refund")
    @Usage("/tianjitown money tax refund <操作UUID>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void refundIncomeTax(CommandSender sender, TownRuntime runtime, UUID operationId) {
        runtime.read(sender, () -> findIncomeTax(runtime, operationId), collection -> {
            showIncomeTax(sender, runtime, collection);
            facade.requestConfirmation(sender, plugin.messages().text("chat.admin.income-tax-refund-confirmation", Map.of(
                    "id", operationId, "amount", runtime.wallet().formatMinor(collection.tax().taxMinor()),
                    "player", collection.tax().receiverId(), "state", collection.status())), () -> {
                facade.requireRecoveryAccess(sender, runtime);
                runtime.refundIncomeTaxCollection(sender, collection,
                        result -> showIncomeTax(sender, runtime, result));
            });
        });
    }

    private EconomyRepository.IncomeTaxCollection findIncomeTax(TownRuntime runtime, UUID id) {
        return runtime.finance().pendingIncomeTaxCollections().stream()
                .filter(collection -> collection.operationId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到待处理税款，请重新查询"));
    }

    private void showIncomeTax(CommandSender sender, TownRuntime runtime, EconomyRepository.IncomeTaxCollection collection) {
        facade.send(sender, "chat.admin.income-tax-recovery-entry", Map.of(
                "id", collection.operationId(), "town", collection.tax().townId(),
                "player", collection.tax().receiverId(), "source", collection.tax().source(),
                "amount", runtime.wallet().formatMinor(collection.tax().taxMinor()),
                "state", collection.status(), "version", collection.version(),
                "detail", TownAdminCommand.safeText(collection.lastError())));
    }

    private void showSubsidy(CommandSender sender, TownRuntime runtime, EconomyRepository.TaxSubsidyRecovery reservation) {
        facade.send(sender, "chat.admin.subsidy-recovery-entry", Map.of(
                "id", TownAdminCommand.safeText(reservation.businessKey()), "town", reservation.townId(),
                "tax", runtime.wallet().formatMinor(reservation.taxMinor()),
                "subsidy", runtime.wallet().formatMinor(reservation.subsidyMinor()),
                "state", reservation.status(), "recorded", reservation.taxRecorded(),
                "detail", TownAdminCommand.safeText(reservation.lastError())));
    }

    private static boolean choice(String value, String yes) {
        if (yes.equalsIgnoreCase(value)) return true;
        if ("cancelled".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("核实结论必须为 " + yes + " 或 cancelled");
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("必须填写核实依据");
    }
}

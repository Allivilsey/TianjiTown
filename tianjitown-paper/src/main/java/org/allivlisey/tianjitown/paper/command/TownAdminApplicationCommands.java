package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.command.CommandSender;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Usage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Handles administrator application review and town inspection or deletion. */
public final class TownAdminApplicationCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    public TownAdminApplicationCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    @Command("tianjitown application list")
    @Usage("/tianjitown application list")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void listApplications(CommandSender sender, TownRuntime runtime) {
        runtime.read(sender, () -> runtime.repository().listReviewQueue(100),
                applications -> plugin.townUi().showAdminApplicationList(sender, applications));
    }

    @Command("tianjitown application delate")
    @Usage("/tianjitown application delate <小镇代码|申请UUID>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void forceDeleteApplication(CommandSender sender, TownRuntime runtime,
            @revxrsal.commands.annotation.Single String townCode) {
        UUID actorId = TownAdminCommand.actorId(sender);
        String actorName = sender.getName();
        runtime.write(sender, () -> runtime.repository().forceDeleteApplication(
                townCode, actorId, actorName, "管理员强制删除建镇申请（保留历史，不退款）"),
                application -> facade.send(sender, "chat.admin.application-cancelled-without-cooldown",
                        Map.of("code", application.text().residenceName(), "id", application.id())));
    }

    @Command("tianjitown application clearcd")
    @Usage("/tianjitown application clearcd <玩家名|UUID>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void clearApplicationCooldown(CommandSender sender, TownRuntime runtime,
            @revxrsal.commands.annotation.Single String player) {
        UUID playerId = TownAdminCommand.playerId(player);
        UUID actorId = TownAdminCommand.actorId(sender);
        String actorName = sender.getName();
        runtime.write(sender, () -> runtime.repository().clearApplicationCooldown(playerId, actorId, actorName),
                count -> facade.send(sender, "chat.admin.application-cooldown-cleared",
                        Map.of("player", TownAdminCommand.safeText(player), "count", count)));
    }

    @Command("tianjitown town list")
    @Usage("/tianjitown town list [active|provisioning|archived]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void listTowns(CommandSender sender, TownRuntime runtime,
            @revxrsal.commands.annotation.Default("active") @revxrsal.commands.annotation.Single
            @revxrsal.commands.annotation.Suggest({"active", "provisioning", "archived"}) String status) {
        org.allivlisey.tianjitown.core.town.TownStatus selected;
        try { selected = org.allivlisey.tianjitown.core.town.TownStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("状态必须为 active、provisioning 或 archived"); }
        runtime.read(sender, () -> runtime.repository().listTowns(selected), towns -> {
            facade.send(sender, "chat.admin.town-list-title", Map.of("count", towns.size()));
            if (towns.isEmpty()) {
                facade.send(sender, "chat.admin.town-list-empty");
                return;
            }
            for (TownSnapshot town : towns) {
                facade.send(sender, "chat.admin.town-list-entry", Map.of(
                        "town", TownAdminCommand.safeText(town.profile().name()),
                        "code", TownAdminCommand.safeText(town.profile().residenceName()),
                        "status", town.status()));
            }
        });
    }

    @Command("tianjitown town view")
    @Usage("/tianjitown town view <小镇代码>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void viewTown(CommandSender sender, TownRuntime runtime, String input) {
        String townName = input.strip();
        runtime.read(sender, () -> facade.requireTown(runtime, townName), town -> {
            facade.send(sender, "chat.admin.town-title", Map.of("town", town.profile().name(),
                    "code", town.profile().residenceName()));
            facade.send(sender, "chat.admin.town-status", Map.of("status", town.status(),
                    "mayor", town.mayorId(), "version", town.version()));
            if (town.territory() != null) {
                facade.send(sender, "chat.admin.town-territory", Map.of(
                        "world", town.territory().center().worldName(),
                        "x", town.territory().center().x(),
                        "z", town.territory().center().z(),
                        "residence", town.residenceName(),
                        "projection", town.projectionStatus()));
            }
        });
    }

    @Command("tianjitown town delete")
    @Usage("/tianjitown town delete <小镇代码> [原因]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void deleteTownCommand(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> {
            List<TownSnapshot> candidates = runtime.repository().listReservedTowns();
            TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(input.split(" "), 0,
                    TownAdminCommand.townCodes(candidates), plugin.messages()::plainText);
            TownSnapshot target = candidates.stream()
                    .filter(candidate -> TownAdminCommand.sameCode(candidate.profile().residenceName(), parsed.townName()))
                    .findFirst().orElseThrow(() -> facade.messageArgument(
                            "chat.admin.town-not-found",
                            Map.of("town", TownAdminCommand.safeText(parsed.townName()))));
            return new TownDeleteRequest(target.id(), target.profile().name(), target.version(),
                    parsed.reason());
        }, request -> facade.requestConfirmation(sender,
                plugin.messages().text("chat.admin.town-delete-confirmation",
                        Map.of("town", TownAdminCommand.safeText(request.townName()))),
                () -> deleteTown(sender, runtime, request)));
    }

    private void deleteTown(CommandSender sender, TownRuntime runtime,
                            TownDeleteRequest request) {
        runtime.write(sender, () -> {
            return runtime.deleteTownGuarded(request.townId(), request.version(),
                    TownAdminCommand.actorId(sender), sender.getName(), request.reason());
        }, deleted -> {
            runtime.townArchived(deleted);
            LandProtectionService.Result result = runtime.landProtection().remove(
                    deleted.residenceName(), deleted.territory());
            if (result.success()) {
                runtime.write(sender, () -> {
                    runtime.repository().completeTownDeletion(deleted.id(), TownAdminCommand.actorId(sender),
                            sender.getName(), request.reason());
                    return deleted;
                }, completed -> facade.send(sender, "chat.admin.town-deleted", Map.of(
                        "town", completed.profile().name(), "detail",
                        LandProtectionMessages.detail(plugin.messages(), result))));
            } else {
                facade.send(sender, "chat.admin.town-delete-residence-failed", Map.of(
                        "detail", LandProtectionMessages.detail(plugin.messages(), result)));
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.admin.town-delete-residence-failure", Map.of(
                                "town", TownAdminCommand.safeText(deleted.profile().name()),
                                "residence", TownAdminCommand.safeText(deleted.residenceName()),
                                "detail", TownAdminCommand.safeText(LandProtectionMessages.detail(
                                        plugin.messages(), result)))));
            }
        });
    }

    private record TownDeleteRequest(UUID townId, String townName, long version, String reason) {
    }
}

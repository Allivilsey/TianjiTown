package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Usage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Handles administrator membership, mayor and vote commands. */
public final class TownAdminGovernanceCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    public TownAdminGovernanceCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    @Command("tianjitown member add")
    @Usage("/tianjitown member add <小镇代码> <玩家> <原因>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void addMember(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> memberRequest(runtime, input), request -> {
            UUID playerId = TownAdminCommand.playerId(request.player());
            runtime.write(sender, () -> {
                TownSnapshot town = facade.requireTown(runtime, request.townId());
                runtime.repository().addMember(town.id(), playerId, TownAdminCommand.actorId(sender),
                        sender.getName(), request.reason());
                return town;
            }, town -> {
                facade.send(sender, "chat.admin.member-added");
                Player added = Bukkit.getPlayer(playerId);
                if (added != null) {
                    runtime.buffs().refreshPlayer(added);
                }
                facade.reconcileOne(sender, runtime, town.id(), true);
            });
        });
    }

    @Command("tianjitown member remove")
    @Usage("/tianjitown member remove <小镇代码> <玩家> <原因>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void removeMember(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> memberRequest(runtime, input), request -> {
            UUID playerId = TownAdminCommand.playerId(request.player());
            runtime.write(sender, () -> {
                TownSnapshot town = facade.requireTown(runtime, request.townId());
                runtime.repository().removeMember(town.id(), playerId, TownAdminCommand.actorId(sender),
                        sender.getName(), request.reason());
                return town;
            }, town -> {
                facade.send(sender, "chat.admin.member-removed");
                Player removed = Bukkit.getPlayer(playerId);
                if (removed != null) {
                    runtime.buffs().refreshPlayer(removed);
                }
                facade.reconcileOne(sender, runtime, town.id(), true);
            });
        });
    }

    @Command("tianjitown member role")
    @Usage("/tianjitown member role <小镇代码> <玩家> <角色>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void roleMember(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> memberRequest(runtime, input), request -> {
            UUID playerId = TownAdminCommand.playerId(request.player());
            MemberRole role;
            try {
                role = MemberRole.valueOf(request.reason().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                facade.send(sender, "chat.admin.role-invalid");
                return;
            }
            runtime.write(sender, () -> {
                TownSnapshot town = facade.requireTown(runtime, request.townId());
                runtime.governance().changeRoleByAdmin(town.id(), playerId, role,
                        TownAdminCommand.actorId(sender), sender.getName(),
                        plugin.messages().plainText("log.admin.member-role-change-reason"));
                return town;
            }, town -> {
                facade.send(sender, "chat.admin.role-updated", Map.of("role", role));
                facade.reconcileOne(sender, runtime, town.id(), true);
            });
        });
    }

    @Command("tianjitown vote cancel")
    @Usage("/tianjitown vote cancel <小镇代码> <原因>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void cancelVote(CommandSender sender, TownRuntime runtime,
                           @revxrsal.commands.annotation.Single String townCode, String inputReason) {
        String reason = TownCommandParser.reason(inputReason.split(" "), 0, plugin.messages()::plainText);
        runtime.write(sender, () -> {
            TownSnapshot town = facade.requireTown(runtime, townCode);
            var vote = runtime.governance().listTownVotes(town.id(), TownAdminCommand.actorId(sender), true)
                    .stream().findFirst().orElseThrow(() -> facade.messageArgument(
                            "chat.admin.vote-no-open", Map.of("town", townCode)));
            return runtime.governance().cancelVote(vote.id(), TownAdminCommand.actorId(sender),
                    sender.getName(), reason);
        }, vote -> facade.send(sender, "chat.admin.vote-cancelled", Map.of("id", vote.id())));
    }

    @Command("tianjitown mayor transfer")
    @Usage("/tianjitown mayor transfer <小镇代码> <玩家> <原因>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void transferMayor(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> memberRequest(runtime, input), request -> {
            UUID newMayor = TownAdminCommand.playerId(request.player());
            runtime.write(sender, () -> {
                TownSnapshot town = facade.requireTown(runtime, request.townId());
                runtime.repository().transferMayor(town.id(), newMayor, TownAdminCommand.actorId(sender),
                        sender.getName(), request.reason());
                return town;
            }, town -> {
                facade.send(sender, "chat.admin.mayor-emergency-transfer");
                facade.reconcileOne(sender, runtime, town.id(), true);
            });
        });
    }

    private MemberRequest memberRequest(TownRuntime runtime, String input) {
        List<TownSnapshot> candidates = runtime.repository().listTowns(false).stream()
                .filter(town -> town.status() == TownStatus.ACTIVE).toList();
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(input.split(" "), 0,
                TownAdminCommand.townCodes(candidates), plugin.messages()::plainText);
        TownSnapshot town = candidates.stream()
                .filter(candidate -> TownAdminCommand.sameCode(candidate.profile().residenceName(), parsed.townName()))
                .findFirst().orElseThrow(() -> facade.messageArgument(
                        "chat.admin.member-town-not-found",
                        Map.of("town", TownAdminCommand.safeText(parsed.townName()))));
        return new MemberRequest(town.id(), parsed.player(), parsed.reason());
    }

    private record MemberRequest(UUID townId, String player, String reason) {
    }

}

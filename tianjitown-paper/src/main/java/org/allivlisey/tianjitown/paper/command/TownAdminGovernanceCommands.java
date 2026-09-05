package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.config.GovernanceSettings;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.governance.VoteType;
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

    @Command("townadmin member add")
    @Usage("/townadmin member add <小镇全名> <玩家> <原因>")
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

    @Command("townadmin member remove")
    @Usage("/townadmin member remove <小镇全名> <玩家> <原因>")
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

    @Command("townadmin member role")
    @Usage("/townadmin member role <小镇全名> <玩家> <角色>")
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

    @Command("townadmin vote settle")
    @Usage("/townadmin vote settle <voteId>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void settleVote(CommandSender sender, TownRuntime runtime, UUID voteId) {
        runtime.write(sender, () -> runtime.governance().settleVote(voteId,
                TownAdminCommand.actorId(sender), sender.getName(), false), vote -> {
            facade.send(sender, "chat.admin.vote-status", Map.of("status", vote.status(),
                    "yes", vote.yesVotes(), "required", vote.requiredYes()));
            if (vote.passed() && vote.type() == VoteType.KICK_MEMBER) {
                facade.reconcileOne(sender, runtime, vote.townId(), true);
            }
        });
    }

    @Command("townadmin vote cancel")
    @Usage("/townadmin vote cancel <voteId> <原因>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void cancelVote(CommandSender sender, TownRuntime runtime, UUID voteId, String inputReason) {
        String reason = TownCommandParser.reason(inputReason.split(" "), 0, plugin.messages()::plainText);
        runtime.write(sender, () -> runtime.governance().cancelVote(voteId,
                TownAdminCommand.actorId(sender), sender.getName(), reason), vote ->
                facade.send(sender, "chat.admin.vote-cancelled", Map.of("id", vote.id())));
    }

    @Command("townadmin vote create-kick")
    @Usage("/townadmin vote create-kick <小镇全名> <玩家>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void createKickVote(CommandSender sender, TownRuntime runtime, String input) {
        createVote(sender, runtime, input, VoteType.KICK_MEMBER);
    }

    @Command("townadmin vote create-mayor")
    @Usage("/townadmin vote create-mayor <小镇全名> <玩家>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void createMayorVote(CommandSender sender, TownRuntime runtime, String input) {
        createVote(sender, runtime, input, VoteType.REPLACE_MAYOR);
    }

    private void createVote(CommandSender sender, TownRuntime runtime, String input, VoteType type) {
        GovernanceSettings settings = GovernanceSettings.fixed();
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(false).stream()
                    .filter(town -> town.status() == TownStatus.ACTIVE).toList();
            TownCommandParser.NamedPlayer parsed = TownCommandParser.namedPlayer(input.split(" "), 0,
                    TownAdminCommand.townNames(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream()
                    .filter(candidate -> TownAdminCommand.sameName(candidate.profile().name(), parsed.townName()))
                    .findFirst().orElseThrow(() -> facade.messageArgument(
                            "chat.admin.vote-town-not-found"));
            return new VoteCreateRequest(town.id(), TownAdminCommand.playerId(parsed.player()),
                    type);
        }, request -> {
            runtime.write(sender, () -> runtime.governance().createVote(request.townId(),
                    request.type(), request.targetId(), TownAdminCommand.actorId(sender),
                    settings.activeMemberWindow(), settings.minimumMembership(),
                    settings.voteDuration(), true), vote -> facade.send(sender, "chat.admin.vote-created",
                    Map.of("id", vote.id(), "voters", vote.eligibleVoters(),
                            "required", vote.requiredYes())));
        });
    }

    @Command("townadmin mayor transfer")
    @Usage("/townadmin mayor transfer <小镇全名> <玩家> <原因>")
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
                TownAdminCommand.townNames(candidates), plugin.messages()::plainText);
        TownSnapshot town = candidates.stream()
                .filter(candidate -> TownAdminCommand.sameName(candidate.profile().name(), parsed.townName()))
                .findFirst().orElseThrow(() -> facade.messageArgument(
                        "chat.admin.member-town-not-found",
                        Map.of("town", TownAdminCommand.safeText(parsed.townName()))));
        return new MemberRequest(town.id(), parsed.player(), parsed.reason());
    }

    private record MemberRequest(UUID townId, String player, String reason) {
    }

    private record VoteCreateRequest(UUID townId, UUID targetId, VoteType type) {
    }
}

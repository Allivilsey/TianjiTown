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

    public boolean member(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requireMessageLength(args, 5, "chat.admin.usage-member");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("add") && !action.equals("remove") && !action.equals("role")) {
            throw facade.messageArgument("chat.admin.member-action-unsupported");
        }
        runtime.read(sender, () -> memberRequest(runtime, args), request -> {
            UUID playerId = TownAdminCommand.playerId(request.player());
            if (action.equals("role")) {
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
            } else if (action.equals("add")) {
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
            } else {
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
            }
        });
        return true;
    }

    public boolean vote(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requireMessageLength(args, 3, "chat.admin.usage-vote");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("settle")) {
            UUID voteId = UUID.fromString(args[2]);
            runtime.write(sender, () -> runtime.governance().settleVote(voteId,
                    TownAdminCommand.actorId(sender), sender.getName(), false), vote -> {
                facade.send(sender, "chat.admin.vote-status", Map.of("status", vote.status(),
                        "yes", vote.yesVotes(), "required", vote.requiredYes()));
                if (vote.passed() && vote.type() == VoteType.KICK_MEMBER) {
                    facade.reconcileOne(sender, runtime, vote.townId(), true);
                }
            });
            return true;
        }
        if (action.equals("cancel")) {
            facade.requireMessageLength(args, 4, "chat.admin.usage-vote-cancel");
            UUID voteId = UUID.fromString(args[2]);
            String reason = TownCommandParser.reason(args, 3, plugin.messages()::plainText);
            runtime.write(sender, () -> runtime.governance().cancelVote(voteId,
                    TownAdminCommand.actorId(sender), sender.getName(), reason), vote ->
                    facade.send(sender, "chat.admin.vote-cancelled", Map.of("id", vote.id())));
            return true;
        }
        if (!action.equals("create-kick") && !action.equals("create-mayor")) {
            throw facade.messageArgument("chat.admin.vote-action-unsupported");
        }
        facade.requireMessageLength(args, 4, "chat.admin.usage-vote-create",
                Map.of("action", action));
        GovernanceSettings settings = GovernanceSettings.fixed();
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(false).stream()
                    .filter(town -> town.status() == TownStatus.ACTIVE).toList();
            TownCommandParser.NamedPlayer parsed = TownCommandParser.namedPlayer(args, 2,
                    TownAdminCommand.townNames(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream()
                    .filter(candidate -> TownAdminCommand.sameName(candidate.profile().name(), parsed.townName()))
                    .findFirst().orElseThrow(() -> facade.messageArgument(
                            "chat.admin.vote-town-not-found"));
            return new VoteCreateRequest(town.id(), TownAdminCommand.playerId(parsed.player()),
                    action.equals("create-kick") ? VoteType.KICK_MEMBER : VoteType.REPLACE_MAYOR);
        }, request -> {
            runtime.write(sender, () -> runtime.governance().createVote(request.townId(),
                    request.type(), request.targetId(), TownAdminCommand.actorId(sender),
                    settings.activeMemberWindow(), settings.minimumMembership(),
                    settings.voteDuration(), true), vote -> facade.send(sender, "chat.admin.vote-created",
                    Map.of("id", vote.id(), "voters", vote.eligibleVoters(),
                            "required", vote.requiredYes())));
        });
        return true;
    }

    private MemberRequest memberRequest(TownRuntime runtime, String[] args) {
        List<TownSnapshot> candidates = runtime.repository().listTowns(false).stream()
                .filter(town -> town.status() == TownStatus.ACTIVE).toList();
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(args, 2,
                TownAdminCommand.townNames(candidates), plugin.messages()::plainText);
        TownSnapshot town = candidates.stream()
                .filter(candidate -> TownAdminCommand.sameName(candidate.profile().name(), parsed.townName()))
                .findFirst().orElseThrow(() -> facade.messageArgument(
                        "chat.admin.member-town-not-found",
                        Map.of("town", TownAdminCommand.safeText(parsed.townName()))));
        return new MemberRequest(town.id(), parsed.player(), parsed.reason());
    }

    public boolean mayor(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requireMessageLength(args, 5, "chat.admin.usage-mayor");
        if (!args[1].equalsIgnoreCase("transfer")) {
            throw facade.messageArgument("chat.admin.usage-mayor");
        }
        runtime.read(sender, () -> memberRequest(runtime, args), request -> {
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
        return true;
    }

    private record MemberRequest(UUID townId, String player, String reason) {
    }

    private record VoteCreateRequest(UUID townId, UUID targetId, VoteType type) {
    }
}

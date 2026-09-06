package org.allivlisey.tianjitown.paper.action;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.config.GovernanceSettings;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.TransferSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.paper.action.TownActionSupport.*;
import static org.allivlisey.tianjitown.paper.action.TownActionSupport.*;

final class TownGovernanceActions {
    private final TownActionSupport support;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownGovernanceActions(TownActionSupport support) {
        this.support = support;
        this.plugin = support.plugin;
        this.runtime = support.runtime;
    }

    public void updateTownProfile(Player actor, UUID townId, ApplicationText profile,
                           long expectedVersion,
                           Consumer<TownActionOutcome<TownSnapshot>> completion) {
        if (!support.validateApplicationText("TOWN_PROFILE_UPDATE", profile, completion)) {
            return;
        }
        support.write("TOWN_PROFILE_UPDATE", actor,
                () -> runtime.repository().updateTownProfile(townId, profile, expectedVersion,
                        actor.getUniqueId(), actor.getName(), "管理组通过共享业务入口修改资料"),
                town -> Map.of("town_id", town.id(), "version", town.version(),
                        "rules_revision", town.rulesRevision()), completion);
    }

    public void requestMayorTransfer(Player actor, UUID townId, UUID candidateId,
                              Consumer<TownActionOutcome<TransferSnapshot>> completion) {
        GovernanceSettings settings = GovernanceSettings.fixed();
        support.write("MAYOR_TRANSFER_REQUEST", actor,
                () -> runtime.governance().requestMayorTransfer(townId, candidateId,
                        actor.getUniqueId(), settings.transferConfirmation()),
                transfer -> support.transferData(transfer), completion);
    }

    public void decideMayorTransfer(Player actor, UUID transferId, boolean accept,
                             Consumer<TownActionOutcome<TransferSnapshot>> completion) {
        support.write("MAYOR_TRANSFER_DECIDE", actor,
                () -> runtime.governance().decideMayorTransfer(transferId,
                        actor.getUniqueId(), accept), transfer -> {
                    if (accept) {
                        support.syncResidence(actor, transfer.townId());
                    }
                    return support.transferData(transfer);
                }, completion);
    }

    public void acknowledgeRules(Player actor, UUID townId, long revision,
                          Consumer<TownActionOutcome<Long>> completion) {
        support.write("RULES_ACKNOWLEDGE", actor, () -> {
            runtime.governance().acknowledgeRules(townId, actor.getUniqueId(), revision);
            return revision;
        }, confirmed -> Map.of("town_id", townId, "revision", confirmed), completion);
    }

    public void createVote(Player actor, UUID townId, VoteType type, UUID targetId,
                    Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        GovernanceSettings settings = GovernanceSettings.fixed();
        support.write("VOTE_CREATE", actor,
                () -> runtime.governance().createVote(townId, type, targetId,
                        actor.getUniqueId(), settings.activeMemberWindow(),
                        settings.minimumMembership(), settings.voteDuration(), false),
                TownActionSupport::voteData, completion);
    }

    public void castVote(Player actor, UUID voteId, boolean approve,
                  Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        support.write("VOTE_CAST", actor,
                () -> runtime.governance().castVote(voteId, actor.getUniqueId(), approve),
                vote -> {
                    if (vote.passed() && vote.type() == VoteType.KICK_MEMBER) {
                        Player removed = plugin.getServer().getPlayer(vote.subjectId());
                        if (removed != null) {
                            runtime.buffs().refreshPlayer(removed);
                        }
                        support.syncResidence(actor, vote.townId());
                    }
                    return support.voteData(vote);
                }, completion);
    }

    public void cancelOwnVote(Player actor, UUID voteId,
                       Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        support.write("VOTE_CANCEL", actor,
                () -> runtime.governance().cancelOwnVote(voteId, actor.getUniqueId(),
                        actor.getName()), TownActionSupport::voteData, completion);
    }

    public void disbandTown(Player actor, UUID townId, long expectedVersion,
                     Consumer<TownActionOutcome<TownSnapshot>> completion) {
        String action = "TOWN_DISBAND";
        if (support.rejectBeforeWrite(action, completion)) {
            return;
        }
        support.writeUnchecked(action, actor,
                () -> runtime.repository().disbandTown(townId, actor.getUniqueId(),
                        expectedVersion), town -> Map.of(), archived -> {
                    if (!archived.result().success()) {
                        completion.accept(archived);
                        return;
                    }
                    TownSnapshot town = archived.value();
                    LandProtectionService.Result removed;
                    try {
                        removed = runtime.landProtection().remove(town.residenceName(),
                                town.territory());
                    } catch (RuntimeException | LinkageError exception) {
                        completion.accept(TownActionOutcome.failure(TownActionResult.failure(
                                action, "LAND_PROTECTION_FAILED",
                                Map.of("town_id", town.id(), "archived", true, "detail",
                                        TownActionFailures.safeMessage(exception)))));
                        return;
                    }
                    if (!removed.success()) {
                        completion.accept(TownActionOutcome.failure(TownActionResult.failure(
                                action, "LAND_PROTECTION_FAILED", Map.of("town_id", town.id(),
                                        "archived", true, "detail",
                                        LandProtectionMessages.detail(plugin.messages(), removed)))));
                        return;
                    }
                    support.writeUnchecked(action, actor, () -> {
                        runtime.repository().completeTownDeletion(town.id(), actor.getUniqueId(),
                                actor.getName(), "镇长通过共享业务入口解散");
                        return town;
                    }, completed -> {
                        runtime.deactivateResidence(completed.residenceName());
                        runtime.buffs().refreshAllPlayers();
                        return Map.of("town_id", completed.id(), "status", "DELETED");
                    }, completion);
                });
    }

}

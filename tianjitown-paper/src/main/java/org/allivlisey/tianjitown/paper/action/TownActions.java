package org.allivlisey.tianjitown.paper.action;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.land.ExpansionDirection;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.TransferSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Compatibility entry point for player actions. */
public final class TownActions {
    private final TownApplicationActions application;
    private final TownMembershipActions membership;
    private final TownGovernanceActions governance;
    private final TownEconomyActions economy;
    private final TownExpansionActions expansion;
    private final TownBuffActions buff;
    private final TownQueryActions query;

    public TownActions(TianjiTownPlugin plugin, TownRuntime runtime) {
        TownActionSupport support = new TownActionSupport(plugin, runtime);
        application = new TownApplicationActions(support);
        membership = new TownMembershipActions(support);
        governance = new TownGovernanceActions(support);
        economy = new TownEconomyActions(support);
        expansion = new TownExpansionActions(support);
        buff = new TownBuffActions(support);
        query = new TownQueryActions(support);
    }

    public void createApplication(Player actor, ApplicationText text, List<UUID> initialMemberIds,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        application.createApplication(actor, text, initialMemberIds, completion);
    }

    public void updateApplication(Player actor, UUID applicationId, ApplicationText text,
                           List<UUID> initialMemberIds, long expectedVersion,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        application.updateApplication(actor, applicationId, text, initialMemberIds, expectedVersion, completion);
    }

    public void respondInitialMember(Player actor, UUID applicationId, boolean confirm,
                              Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        application.respondInitialMember(actor, applicationId, confirm, completion);
    }

    public void selectApplicationSite(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        application.selectApplicationSite(actor, applicationId, completion);
    }

    public void submitApplication(Player actor, UUID applicationId,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        application.submitApplication(actor, applicationId, completion);
    }

    public void cancelApplication(Player actor, UUID applicationId,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        application.cancelApplication(actor, applicationId, completion);
    }

    public void reviewApplication(Player actor, UUID applicationId, boolean requestChanges,
                           String reason,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        application.reviewApplication(actor, applicationId, requestChanges, reason, completion);
    }

    public void applyToTown(Player actor, UUID townId,
                     Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        membership.applyToTown(actor, townId, completion);
    }

    public void cancelJoinApplication(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        membership.cancelJoinApplication(actor, applicationId, completion);
    }

    public void approveJoinApplication(Player actor, UUID applicationId,
                                Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        membership.approveJoinApplication(actor, applicationId, completion);
    }

    public void rejectJoinApplication(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        membership.rejectJoinApplication(actor, applicationId, completion);
    }

    public void changeMemberRole(Player actor, UUID townId, UUID targetId, MemberRole role,
                          Consumer<TownActionOutcome<MemberRole>> completion) {
        membership.changeMemberRole(actor, townId, targetId, role, completion);
    }

    public void kickMember(Player actor, UUID townId, UUID targetId,
                    Consumer<TownActionOutcome<TownPlayerChange>> completion) {
        membership.kickMember(actor, townId, targetId, completion);
    }

    public void addVisitor(Player actor, UUID townId, UUID targetId,
                    Consumer<TownActionOutcome<TownPlayerChange>> completion) {
        membership.addVisitor(actor, townId, targetId, completion);
    }

    public void removeVisitor(Player actor, UUID townId, UUID targetId,
                       Consumer<TownActionOutcome<TownPlayerChange>> completion) {
        membership.removeVisitor(actor, townId, targetId, completion);
    }

    public void leaveTown(Player actor, UUID townId,
                   Consumer<TownActionOutcome<UUID>> completion) {
        membership.leaveTown(actor, townId, completion);
    }

    public void updateTownProfile(Player actor, UUID townId, ApplicationText profile,
                           long expectedVersion,
                           Consumer<TownActionOutcome<TownSnapshot>> completion) {
        governance.updateTownProfile(actor, townId, profile, expectedVersion, completion);
    }

    public void requestMayorTransfer(Player actor, UUID townId, UUID candidateId,
                              Consumer<TownActionOutcome<TransferSnapshot>> completion) {
        governance.requestMayorTransfer(actor, townId, candidateId, completion);
    }

    public void decideMayorTransfer(Player actor, UUID transferId, boolean accept,
                             Consumer<TownActionOutcome<TransferSnapshot>> completion) {
        governance.decideMayorTransfer(actor, transferId, accept, completion);
    }

    public void acknowledgeRules(Player actor, UUID townId, long revision,
                          Consumer<TownActionOutcome<Long>> completion) {
        governance.acknowledgeRules(actor, townId, revision, completion);
    }

    public void createVote(Player actor, UUID townId, VoteType type, UUID targetId,
                    Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        governance.createVote(actor, townId, type, targetId, completion);
    }

    public void castVote(Player actor, UUID voteId, boolean approve,
                  Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        governance.castVote(actor, voteId, approve, completion);
    }

    public void cancelOwnVote(Player actor, UUID voteId,
                       Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        governance.cancelOwnVote(actor, voteId, completion);
    }

    public void disbandTown(Player actor, UUID townId, long expectedVersion,
                     Consumer<TownActionOutcome<TownSnapshot>> completion) {
        governance.disbandTown(actor, townId, expectedVersion, completion);
    }

    public void changeTaxRate(Player actor, UUID townId, int basisPoints,
                       Consumer<TownActionOutcome<EconomyRepository.TaxChange>> completion) {
        economy.changeTaxRate(actor, townId, basisPoints, completion);
    }

    public void acknowledgeTaxRevision(Player actor, int revision,
                                Consumer<TownActionOutcome<Integer>> completion) {
        economy.acknowledgeTaxRevision(actor, revision, completion);
    }

    public void donate(Player actor, long amountMinor,
                Consumer<TownActionOutcome<EconomyRepository.LedgerMutation>> completion) {
        economy.donate(actor, amountMinor, completion);
    }

    public void expandTown(Player actor, ExpansionDirection direction,
                    Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        expansion.expandTown(actor, direction, completion);
    }

    public void expandTown(Player actor, int gridX, int gridZ,
                    Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        expansion.expandTown(actor, gridX, gridZ, completion);
    }

    public void buyBuff(Player actor, String buffKey, int weeks, int level,
                 Consumer<TownActionOutcome<CommerceRepository.BuffPurchase>> completion) {
        buff.buyBuff(actor, buffKey, weeks, level, completion);
    }

    public void queryActor(Player actor, Consumer<TownActionOutcome<Void>> completion) {
        query.queryActor(actor, completion);
    }

    public static <T> boolean validateText(String action, ApplicationText text,
                                    Consumer<TownActionOutcome<T>> completion) {
        try {
            text.requireValid();
            return true;
        } catch (ApplicationText.ValidationException exception) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("detail",
                            exception.getMessage()))));
            return false;
        }
    }
}

package org.allivlisey.tianjitown.paper.ui.governance;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.home.RuleEditorDialogRenderer;
import org.allivlisey.tianjitown.paper.ui.membership.VisitorManagementMenuModel;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade.GovernanceCenterView;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Handles town rules, mayor transfers and member voting dialogs. */
public final class TownGovernanceDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownRulesDialogs townRulesDialogs;
    private final TownMayorTransferDialogs townMayorTransferDialogs;
    private final TownVoteDialogs townVoteDialogs;
    private final RuleEditorControls ruleEditorControls;

    public TownGovernanceDialogs(TownUiLegacyFacade facade, TownRulesDialogs rules, TownMayorTransferDialogs transfers, TownVoteDialogs votes, RuleEditorControls controls) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.townRulesDialogs = rules;
        this.townMayorTransferDialogs = transfers;
        this.townVoteDialogs = votes;
        this.ruleEditorControls = controls;
    }

    public void openGovernanceCenter(Player player) {
        runtime.read(player, () -> new GovernanceCenterView(
                runtime.repository().dashboard(player.getUniqueId()),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.dashboard().town();
            MemberGovernanceSnapshot governance = view.governance();
            if (town == null || governance == null) {
                presentation.openNotice(player, presentation.dialogText("notice.no-town-title"),
                        presentation.dialogText("notice.no-town-message"),
                        presentation.dialogText("common.back"), "MAIN", null);
                return;
            }
            int pendingJoins = governance.canReviewApplications()
                    ? view.dashboard().incomingJoinApplications().size() : 0;
            long pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, presentation.button(Material.GOLDEN_HELMET,
                    presentation.dialogText("governance.title"),
                    List.of(presentation.dialogText("governance.town-summary", Map.of(
                                    "town", town.profile().name())),
                            presentation.dialogText("member-role.current-identity", Map.of("role",
                                    facade.memberRoleText(governance.role()))),
                            presentation.dialogText("governance.pending-joins", Map.of("count", pendingJoins)),
                            presentation.dialogText("votes.governance-pending-votes",
                                    Map.of("count", pendingVotes))), null, null)));
            items.add(new MenuItem(10, presentation.button(Material.PLAYER_HEAD, presentation.dialogText("governance.members"),
                    List.of(presentation.dialogText("tooltip.governance.members")),
                    "MEMBERS", town.id() + ":0")));
            items.add(new MenuItem(12, presentation.button(Material.BOOK, presentation.dialogText("votes.governance-title"),
                    List.of(presentation.dialogText("tooltip.governance.votes")),
                    "VOTES", town.id().toString())));
            if (governance.canReviewApplications()) {
                items.add(new MenuItem(14, presentation.button(pendingJoins > 0
                                ? Material.ENCHANTED_BOOK : Material.BOOK,
                        pendingJoins > 0 ? presentation.dialogText("common.applications-count",
                                Map.of("count", pendingJoins))
                                : presentation.dialogText("governance.applications"),
                        List.of(presentation.dialogText("tooltip.governance.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            VisitorManagementMenuModel visitorMenu = VisitorManagementMenuModel.create(
                    plugin.messages(), governance, town.id());
            if (visitorMenu.visible()) {
                items.add(new MenuItem(16, presentation.button(Material.NAME_TAG, visitorMenu.label(),
                        visitorMenu.lore(), visitorMenu.action(), visitorMenu.target())));
            }
            presentation.openMenu(player, 27, presentation.dialogText("governance.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }
    public void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        townRulesDialogs.renderRulesConfirmation(player, governance);
    }

    public void openTownRules(Player player, UUID townId) {
        townRulesDialogs.openTownRules(player, townId);
    }

    public void openJoinTownRules(Player player, UUID townId) {
        townRulesDialogs.openJoinTownRules(player, townId);
    }

    public void openTownRuleEditor(Player player, UUID townId) {
        townRulesDialogs.openTownRuleEditor(player, townId);
    }

    public void acknowledgeRules(Player player, UUID townId, long revision) {
        townRulesDialogs.acknowledgeRules(player, townId, revision);
    }

    public void openTransferRequest(Player player, UUID transferId) {
        townMayorTransferDialogs.openTransferRequest(player, transferId);
    }

    public void requestMayorTransfer(Player mayor, UUID townId, UUID candidateId) {
        townMayorTransferDialogs.requestMayorTransfer(mayor, townId, candidateId);
    }

    public void decideMayorTransfer(Player candidate, UUID transferId, boolean accept) {
        townMayorTransferDialogs.decideMayorTransfer(candidate, transferId, accept);
    }

    public void openVotes(Player player, UUID townId) {
        townVoteDialogs.openVotes(player, townId);
    }

    public void openVote(Player player, UUID voteId) {
        townVoteDialogs.openVote(player, voteId);
    }

    public void createVote(Player player, UUID townId, VoteType type, UUID targetId) {
        townVoteDialogs.createVote(player, townId, type, targetId);
    }

    public void sendVoteReminder(Player player, VoteSnapshot vote) {
        townVoteDialogs.sendVoteReminder(player, vote);
    }

    public void castVote(Player player, UUID voteId, boolean approve) {
        townVoteDialogs.castVote(player, voteId, approve);
    }

    public void cancelVote(Player player, UUID voteId) {
        townVoteDialogs.cancelVote(player, voteId);
    }

    public void openRuleEditorAddDialog(Player player, String title, Component content,
                                         DialogRoute parent,
                                         int addWidth,
                                         int columns,
                                         Function<UUID, List<ActionButton>> afterAddActions,
                                         Function<UUID, List<ActionButton>> ruleActions,
                                         Consumer<DialogResponseView> addRule,
                                         Function<UUID, List<ActionButton>> trailingActions,
                                         Function<UUID, List<ActionButton>> postActions) {
        ruleEditorControls.openRuleEditorAddDialog(player, title, content, parent, addWidth, columns, afterAddActions, ruleActions, addRule, trailingActions, postActions);
    }

    public List<ActionButton> inlineRuleDeletionActions(Player player, UUID session,
                                                          RuleEditorDialogRenderer.Layout layout,
                                                          Consumer<RuleEditorDialogRenderer.DeleteTarget> deleteRule) {
        return ruleEditorControls.inlineRuleDeletionActions(player, session, layout, deleteRule);
    }

}

package org.allivlisey.tianjitown.paper.ui.governance;
import org.allivlisey.tianjitown.paper.runtime.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.home.ReadOnlyRulesDialogRenderer;
import org.allivlisey.tianjitown.paper.ui.home.RuleEditorDialogRenderer;
import org.allivlisey.tianjitown.paper.ui.membership.VisitorManagementMenuModel;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import org.allivlisey.tianjitown.storage.governance.TransferSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
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
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownGovernanceDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openGovernanceCenter(Player player) {
        runtime.read(player, () -> new GovernanceCenterView(
                runtime.repository().dashboard(player.getUniqueId()),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.dashboard().town();
            MemberGovernanceSnapshot governance = view.governance();
            if (town == null || governance == null) {
                facade.openNotice(player, facade.dialogText("notice.no-town-title"),
                        facade.dialogText("notice.no-town-message"),
                        facade.dialogText("common.back"), "MAIN", null);
                return;
            }
            int pendingJoins = governance.canReviewApplications()
                    ? view.dashboard().incomingJoinApplications().size() : 0;
            long pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, facade.button(Material.GOLDEN_HELMET,
                    facade.dialogText("governance.title"),
                    List.of(facade.dialogText("governance.town-summary", Map.of(
                                    "town", town.profile().name())),
                            facade.dialogText("member-role.current-identity", Map.of("role",
                                    facade.memberRoleText(governance.role()))),
                            facade.dialogText("governance.pending-joins", Map.of("count", pendingJoins)),
                            facade.dialogText("votes.governance-pending-votes",
                                    Map.of("count", pendingVotes))), null, null)));
            items.add(new MenuItem(10, facade.button(Material.PLAYER_HEAD, facade.dialogText("governance.members"),
                    List.of(facade.dialogText("tooltip.governance.members")),
                    "MEMBERS", town.id() + ":0")));
            items.add(new MenuItem(12, facade.button(Material.BOOK, facade.dialogText("votes.governance-title"),
                    List.of(facade.dialogText("tooltip.governance.votes")),
                    "VOTES", town.id().toString())));
            if (governance.canReviewApplications()) {
                items.add(new MenuItem(14, facade.button(pendingJoins > 0
                                ? Material.ENCHANTED_BOOK : Material.BOOK,
                        pendingJoins > 0 ? facade.dialogText("common.applications-count",
                                Map.of("count", pendingJoins))
                                : facade.dialogText("governance.applications"),
                        List.of(facade.dialogText("tooltip.governance.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            VisitorManagementMenuModel visitorMenu = VisitorManagementMenuModel.create(
                    plugin.messages(), governance, town.id());
            if (visitorMenu.visible()) {
                items.add(new MenuItem(16, facade.button(Material.NAME_TAG, visitorMenu.label(),
                        visitorMenu.lore(), visitorMenu.action(), visitorMenu.target())));
            }
            facade.openMenu(player, 27, facade.dialogText("governance.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        Component rules = facade.dialogComponent("common.town", Map.of(
                        "town", TownUiLegacyFacade.safeText(governance.townName())))
                .append(Component.newline())
                .append(facade.dialogComponent("rules.revision", Map.of(
                        "revision", governance.townRulesRevision())));
        for (int index = 0; index < governance.rules().size(); index++) {
            rules = rules.append(Component.newline()).append(Component.newline())
                    .append(facade.dialogComponent("rules.item", Map.of(
                            "index", index + 1,
                            "rule", TownUiLegacyFacade.safeText(governance.rules().get(index)))));
        }
        rules = rules.append(Component.newline()).append(Component.newline())
                .append(facade.dialogComponent("rules.locked-hint"));
        DialogInput acknowledged = DialogInput.bool("rules_acknowledged",
                facade.dialogComponent("rules.acknowledgement"),
                false, "true", "false");
        facade.openDialogPage(player, facade.dialogText("rules.updated-title"),
                List.of(DialogBody.plainMessage(rules, 420)),
                List.of(acknowledged), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.multiAction(List.of(
                                ActionButton.create(facade.dialogComponent("rules.confirm"),
                                        null, 170, facade.dialogAction(player, session,
                                                response -> acknowledgeRulesDialog(player,
                                                        governance.townId(),
                                                        governance.townRulesRevision(), response))),
                                ActionButton.create(facade.dialogComponent("rules.later"),
                                        facade.dialogComponent("rules.later-tooltip"),
                                        170, facade.dialogAction(player, session, "CLOSE", null))))
                        .exitAction(facade.returnButton(player, session, DialogRoute.ROOT))
                        .columns(2).build(), DialogRoute.ROOT);
    }

    private void acknowledgeRulesDialog(Player player, UUID townId, long revision,
                                        DialogResponseView response) {
        if (!Boolean.TRUE.equals(response.getBoolean("rules_acknowledged"))) {
            facade.openNotice(player, facade.dialogText("rules.required-title"),
                    facade.dialogText("rules.required-message"), facade.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        acknowledgeRules(player, townId, revision);
    }

    public void openTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town ->
                openReadOnlyTownRules(player, town,
                        new DialogRoute("TOWN", town.id().toString())));
    }

    public void openJoinTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-unavailable"))), town ->
                openReadOnlyTownRules(player, town,
                        new DialogRoute("JOIN_TOWN", town.id().toString())));
    }

    private void openReadOnlyTownRules(Player player, TownSnapshot town, DialogRoute returnRoute) {
        ReadOnlyRulesDialogRenderer.Layout layout = ReadOnlyRulesDialogRenderer.layout(
                town.profile().name(), town.profile().rules(), returnRoute);
        facade.openDialogPage(player, facade.dialogText("common.rules-title"), List.of(
                        DialogBody.plainMessage(ReadOnlyRulesDialogRenderer.content(
                                plugin.messages(), layout), ReadOnlyRulesDialogRenderer.CONTENT_WIDTH)),
                List.of(), DialogBase.DialogAfterAction.NONE,
                session -> DialogType.notice(facade.returnButton(player, session, layout.returnRoute())),
                layout.returnRoute());
    }

    public void openTownRuleEditor(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(town.id(),
                    town.version(), town.profile().rules());
            DialogRoute parent = new DialogRoute("TOWN", town.id().toString());
            Component heading = facade.dialogComponent("rules.edit-heading", Map.of(
                    "town", TownUiLegacyFacade.safeText(town.profile().name())));
            openRuleEditorAddDialog(player, facade.dialogText("rules.edit-title"),
                    heading, parent,
                    RuleEditorDialogRenderer.SINGLE_COLUMN_ACTION_WIDTH,
                    1,
                    session -> List.of(),
                    session -> inlineRuleDeletionActions(player, session, layout,
                            deleteTarget -> deleteTownRule(player, deleteTarget)),
                    response -> addTownRule(player, town.id(), town.version(), response),
                    session -> List.of(), session -> List.of());
        });
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
        DialogInput input = DialogInput.text("rule_text", 400,
                facade.dialogComponent("rules.input-label"), false, "", 300, null);
        facade.openDialogPage(player, title, List.of(DialogBody.plainMessage(
                        content, 420)), List.of(input),
                DialogBase.DialogAfterAction.NONE, session -> {
                    List<ActionButton> actions = new ArrayList<>();
                    actions.add(ActionButton.create(facade.dialogComponent("rules.add"),
                            facade.dialogComponent("rules.add-tooltip"), addWidth,
                            facade.dialogAction(player, session, addRule)));
                    actions.addAll(afterAddActions.apply(session));
                    actions.addAll(ruleActions.apply(session));
                    actions.addAll(trailingActions.apply(session));
                    actions.addAll(postActions.apply(session));
                    return DialogType.multiAction(actions)
                            .exitAction(facade.returnButton(player, session, parent))
                            .columns(columns).build();
                }, parent);
    }

    public List<ActionButton> inlineRuleDeletionActions(Player player, UUID session,
                                                          RuleEditorDialogRenderer.Layout layout,
                                                          Consumer<RuleEditorDialogRenderer.DeleteTarget> deleteRule) {
        List<ActionButton> actions = new ArrayList<>();
        for (RuleEditorDialogRenderer.Row row : layout.rows()) {
            actions.add(ActionButton.create(facade.dialogComponent("rules.item", Map.of(
                            "index", row.displayIndex(), "rule", TownUiLegacyFacade.safeText(row.rule()))),
                    facade.dialogComponent("rules.delete-tooltip", Map.of("index", row.displayIndex())),
                    RuleEditorDialogRenderer.INLINE_RULE_WIDTH,
                    facade.dialogAction(player, session,
                            response -> deleteRule.accept(row.deleteTarget()))));
        }
        return actions;
    }

    private void addTownRule(Player player, UUID townId, long pageVersion,
                             DialogResponseView response) {
        String rule = TownUiLegacyFacade.responseText(response, "rule_text");
        if (rule.isBlank()) {
            facade.openNotice(player, facade.dialogText("rules.invalid-title"),
                    facade.dialogText("rules.invalid-empty"), facade.dialogText("common.back"),
                    "EDIT_TOWN_RULES", townId.toString());
            return;
        }
        updateTownRules(player, townId, pageVersion, rules -> {
            if (rules.size() >= 50) {
                throw new IllegalArgumentException(facade.dialogText("rules.limit-reached"));
            }
            List<String> updated = new ArrayList<>(rules);
            updated.add(rule);
            return updated;
        });
    }

    private void deleteTownRule(Player player,
                                RuleEditorDialogRenderer.DeleteTarget deleteTarget) {
        updateTownRules(player, deleteTarget.pageId(), deleteTarget.pageVersion(), rules -> {
            if (rules.size() <= 1) {
                throw new IllegalArgumentException(facade.dialogText("rules.minimum-one"));
            }
            if (deleteTarget.ruleIndex() < 0 || deleteTarget.ruleIndex() >= rules.size()) {
                throw new IllegalArgumentException(facade.dialogText("rules.delete-missing"));
            }
            if (!rules.get(deleteTarget.ruleIndex()).equals(deleteTarget.expectedRule())) {
                throw new IllegalArgumentException(facade.dialogText("rules.delete-conflict"));
            }
            List<String> updated = new ArrayList<>(rules);
            updated.remove(deleteTarget.ruleIndex());
            return updated;
        });
    }

    private void updateTownRules(Player player, UUID townId, long expectedVersion,
                                 Function<List<String>, List<String>> transform) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            if (town.version() != expectedVersion) {
                openTownRuleEditorRefreshNotice(player, town.id());
                return;
            }
            List<String> rules;
            try {
                rules = transform.apply(town.profile().rules());
            } catch (IllegalArgumentException exception) {
                facade.openNotice(player, facade.dialogText("rules.invalid-title"), exception.getMessage(),
                        facade.dialogText("common.back"), "EDIT_TOWN_RULES", town.id().toString());
                return;
            }
            ApplicationText profile = new ApplicationText(town.profile().name(),
                    town.profile().shortName(), town.profile().residenceName(),
                    town.profile().description(), rules);
            actions.updateTownProfile(player, town.id(), profile, expectedVersion, outcome -> {
                if (outcome.result().success()) {
                    openTownRuleEditor(player, outcome.value().id());
                } else if ("CONFLICT".equals(outcome.result().reason())) {
                    openTownRuleEditorRefreshNotice(player, town.id());
                } else {
                    facade.handleOutcome(player, outcome, ignored -> { });
                }
            });
        });
    }

    private void openTownRuleEditorRefreshNotice(Player player, UUID townId) {
        facade.openNotice(player, facade.dialogText("rules.refresh-title"),
                facade.dialogText("rules.refresh-message"), facade.dialogText("common.back"),
                "EDIT_TOWN_RULES", townId.toString());
    }

    public void openTransferRequest(Player player, UUID transferId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), governance -> {
            TransferSnapshot transfer = governance.pendingTransfer();
            if (transfer == null || !transfer.id().equals(transferId)) {
                facade.openNotice(player, facade.dialogText("notice.transfer-expired-title"),
                        facade.dialogText("notice.transfer-expired-message"),
                        facade.dialogText("common.back"), "MAIN", null);
                return;
            }
            List<MenuItem> items = List.of(
                    new MenuItem(4, facade.button(Material.NETHER_STAR,
                            facade.dialogText("transfer.summary-title"),
                            List.of(facade.dialogText("common.town", Map.of(
                                            "town", TownUiLegacyFacade.safeText(governance.townName()))),
                                    facade.dialogText("transfer.expires", Map.of(
                                            "time", TownUiLegacyFacade.safeText(transfer.expiresAt()))),
                                    facade.dialogText("transfer.consequence")), null, null)),
                    new MenuItem(11, facade.button(Material.LIME_CONCRETE,
                            facade.dialogText("transfer.accept"),
                            List.of(facade.dialogText("common.confirmation-required")),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":true")),
                    new MenuItem(15, facade.button(Material.RED_CONCRETE,
                            facade.dialogText("common.reject"),
                            List.of(facade.dialogText("tooltip.transfer.reject-close")),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":false")));
            facade.openMenu(player, 27, facade.dialogText("transfer.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void openVotes(Player player, UUID townId) {
        openVotes(player, townId, 0);
    }

    public void openVotes(Player player, UUID townId, int requestedPage) {
        int page = Math.max(0, requestedPage);
        runtime.read(player, () -> runtime.governance().listTownVotes(townId,
                player.getUniqueId(), true), votes -> {
            List<MenuItem> items = new ArrayList<>();
            List<VoteSnapshot> visible = TownUiLegacyFacade.page(votes, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                VoteSnapshot vote = visible.get(index);
                String target = vote.type() == VoteType.KICK_MEMBER
                        ? facade.displayName(vote.subjectId()) : facade.displayName(vote.candidateId());
                boolean pending = vote.viewerEligible() && !vote.viewerVoted();
                items.add(new MenuItem(index, facade.button(
                        pending ? Material.ENCHANTED_BOOK : Material.PAPER,
                        facade.dialogText(pending ? "votes.pending-entry-title" : "votes.entry-title",
                                Map.of("pending", facade.dialogText("votes.pending"),
                                        "type", voteLabel(vote.type()), "target", target)),
                        List.of(facade.dialogText("votes.entry.approve-count", Map.of(
                                        "yes", vote.yesVotes(), "required", vote.requiredYes())),
                                facade.dialogText("votes.entry.oppose-count",
                                        Map.of("no", vote.noVotes())),
                                facade.dialogText("common.expires",
                                        Map.of("time", vote.endsAt()))),
                        "VOTE_DETAIL", vote.id().toString())));
            }
            if (votes.isEmpty()) {
                items.add(new MenuItem(22, facade.button(Material.PAPER, facade.dialogText("votes.empty"),
                        List.of(facade.dialogText("votes.empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, facade.button(Material.ARROW, facade.dialogText("common.previous"), List.of(),
                        "VOTES_PAGE", townId + ":" + (page - 1))));
            }
            if (TownUiLegacyFacade.hasNext(votes, page, 8)) {
                items.add(new MenuItem(53, facade.button(Material.ARROW, facade.dialogText("common.next"), List.of(),
                        "VOTES_PAGE", townId + ":" + (page + 1))));
            }
            facade.openMenu(player, 54, facade.dialogText("votes.list-title", Map.of("page", page + 1)),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    public void openVote(Player player, UUID voteId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), governance -> {
            VoteSnapshot vote = governance.votes().stream().filter(item -> item.id().equals(voteId))
                    .findFirst().orElse(null);
            if (vote == null) {
                facade.openNotice(player, facade.dialogText("notice.vote-ended-title"),
                        facade.dialogText("notice.vote-ended-message"),
                        facade.dialogText("common.back"), "VOTES",
                        governance.townId().toString());
                return;
            }
            String target = vote.type() == VoteType.KICK_MEMBER
                    ? facade.displayName(vote.subjectId()) : facade.displayName(vote.candidateId());
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, facade.button(Material.PAPER,
                    facade.dialogText("votes.detail-type", Map.of("type", voteLabel(vote.type()))),
                    List.of(facade.dialogText("votes.status-line", Map.of("status",
                                    facade.dialogText("votes.status-" + vote.status().name()
                                            .toLowerCase(java.util.Locale.ROOT)))),
                            facade.dialogText("votes.target", Map.of("target", target)),
                            facade.dialogText("votes.voters", Map.of("count", vote.eligibleVoters())),
                            facade.dialogText("votes.threshold", Map.of("required", vote.requiredYes())),
                            facade.dialogText("votes.tally", Map.of("yes", vote.yesVotes(),
                                    "no", vote.noVotes())),
                            facade.dialogText("common.expires", Map.of("time", vote.endsAt()))),
                    null, null)));
            if (vote.viewerEligible() && !vote.viewerVoted()) {
                items.add(new MenuItem(11, facade.button(Material.LIME_CONCRETE,
                        facade.dialogText("votes.approve"),
                        List.of(facade.dialogText("votes.once")), "CAST_VOTE", vote.id() + ":true")));
                items.add(new MenuItem(15, facade.button(Material.RED_CONCRETE,
                        facade.dialogText("votes.reject"),
                        List.of(facade.dialogText("votes.once")), "CAST_VOTE", vote.id() + ":false")));
            } else {
                items.add(new MenuItem(13, facade.button(Material.GRAY_DYE,
                        vote.viewerVoted() ? facade.dialogText("votes.already-voted")
                                : facade.dialogText("votes.ineligible"),
                        List.of(), null, null)));
            }
            if (vote.createdBy().equals(player.getUniqueId())) {
                items.add(new MenuItem(18, facade.button(Material.BARRIER,
                        facade.dialogText("votes.cancel"),
                        List.of(facade.dialogText("votes.cancel-only"),
                                facade.dialogText("votes.cancel-irreversible")),
                        "CONFIRM_CANCEL_VOTE", vote.id().toString())));
            }
            facade.openMenu(player, 27, facade.dialogText("votes.detail-title"),
                    new DialogRoute("VOTES", vote.townId().toString()), items);
        });
    }

    private String voteLabel(VoteType type) {
        return facade.dialogText(type == VoteType.KICK_MEMBER
                ? "votes.type-kick" : "votes.type-replace-mayor");
    }

    public void requestMayorTransfer(Player mayor, UUID townId, UUID candidateId) {
        actions.requestMayorTransfer(mayor, townId, candidateId, outcome ->
                facade.handleOutcome(mayor, outcome, transfer -> {
            Player candidate = Bukkit.getPlayer(candidateId);
            if (candidate != null) {
                candidate.sendMessage(plugin.messages().component(
                                "chat.notification.transfer-request")
                        .append(facade.callbackButton(candidate, "chat.buttons.handle",
                                () -> openTransferRequest(candidate, transfer.id()))));
            }
            facade.openNotice(mayor, facade.dialogText("notice.transfer-requested-title"),
                    facade.dialogText("notice.transfer-requested-message", Map.of(
                            "expires", transfer.expiresAt())),
                    facade.dialogText("common.back"), "MAIN", null);
        }));
    }

    public void decideMayorTransfer(Player candidate, UUID transferId, boolean accept) {
        actions.decideMayorTransfer(candidate, transferId, accept, outcome ->
                facade.handleOutcome(candidate, outcome, transfer -> {
            Player oldMayor = Bukkit.getPlayer(transfer.requestedBy());
            if (oldMayor != null) {
                plugin.messages().send(oldMayor, accept
                        ? "chat.notification.transfer-accepted"
                        : "chat.notification.transfer-rejected");
            }
            facade.openNotice(candidate, accept ? facade.dialogText("notice.transfer-complete-title")
                            : facade.dialogText("notice.transfer-rejected-title"),
                    accept ? facade.dialogText("notice.transfer-complete-message")
                            : facade.dialogText("notice.transfer-rejected-message"),
                    facade.dialogText("common.back"), "MAIN", null);
        }));
    }

    public void acknowledgeRules(Player player, UUID townId, long revision) {
        actions.acknowledgeRules(player, townId, revision, outcome ->
                facade.handleOutcome(player, outcome, confirmed -> {
            facade.openNotice(player, facade.dialogText("notice.rules-confirmed-title"),
                    facade.dialogText("notice.rules-confirmed-message", Map.of(
                            "revision", confirmed)),
                    facade.dialogText("common.enter-town-service"), "MAIN", null);
        }));
    }

    public void createVote(Player player, UUID townId, VoteType type, UUID targetId) {
        actions.createVote(player, townId, type, targetId, outcome ->
                facade.handleOutcome(player, outcome, vote -> {
            notifyVoteCreated(player, vote);
            facade.openNotice(player, facade.dialogText("notice.vote-created-title"),
                    facade.dialogText("notice.vote-created-message", Map.of(
                            "voters", vote.eligibleVoters(), "required", vote.requiredYes())),
                    facade.dialogText("common.view-vote"),
                    "VOTE_DETAIL", vote.id().toString());
        }));
    }

    private void notifyVoteCreated(Player creator, VoteSnapshot vote) {
        runtime.read(creator, () -> runtime.governance().listVoteVoterIds(vote.id()), voters -> {
            String target = vote.type() == VoteType.KICK_MEMBER
                        ? facade.displayName(vote.subjectId()) : facade.displayName(vote.candidateId());
            for (UUID voterId : voters) {
                Player voter = Bukkit.getPlayer(voterId);
                if (voter == null || !voter.isOnline()) {
                    continue;
                }
                voter.sendMessage(plugin.messages().component("chat.notification.vote-created",
                                Map.of("creator", facade.displayName(vote.createdBy()),
                                        "type", voteLabel(vote.type()), "target", target,
                                        "ends", vote.endsAt(), "required", vote.requiredYes()))
                        .append(facade.callbackButton(voter, "chat.buttons.view-votes",
                                () -> openVote(voter, vote.id()))));
                facade.playSound(voter, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
            }
        });
    }

    public void sendVoteReminder(Player player, VoteSnapshot vote) {
        String target = vote.type() == VoteType.KICK_MEMBER
                ? facade.displayName(vote.subjectId()) : facade.displayName(vote.candidateId());
        player.sendMessage(plugin.messages().component("chat.notification.vote-created", Map.of(
                        "creator", facade.displayName(vote.createdBy()),
                        "type", voteLabel(vote.type()), "target", target,
                        "ends", vote.endsAt(), "required", vote.requiredYes()))
                .append(facade.callbackButton(player, "chat.buttons.view-votes",
                        () -> openVote(player, vote.id()))));
    }

    public void castVote(Player player, UUID voteId, boolean approve) {
        actions.castVote(player, voteId, approve, outcome ->
                facade.handleOutcome(player, outcome, vote -> {
            facade.openNotice(player, facade.dialogText("notice.vote-recorded-title"),
                    facade.dialogText("notice.vote-recorded-message", Map.of(
                            "yes", vote.yesVotes(), "required", vote.requiredYes(),
                            "status", facade.dialogText("votes.status-" + vote.status().name()
                                    .toLowerCase(java.util.Locale.ROOT)))), facade.dialogText("common.back"),
                    "VOTES", vote.townId().toString());
        }));
    }

    public void cancelVote(Player player, UUID voteId) {
        actions.cancelOwnVote(player, voteId, outcome ->
                facade.handleOutcome(player, outcome, vote -> {
                    facade.openNotice(player, facade.dialogText("notice.vote-cancelled-title"),
                            facade.dialogText("notice.vote-cancelled-message"),
                            facade.dialogText("common.back"), "VOTES",
                            vote.townId().toString());
                }));
    }
}

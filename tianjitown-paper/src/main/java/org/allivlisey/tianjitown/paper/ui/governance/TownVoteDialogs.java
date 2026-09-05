package org.allivlisey.tianjitown.paper.ui.governance;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Voting pages, decisions and voter notifications. */
public final class TownVoteDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    public TownVoteDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
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
                items.add(new MenuItem(index, presentation.button(
                        pending ? Material.ENCHANTED_BOOK : Material.PAPER,
                        presentation.dialogText(pending ? "votes.pending-entry-title" : "votes.entry-title",
                                Map.of("pending", presentation.dialogText("votes.pending"),
                                        "type", voteLabel(vote.type()), "target", target)),
                        List.of(presentation.dialogText("votes.entry.approve-count", Map.of(
                                        "yes", vote.yesVotes(), "required", vote.requiredYes())),
                                presentation.dialogText("votes.entry.oppose-count",
                                        Map.of("no", vote.noVotes())),
                                presentation.dialogText("common.expires",
                                        Map.of("time", vote.endsAt()))),
                        "VOTE_DETAIL", vote.id().toString())));
            }
            if (votes.isEmpty()) {
                items.add(new MenuItem(22, presentation.button(Material.PAPER, presentation.dialogText("votes.empty"),
                        List.of(presentation.dialogText("votes.empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, presentation.button(Material.ARROW, presentation.dialogText("common.previous"), List.of(),
                        "VOTES_PAGE", townId + ":" + (page - 1))));
            }
            if (TownUiLegacyFacade.hasNext(votes, page, 8)) {
                items.add(new MenuItem(53, presentation.button(Material.ARROW, presentation.dialogText("common.next"), List.of(),
                        "VOTES_PAGE", townId + ":" + (page + 1))));
            }
            presentation.openMenu(player, 54, presentation.dialogText("votes.list-title", Map.of("page", page + 1)),
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
                presentation.openNotice(player, presentation.dialogText("notice.vote-ended-title"),
                        presentation.dialogText("notice.vote-ended-message"),
                        presentation.dialogText("common.back"), "VOTES",
                        governance.townId().toString());
                return;
            }
            String target = vote.type() == VoteType.KICK_MEMBER
                    ? facade.displayName(vote.subjectId()) : facade.displayName(vote.candidateId());
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, presentation.button(Material.PAPER,
                    presentation.dialogText("votes.detail-type", Map.of("type", voteLabel(vote.type()))),
                    List.of(presentation.dialogText("votes.status-line", Map.of("status",
                                    presentation.dialogText("votes.status-" + vote.status().name()
                                            .toLowerCase(java.util.Locale.ROOT)))),
                            presentation.dialogText("votes.target", Map.of("target", target)),
                            presentation.dialogText("votes.voters", Map.of("count", vote.eligibleVoters())),
                            presentation.dialogText("votes.threshold", Map.of("required", vote.requiredYes())),
                            presentation.dialogText("votes.tally", Map.of("yes", vote.yesVotes(),
                                    "no", vote.noVotes())),
                            presentation.dialogText("common.expires", Map.of("time", vote.endsAt()))),
                    null, null)));
            if (vote.viewerEligible() && !vote.viewerVoted()) {
                items.add(new MenuItem(11, presentation.button(Material.LIME_CONCRETE,
                        presentation.dialogText("votes.approve"),
                        List.of(presentation.dialogText("votes.once")), "CAST_VOTE", vote.id() + ":true")));
                items.add(new MenuItem(15, presentation.button(Material.RED_CONCRETE,
                        presentation.dialogText("votes.reject"),
                        List.of(presentation.dialogText("votes.once")), "CAST_VOTE", vote.id() + ":false")));
            } else {
                items.add(new MenuItem(13, presentation.button(Material.GRAY_DYE,
                        vote.viewerVoted() ? presentation.dialogText("votes.already-voted")
                                : presentation.dialogText("votes.ineligible"),
                        List.of(), null, null)));
            }
            if (vote.createdBy().equals(player.getUniqueId())) {
                items.add(new MenuItem(18, presentation.button(Material.BARRIER,
                        presentation.dialogText("votes.cancel"),
                        List.of(presentation.dialogText("votes.cancel-only"),
                                presentation.dialogText("votes.cancel-irreversible")),
                        "CONFIRM_CANCEL_VOTE", vote.id().toString())));
            }
            presentation.openMenu(player, 27, presentation.dialogText("votes.detail-title"),
                    new DialogRoute("VOTES", vote.townId().toString()), items);
        });
    }

    private String voteLabel(VoteType type) {
        return presentation.dialogText(type == VoteType.KICK_MEMBER
                ? "votes.type-kick" : "votes.type-replace-mayor");
    }

    public void createVote(Player player, UUID townId, VoteType type, UUID targetId) {
        actions.createVote(player, townId, type, targetId, outcome ->
                facade.handleOutcome(player, outcome, vote -> {
            notifyVoteCreated(player, vote);
            presentation.openNotice(player, presentation.dialogText("notice.vote-created-title"),
                    presentation.dialogText("notice.vote-created-message", Map.of(
                            "voters", vote.eligibleVoters(), "required", vote.requiredYes())),
                    presentation.dialogText("common.view-vote"),
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
                        .append(presentation.callbackButton(voter, "chat.buttons.view-votes",
                                () -> openVote(voter, vote.id()))));
                presentation.playSound(voter, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
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
                .append(presentation.callbackButton(player, "chat.buttons.view-votes",
                        () -> openVote(player, vote.id()))));
    }

    public void castVote(Player player, UUID voteId, boolean approve) {
        actions.castVote(player, voteId, approve, outcome ->
                facade.handleOutcome(player, outcome, vote -> {
            presentation.openNotice(player, presentation.dialogText("notice.vote-recorded-title"),
                    presentation.dialogText("notice.vote-recorded-message", Map.of(
                            "yes", vote.yesVotes(), "required", vote.requiredYes(),
                            "status", presentation.dialogText("votes.status-" + vote.status().name()
                                    .toLowerCase(java.util.Locale.ROOT)))), presentation.dialogText("common.back"),
                    "VOTES", vote.townId().toString());
        }));
    }

    public void cancelVote(Player player, UUID voteId) {
        actions.cancelOwnVote(player, voteId, outcome ->
                facade.handleOutcome(player, outcome, vote -> {
                    presentation.openNotice(player, presentation.dialogText("notice.vote-cancelled-title"),
                            presentation.dialogText("notice.vote-cancelled-message"),
                            presentation.dialogText("common.back"), "VOTES",
                            vote.townId().toString());
                }));
    }

}

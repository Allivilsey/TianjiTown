package org.allivlisey.tianjitown.paper.ui.governance;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.governance.VoteType;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Owns governance, rule, and vote route protocols. */
public final class TownGovernanceUi {
    private final TownUiLegacyFacade facade;

    public TownGovernanceUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    public void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "GOVERNANCE_CENTER" -> facade.openGovernanceCenter(player);
                case "PENDING_CENTER" -> facade.openPendingCenter(player);
                case "TOWN_RULES" -> facade.openTownRules(player, townId(target));
                case "JOIN_TOWN_RULES" -> facade.openJoinTownRules(player, townId(target));
                case "EDIT_TOWN_RULES" -> facade.openTownRuleEditor(player, townId(target));
                case "VOTES" -> facade.openVotes(player, townId(target));
                case "VOTES_PAGE" -> openVotes(player, target);
                case "VOTE_DETAIL" -> facade.openVote(player, townId(target));
                case "CONFIRM_CREATE_VOTE" -> confirmCreateVote(player, target);
                case "CREATE_VOTE" -> createVote(player, target);
                case "CAST_VOTE" -> castVote(player, target);
                case "CONFIRM_CANCEL_VOTE" -> confirmCancelVote(player, target);
                case "CANCEL_VOTE" -> facade.cancelVote(player, townId(target));
                default -> throw new IllegalArgumentException("unsupported governance action: " + action);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    private void openVotes(Player player, String target) {
        TownPage page = TownPage.parse(target);
        facade.openVotes(player, page.townId(), page.page());
    }

    private void confirmCreateVote(Player player, String target) {
        VoteCreation vote = VoteCreation.parse(target);
        facade.openConfirmation(player, facade.dialogText("confirmation.create-vote-title"),
                "CREATE_VOTE", vote.encode(),
                facade.dialogText("confirmation.create-vote-consequence"), "MEMBER_DETAIL",
                vote.memberTarget());
    }

    private void createVote(Player player, String target) {
        VoteCreation vote = VoteCreation.parse(target);
        facade.createVote(player, vote.townId(), vote.type(), vote.subjectId());
    }

    private void castVote(Player player, String target) {
        VoteDecision vote = VoteDecision.parse(target);
        facade.castVote(player, vote.voteId(), vote.approve());
    }

    private void confirmCancelVote(Player player, String target) {
        UUID voteId = townId(target);
        facade.openConfirmation(player, facade.dialogText("confirmation.cancel-vote-title"),
                "CANCEL_VOTE", voteId.toString(),
                facade.dialogText("confirmation.cancel-vote-consequence"), "VOTE_DETAIL",
                voteId.toString());
    }

    private static UUID townId(String target) {
        if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid UUID route target");
        }
        return UUID.fromString(target);
    }

    public record TownPage(UUID townId, int page) {
        public static TownPage parse(String target) {
            String[] values = parts(target, 2);
            int page = Integer.parseInt(values[1]);
            if (page < 0) {
                throw new IllegalArgumentException("negative page");
            }
            return new TownPage(UUID.fromString(values[0]), page);
        }
    }

    public record VoteCreation(UUID townId, VoteType type, UUID subjectId, int memberPage) {
        public static VoteCreation parse(String target) {
            String[] values = parts(target, 4);
            int page = Integer.parseInt(values[3]);
            if (page < 0) {
                throw new IllegalArgumentException("negative page");
            }
            return new VoteCreation(UUID.fromString(values[0]), VoteType.valueOf(values[1]),
                    UUID.fromString(values[2]), page);
        }

        public String encode() {
            return townId + ":" + type + ":" + subjectId + ":" + memberPage;
        }

        public String memberTarget() {
            return townId + ":" + subjectId + ":" + memberPage;
        }
    }

    public record VoteDecision(UUID voteId, boolean approve) {
        public static VoteDecision parse(String target) {
            String[] values = parts(target, 2);
            if (!"true".equals(values[1]) && !"false".equals(values[1])) {
                throw new IllegalArgumentException("invalid vote decision");
            }
            return new VoteDecision(UUID.fromString(values[0]), Boolean.parseBoolean(values[1]));
        }
    }

    private static String[] parts(String target, int expected) {
        String[] values = target == null ? new String[0] : target.split(":", -1);
        if (values.length != expected) {
            throw new IllegalArgumentException("invalid route target");
        }
        return values;
    }
}

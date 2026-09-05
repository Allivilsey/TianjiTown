package org.allivlisey.tianjitown.paper.ui.membership;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Owns the join-application route protocol and its target codecs. */
public final class TownJoinApplicationUi {
    private final TownUiLegacyFacade facade;

    public TownJoinApplicationUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    public void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "JOIN_TOWNS" -> facade.openJoinTowns(player);
                case "JOIN_TOWNS_PAGE" -> facade.openJoinTowns(player, Page.parse(target).page());
                case "JOIN_TOWN" -> facade.openJoinTown(player, uuid(target));
                case "CONFIRM_APPLY_JOIN" -> confirmApply(player, uuid(target));
                case "APPLY_JOIN" -> facade.applyJoin(player, uuid(target));
                case "MY_JOIN_APPLICATIONS" -> facade.openMyJoinApplications(player);
                case "CONFIRM_CANCEL_JOIN" -> confirmCancel(player, uuid(target));
                case "CANCEL_JOIN" -> facade.cancelJoin(player, uuid(target));
                case "JOIN_APPLICATIONS" -> facade.openTownJoinApplications(player, uuid(target));
                case "JOIN_APPLICATIONS_PAGE" -> openTownApplications(player, TownPage.parse(target));
                case "JOIN_APPLICATION" -> facade.openTownJoinApplication(player, uuid(target));
                case "CONFIRM_APPROVE_JOIN" -> confirmDecision(player, uuid(target), true);
                case "APPROVE_JOIN" -> facade.approveJoin(player, uuid(target));
                case "CONFIRM_REJECT_JOIN" -> confirmDecision(player, uuid(target), false);
                case "REJECT_JOIN" -> facade.rejectJoin(player, uuid(target));
                default -> throw new IllegalArgumentException("unsupported join application action: " + action);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    private void confirmApply(Player player, UUID townId) {
        facade.openConfirmation(player, facade.dialogText("confirmation.apply-join-title"),
                "APPLY_JOIN", townId.toString(),
                facade.dialogText("confirmation.apply-join-consequence"), "JOIN_TOWN",
                townId.toString());
    }

    private void confirmCancel(Player player, UUID applicationId) {
        facade.openConfirmation(player, facade.dialogText("confirmation.cancel-join-title"),
                "CANCEL_JOIN", applicationId.toString(),
                facade.dialogText("confirmation.cancel-join-consequence"),
                "MY_JOIN_APPLICATIONS", null);
    }

    private void openTownApplications(Player player, TownPage target) {
        facade.openTownJoinApplications(player, target.townId(), target.page());
    }

    private void confirmDecision(Player player, UUID applicationId, boolean approve) {
        facade.openConfirmation(player, facade.dialogText(approve
                        ? "confirmation.approve-join-title" : "confirmation.reject-join-title"),
                approve ? "APPROVE_JOIN" : "REJECT_JOIN", applicationId.toString(),
                facade.dialogText(approve ? "confirmation.approve-join-consequence"
                        : "confirmation.reject-join-consequence"),
                "JOIN_APPLICATION", applicationId.toString());
    }

    private static UUID uuid(String target) {
        if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid UUID target");
        }
        return UUID.fromString(target);
    }

    public record Page(int page) {
        public static Page parse(String target) {
            int page = Integer.parseInt(target);
            if (page < 0) {
                throw new IllegalArgumentException("negative page");
            }
            return new Page(page);
        }
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

    private static String[] parts(String target, int expected) {
        String[] values = target == null ? new String[0] : target.split(":", -1);
        if (values.length != expected) {
            throw new IllegalArgumentException("invalid route target");
        }
        return values;
    }
}

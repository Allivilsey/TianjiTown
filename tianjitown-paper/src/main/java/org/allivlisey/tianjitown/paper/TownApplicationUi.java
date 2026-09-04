package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Owns the town-creation application routes after a draft has been saved. */
final class TownApplicationUi {
    private final TownUiLegacyFacade facade;

    TownApplicationUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "APPLICATION" -> facade.loadApplication(player, ApplicationTarget.parse(target).id());
                case "SELECT_SITE" -> facade.selectApplicationSite(player, ApplicationTarget.parse(target).id());
                case "PREVIEW_SITE" -> facade.previewApplicationSite(player, ApplicationTarget.parse(target).id());
                case "REMIND_INITIAL_MEMBERS" -> facade.remindInitialMembers(player, ApplicationTarget.parse(target).id());
                case "CONFIRM_SUBMIT" -> confirmSubmit(player, ApplicationTarget.parse(target).id());
                case "SUBMIT" -> facade.submitApplication(player, ApplicationTarget.parse(target).id());
                case "CONFIRM_CANCEL" -> confirmCancel(player, ApplicationTarget.parse(target).id());
                case "CANCEL" -> facade.cancelApplication(player, ApplicationTarget.parse(target).id());
                default -> throw new IllegalArgumentException("unsupported application action: " + action);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    void open(Player player, ApplicationSnapshot application) {
        facade.renderApplication(player, application);
    }

    private void confirmSubmit(Player player, UUID applicationId) {
        facade.openConfirmation(player, facade.dialogText("confirmation.submit-application-title"),
                "SUBMIT", applicationId.toString(),
                ApplicationSubmissionDialogRenderer.confirmationConsequence(facade.plugin().messages(),
                        facade.runtime().money(facade.runtime().applicationFeeMinor())),
                "APPLICATION", applicationId.toString());
    }

    private void confirmCancel(Player player, UUID applicationId) {
        facade.openConfirmation(player, facade.dialogText("confirmation.cancel-application-title"),
                "CANCEL", applicationId.toString(),
                facade.dialogText("confirmation.cancel-application-consequence"),
                "APPLICATION", applicationId.toString());
    }

    record ApplicationTarget(UUID id) {
        static ApplicationTarget parse(String target) {
            if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
                throw new IllegalArgumentException("invalid application UUID target");
            }
            return new ApplicationTarget(UUID.fromString(target));
        }
    }
}

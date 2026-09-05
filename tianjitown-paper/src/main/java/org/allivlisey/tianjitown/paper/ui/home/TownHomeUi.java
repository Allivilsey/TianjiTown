package org.allivlisey.tianjitown.paper.ui.home;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.station.ServiceStationController;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Owns home, town details, and personal-center action routes. */
public final class TownHomeUi {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final ServiceStationController serviceStations;

    public TownHomeUi(TownUiLegacyFacade facade, ServiceStationController serviceStations) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.serviceStations = serviceStations;
    }

    public void open(Player player) {
        facade.openMain(player);
    }

    public void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "GIVE_HANDBOOK" -> giveHandbook(player);
                case "TOWN" -> facade.openTown(player, uuid(target));
                case "PERSONAL_CENTER" -> facade.openPersonalCenter(player);
                case "CONFIRM_LEAVE" -> presentation.openConfirmation(player,
                        presentation.dialogText("confirmation.leave-town-title"), "LEAVE", target,
                        presentation.dialogText("confirmation.leave-town-consequence"), "TOWN", target);
                case "LEAVE" -> facade.leave(player, uuid(target));
                case "CONFIRM_DISBAND" -> presentation.openConfirmation(player,
                        presentation.dialogText("confirmation.disband-town-title"), "DISBAND", target,
                        presentation.dialogText("confirmation.disband-town-consequence"), "MAIN", null);
                case "DISBAND" -> facade.disband(player, target);
                default -> facade.openStaleMenu(player);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    private void giveHandbook(Player player) {
        if (serviceStations.giveHandbook(player, false)) {
            presentation.openNotice(player, presentation.dialogText("notice.handbook-received-title"),
                    facade.plugin().messages().text("handbook.received"),
                    presentation.dialogText("common.back"), "MAIN", null);
            return;
        }
        presentation.openNotice(player, presentation.dialogText("notice.handbook-cooldown-title"),
                presentation.dialogText("notice.handbook-cooldown-message"),
                presentation.dialogText("common.back"), "MAIN", null);
    }

    private static UUID uuid(String target) {
        if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid home route target");
        }
        return UUID.fromString(target);
    }
}

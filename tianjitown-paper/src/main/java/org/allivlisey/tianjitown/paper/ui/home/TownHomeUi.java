package org.allivlisey.tianjitown.paper.ui.home;
import org.allivlisey.tianjitown.paper.station.ServiceStationController;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Owns home, town details, and personal-center action routes. */
public final class TownHomeUi {
    private final TownUiLegacyFacade facade;
    private final ServiceStationController serviceStations;

    public TownHomeUi(TownUiLegacyFacade facade, ServiceStationController serviceStations) {
        this.facade = facade;
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
                case "CONFIRM_LEAVE" -> facade.openConfirmation(player,
                        facade.dialogText("confirmation.leave-town-title"), "LEAVE", target,
                        facade.dialogText("confirmation.leave-town-consequence"), "TOWN", target);
                case "LEAVE" -> facade.leave(player, uuid(target));
                case "CONFIRM_DISBAND" -> facade.openConfirmation(player,
                        facade.dialogText("confirmation.disband-town-title"), "DISBAND", target,
                        facade.dialogText("confirmation.disband-town-consequence"), "MAIN", null);
                case "DISBAND" -> facade.disband(player, target);
                default -> facade.openStaleMenu(player);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    private void giveHandbook(Player player) {
        if (serviceStations.giveHandbook(player, false)) {
            facade.openNotice(player, facade.dialogText("notice.handbook-received-title"),
                    facade.plugin().messages().text("handbook.received"),
                    facade.dialogText("common.back"), "MAIN", null);
            return;
        }
        facade.openNotice(player, facade.dialogText("notice.handbook-cooldown-title"),
                facade.dialogText("notice.handbook-cooldown-message"),
                facade.dialogText("common.back"), "MAIN", null);
    }

    private static UUID uuid(String target) {
        if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid home route target");
        }
        return UUID.fromString(target);
    }
}

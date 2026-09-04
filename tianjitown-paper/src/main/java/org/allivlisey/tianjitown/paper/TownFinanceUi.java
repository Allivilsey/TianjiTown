package org.allivlisey.tianjitown.paper;

import org.bukkit.entity.Player;

/** Owns finance routes and keeps malformed route data at the existing safe boundary. */
final class TownFinanceUi {
    private final TownUiLegacyFacade facade;

    TownFinanceUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    void route(Player player, String action, String target) {
        switch (action) {
            case "FINANCE" -> open(player, target);
            case "TAX_MENU" -> openTaxMenu(player);
            case "LEDGER" -> openLedger(player, target);
            case "DONATION_INPUT" -> startDonation(player);
            default -> facade.openStaleMenu(player);
        }
    }

    void open(Player player, String target) {
        try {
            facade.openFinance(player, Integer.parseInt(target));
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    void openTaxMenu(Player player) {
        facade.openTaxMenu(player);
    }

    void openLedger(Player player, String target) {
        try {
            facade.openLedger(player, Integer.parseInt(target));
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    void startDonation(Player player) {
        facade.startDonationInput(player);
    }
}

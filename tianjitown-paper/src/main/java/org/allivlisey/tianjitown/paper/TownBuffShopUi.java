package org.allivlisey.tianjitown.paper;

import org.bukkit.entity.Player;

/** Owns the buff-shop action protocol while the controller remains the public facade. */
final class TownBuffShopUi {
    private final TownUiLegacyFacade facade;

    TownBuffShopUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    void route(Player player, String action, String target) {
        switch (action) {
            case "BUFF_SHOP" -> open(player);
            case "BUFF_DURATIONS" -> openDurations(player, target);
            case "BUY_BUFF" -> buy(player, target);
            default -> facade.openStaleMenu(player);
        }
    }

    void open(Player player) {
        facade.openBuffShop(player);
    }

    void openDurations(Player player, String buffKey) {
        try {
            facade.openBuffDurations(player, buffKey);
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    void buy(Player player, String target) {
        try {
            facade.buyBuff(player, target);
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }
}

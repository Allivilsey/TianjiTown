package org.allivlisey.tianjitown.paper.ui.buff;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.bukkit.entity.Player;

/** Owns the buff-shop action protocol while the controller remains the public facade. */
public final class TownBuffShopUi {
    private final TownUiLegacyFacade facade;

    public TownBuffShopUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    public void route(Player player, String action, String target) {
        switch (action) {
            case "BUFF_SHOP" -> open(player);
            case "BUFF_DURATIONS" -> openDurations(player, target);
            case "BUY_BUFF" -> buy(player, target);
            default -> facade.openStaleMenu(player);
        }
    }

    public void open(Player player) {
        facade.openBuffShop(player);
    }

    public void openDurations(Player player, String buffKey) {
        try {
            facade.openBuffDurations(player, buffKey);
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    public void buy(Player player, String target) {
        try {
            facade.buyBuff(player, target);
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }
}

package org.allivlisey.tianjitown.paper.station;

import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Keeps the service-station interaction rules independent from Bukkit event plumbing.
 */
public final class ServiceStationInteractionPolicy {
    private ServiceStationInteractionPolicy() {
    }

    public static Outcome decide(boolean validStation, Action action, EquipmentSlot hand,
                          boolean administrator, boolean sneaking) {
        if (!validStation) {
            return Outcome.PASS_THROUGH;
        }
        if (action == Action.LEFT_CLICK_BLOCK) {
            return hand == EquipmentSlot.HAND && administrator && sneaking
                    ? Outcome.DESTROY : Outcome.CANCEL;
        }
        if (action == Action.RIGHT_CLICK_BLOCK) {
            return hand == EquipmentSlot.HAND ? Outcome.OPEN_MENU : Outcome.CANCEL;
        }
        return Outcome.PASS_THROUGH;
    }

    enum Outcome {
        PASS_THROUGH,
        CANCEL,
        OPEN_MENU,
        DESTROY
    }
}

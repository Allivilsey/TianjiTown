package org.allivlisey.tianjitown.paper.action;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.paper.action.TownActionSupport.*;
import static org.allivlisey.tianjitown.paper.action.TownActionSupport.*;

final class TownBuffActions {
    private final TownActionSupport support;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownBuffActions(TownActionSupport support) {
        this.support = support;
        this.plugin = support.plugin;
        this.runtime = support.runtime;
    }

    public void buyBuff(Player actor, String buffKey, BuffDurationOption duration,
                 Consumer<TownActionOutcome<CommerceRepository.BuffPurchase>> completion) {
        String action = "BUFF_BUY";
        if (support.rejectBeforeWrite(action, completion)) {
            return;
        }
        if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        runtime.buffs().buyBuffAction(actor, buffKey, duration, purchase -> completion.accept(
                        TownActionOutcome.success(TownActionResult.success(action,
                                Map.of("buff_key", buffKey, "buff_id", purchase.buff().buffId(),
                                        "level", purchase.buff().level(), "expires_at",
                                        purchase.buff().expiresAt(), "balance_minor",
                                        purchase.balanceAfterMinor())), purchase)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

    public void buyBuff(Player actor, String buffKey, int weeks, int level,
                 Consumer<TownActionOutcome<CommerceRepository.BuffPurchase>> completion) {
        String action = "BUFF_BUY";
        if (support.rejectBeforeWrite(action, completion)) {
            return;
        }
        if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        runtime.buffs().buyBuffAction(actor, buffKey, weeks, level,
                purchase -> completion.accept(TownActionOutcome.success(
                        TownActionResult.success(action, Map.of("buff_key", buffKey,
                                "buff_id", purchase.buff().buffId(), "level", level,
                                "weeks", weeks, "expires_at", purchase.buff().expiresAt(),
                                "balance_minor", purchase.balanceAfterMinor())), purchase)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

}

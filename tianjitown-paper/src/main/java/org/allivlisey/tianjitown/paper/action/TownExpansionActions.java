package org.allivlisey.tianjitown.paper.action;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.land.ExpansionDirection;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.paper.action.TownActionSupport.*;
import static org.allivlisey.tianjitown.paper.action.TownActionSupport.*;

final class TownExpansionActions {
    private final TownActionSupport support;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownExpansionActions(TownActionSupport support) {
        this.support = support;
        this.plugin = support.plugin;
        this.runtime = support.runtime;
    }

    public void expandTown(Player actor, ExpansionDirection direction,
                    Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        expandTown(actor, Map.of("direction", direction),
                (success, failure) -> runtime.expandAction(actor, direction, success, failure),
                completion);
    }

    public void expandTown(Player actor, int gridX, int gridZ,
                    Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        expandTown(actor, Map.of("grid_x", gridX, "grid_z", gridZ),
                (success, failure) -> runtime.expandAction(
                        actor, gridX, gridZ, success, failure), completion);
    }

    private void expandTown(Player actor, Map<String, ?> targetData,
                            ExpansionExecutor executor,
                            Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        String action = "TOWN_EXPAND";
        if (support.rejectBeforeWrite(action, completion)) {
            return;
        }
        if (!runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        executor.execute(operation -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("town_id", operation.townId());
            result.put("expansion_id", operation.expansionId());
            result.putAll(targetData);
            result.put("price_minor", operation.priceMinor());
            completion.accept(TownActionOutcome.success(
                    TownActionResult.success(action, result), operation));
        },
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

}

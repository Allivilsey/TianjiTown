package org.allivlisey.tianjitown.paper.action;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.paper.action.TownActionSupport.*;
import static org.allivlisey.tianjitown.paper.action.TownActionSupport.*;

final class TownQueryActions {
    private final TownActionSupport support;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownQueryActions(TownActionSupport support) {
        this.support = support;
        this.plugin = support.plugin;
        this.runtime = support.runtime;
    }

    public void queryActor(Player actor, Consumer<TownActionOutcome<Void>> completion) {
        String action = "QUERY_ACTOR";
        runtime.readAction(actor, () -> {
            TownRepository.PlayerDashboard dashboard = runtime.repository()
                    .dashboard(actor.getUniqueId());
            MemberGovernanceSnapshot governance = runtime.governance()
                    .dashboard(actor.getUniqueId()).orElse(null);
            EconomyRepository.TownFinance finance = runtime.finance()
                    .findFinanceByPlayer(actor.getUniqueId()).orElse(null);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("player_id", actor.getUniqueId());
            values.put("town_id", dashboard.town() == null ? "NONE" : dashboard.town().id());
            values.put("town_status", dashboard.town() == null
                    ? "NONE" : dashboard.town().status());
            values.put("role", governance == null ? "NONE" : governance.role());
            values.put("application_id", dashboard.application() == null
                    ? "NONE" : dashboard.application().id());
            values.put("application_status", dashboard.application() == null
                    ? "NONE" : dashboard.application().status());
            values.put("pending_join_count", dashboard.joinApplications().size());
            values.put("balance_minor", finance == null ? "NONE" : finance.balanceMinor());
            return values;
        }, values -> completion.accept(TownActionOutcome.success(
                TownActionResult.success(action, values), null)), exception ->
                completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

}

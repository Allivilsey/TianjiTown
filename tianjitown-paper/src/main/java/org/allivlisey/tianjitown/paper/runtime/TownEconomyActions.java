package org.allivlisey.tianjitown.paper.runtime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.paper.runtime.TownActionSupport.*;
import static org.allivlisey.tianjitown.paper.runtime.TownActionSupport.*;

final class TownEconomyActions {
    private final TownActionSupport support;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownEconomyActions(TownActionSupport support) {
        this.support = support;
        this.plugin = support.plugin;
        this.runtime = support.runtime;
    }

    public void changeTaxRate(Player actor, UUID townId, int basisPoints,
                       Consumer<TownActionOutcome<EconomyRepository.TaxChange>> completion) {
        String action = "TAX_RATE_CHANGE";
        if (!runtime.taxEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        if (!runtime.economySettings().allowsTaxRate(basisPoints)) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("maximum_bps",
                            runtime.economySettings().maximumTaxBps()))));
            return;
        }
        support.write(action, actor, () -> {
            EconomyRepository.TaxChange change = runtime.finance().changeTaxRate(townId,
                    actor.getUniqueId(), basisPoints, actor.getName(),
                    "镇长通过共享业务入口修改");
            runtime.refreshTaxPolicies();
            return change;
        }, change -> Map.of("town_id", change.townId(), "basis_points",
                change.basisPoints(), "revision", change.revision()), completion);
    }

    public void acknowledgeTaxRevision(Player actor, int revision,
                                Consumer<TownActionOutcome<Integer>> completion) {
        support.write("TAX_REVISION_ACKNOWLEDGE", actor, () -> {
            runtime.finance().acknowledgeTaxRevision(actor.getUniqueId(), revision);
            return revision;
        }, confirmed -> Map.of("player_id", actor.getUniqueId(), "revision", confirmed),
                completion);
    }

    public void donate(Player actor, long amountMinor,
                Consumer<TownActionOutcome<EconomyRepository.LedgerMutation>> completion) {
        String action = "TOWN_DONATE";
        if (support.rejectBeforeWrite(action, completion)) {
            return;
        }
        if (amountMinor <= 0) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("detail",
                            plugin.messages().plainText(DONATION_AMOUNT_POSITIVE)))));
            return;
        }
        if (!runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        runtime.donateAction(actor, amountMinor, mutation -> completion.accept(
                        TownActionOutcome.success(TownActionResult.success(action,
                                Map.of("town_id", mutation.townId(), "amount_minor", amountMinor,
                                        "balance_minor", mutation.balanceAfterMinor())), mutation)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

}

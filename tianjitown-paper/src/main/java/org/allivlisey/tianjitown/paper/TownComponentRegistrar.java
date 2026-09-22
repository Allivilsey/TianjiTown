package org.allivlisey.tianjitown.paper;
import org.allivlisey.tianjitown.paper.command.TownAdminTabCompleter;
import org.allivlisey.tianjitown.paper.runtime.GateStatus;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.ui.TownUiController;

import org.allivlisey.tianjitown.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.jobs.JobsIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.residence.ResidenceCommandGuard;
import org.allivlisey.tianjitown.integrations.residence.ResidenceDeletionGuard;
import org.allivlisey.tianjitown.integrations.residence.ResidenceLandProtectionService;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopTaxAdapter;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.allivlisey.tianjitown.paper.TownStartupCoordinator.*;

final class TownComponentRegistrar {
    private final TianjiTownPlugin plugin;
    private final TownStartupCoordinator startup;

    TownComponentRegistrar(TianjiTownPlugin plugin, TownStartupCoordinator startup) {
        this.plugin = plugin;
        this.startup = startup;
    }

    void activateRuntimeComponents(DatabaseGate candidate, List<String> previousDetails,
                                           String databaseDetail, long generation,
                                           TownRuntime runtime, TownActions actions,
                                           TownUiController ui,
                                           ResidenceLandProtectionService residenceProtection,
                                           Set<String> managedResidenceNames,
                                           Set<String> activeResidenceNames) {
        startup.databaseGate = candidate;
        Plugin quickShop = plugin.getServer().getPluginManager().getPlugin("QuickShop-Hikari");
        QuickShopTaxAdapter.Capability quickShopCapability = new QuickShopTaxAdapter(plugin,
                java.util.Objects.requireNonNull(quickShop, "QuickShop-Hikari"),
                runtime::quickShopTaxEnabled, runtime::taxPolicy, runtime::acceptQuickShopTax,
                runtime.wallet().scale(), startup.messages()::plainText).register();
        runtime.setQuickShopTaxAvailable(quickShopCapability.available());
        Plugin jobs = java.util.Objects.requireNonNull(
                plugin.getServer().getPluginManager().getPlugin("Jobs"), "Jobs");
        JobsIncomeTaxAdapter jobsAdapter = new JobsIncomeTaxAdapter(plugin, jobs,
                runtime::taxEnabled, runtime::acceptJobsIncomeTax,
                startup.messages()::plainText);
        startup.ownRuntimeHook(jobsAdapter);
        JobsIncomeTaxAdapter.Capability jobsCapability = jobsAdapter.register();
        Plugin globalMarketPlus = java.util.Objects.requireNonNull(
                plugin.getServer().getPluginManager().getPlugin("GlobalMarketPlus"),
                "GlobalMarketPlus");
        GlobalMarketPlusIncomeTaxAdapter.Capability globalMarketCapability =
                new GlobalMarketPlusIncomeTaxAdapter(plugin, globalMarketPlus,
                        runtime::taxEnabled,
                        runtime::acceptGlobalMarketPlusIncomeTax,
                        startup.messages()::plainText).register();
        startup.townRuntime = runtime;
        startup.townActions = actions;
        startup.townUi = ui;
        TownAdminTabCompleter completer = startup.townAdminTabCompleter;
        if (completer != null) {
            completer.start(runtime);
        }
        ui.listeners().forEach(listener -> plugin.getServer().getPluginManager().registerEvents(listener, plugin));
        plugin.getServer().getPluginManager().registerEvents(runtime.buffs(), plugin);
        runtime.buffs().registerHuskSyncHook();
        plugin.getServer().getPluginManager().registerEvents(runtime.bonuses(), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new ResidenceCommandGuard(plugin, managedResidenceNames::contains,
                        activeResidenceNames::contains,
                        startup.messages()::text, startup.messages()::plainText), plugin);
        plugin.getServer().getPluginManager().registerEvents(new ResidenceDeletionGuard(plugin,
                managedResidenceNames::contains, residenceProtection::internalMutation,
                runtime::reconcileAll, startup.messages()::text, startup.messages()::plainText), plugin);
        plugin.getServer().getPluginManager().registerEvents(
                new org.allivlisey.tianjitown.integrations.residence.ResidenceReservationGuard(
                        residenceProtection), plugin);
        startup.scheduler.runAsync(() -> {
            try {
                runtime.repository().listReservedTowns().forEach(town ->
                        residenceProtection.reserve(town.residenceName(), town.territory()));
                residenceProtection.reservationsLoaded();
                runtime.repository().listTowns(true).forEach(town -> {
                    managedResidenceNames.add(town.residenceName());
                    if (town.status() == org.allivlisey.tianjitown.core.town.TownStatus.ACTIVE) {
                        activeResidenceNames.add(town.residenceName());
                    }
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().severe(startup.plainText(RESIDENCE_NAMES_LOAD_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
        runtime.recoverStartupState();
        runtime.buffs().refreshAllPlayers();
        runtime.bonuses().refreshIndex();
        startup.scheduler.registerPeriodic(runtime);
        List<String> details = new ArrayList<>(previousDetails);
        details.add("OK " + databaseDetail);
        details.add(runtime.bonuses().lastDiagnostic().healthy()
                ? startup.messages().plainText(STARTUP_DIAGNOSTIC_PASSED)
                : "WARN " + runtime.bonuses().lastDiagnostic().detail()
                    + "；运行时已启用，待处理记录可通过诊断和恢复入口处理");
        details.add((quickShopCapability.available() ? "OK " : "WARN ")
                + quickShopCapability.detail());
        details.add((jobsCapability.available() ? "OK " : "WARN ")
                + jobsCapability.detail());
        details.add((globalMarketCapability.available() ? "OK " : "WARN ")
                + globalMarketCapability.detail());
        details.add(startup.messages().plainText(WORLD_BORDER_READY));
        details.add(startup.messages().plainText(DIALOG_UI_READY));
        details.add(startup.messages().plainText(RUNTIME_FEATURES_READY));
        startup.gateStatus.set(new GateStatus(GateStatus.State.READY, details));
        plugin.getLogger().info(startup.plainText(RUNTIME_STARTED));
    }

}

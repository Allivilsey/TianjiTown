package org.allivlisey.tianjitown.paper;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.GateStatus;
import org.allivlisey.tianjitown.paper.runtime.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.ui.TownUiController;

import org.bukkit.plugin.java.JavaPlugin;


/** Paper entry point; startup and task ownership live in lifecycle components. */
public final class TianjiTownPlugin extends JavaPlugin {
    private final TownStartupCoordinator startup = new TownStartupCoordinator(this);

    @Override
    public void onEnable() {
        startup.onEnable();
    }

    @Override
    public void onDisable() {
        startup.onDisable();
    }

    public GateStatus gateStatus() {
        return startup.gateStatus();
    }

    public TownRuntime townRuntime() {
        return startup.townRuntime();
    }

    public TownUiController townUi() {
        return startup.townUi();
    }

    public TownActions townActions() {
        return startup.townActions();
    }

    public PluginMessages messages() {
        return startup.messages();
    }

    public void reloadMessages() {
        startup.reloadMessages();
    }

    public boolean runAsync(Runnable task) {
        return startup.scheduler.runAsync(task);
    }

    public boolean runMain(Runnable task) {
        return startup.scheduler.runMain(task);
    }

    public boolean runMainLater(Runnable task, long delayTicks) {
        return startup.scheduler.runMainLater(task, delayTicks);
    }

}

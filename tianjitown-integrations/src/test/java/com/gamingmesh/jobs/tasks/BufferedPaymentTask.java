package com.gamingmesh.jobs.tasks;

import org.allivlisey.tianjitown.integrations.jobs.JobsPaymentObserverTest.Economy;
import org.bukkit.OfflinePlayer;

/** Minimal fixture for the verified Jobs 5.2.6.6 payment call site. */
public final class BufferedPaymentTask {
    public static boolean pay(Economy economy, OfflinePlayer player, double amount) {
        return economy.depositPlayer(player, amount);
    }
}

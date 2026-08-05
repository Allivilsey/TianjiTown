package cn.tianji.town.integrations.residence;

import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

public final class ResidenceCommandGuard implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase(Locale.ROOT);
        if (!command.startsWith("/res ") && !command.equals("/res")
                && !command.startsWith("/residence ") && !command.equals("/residence")) {
            return;
        }
        ResidenceManager manager = (ResidenceManager) ResidenceApi.getResidenceManager();
        ClaimedResidence current = manager.getByLoc(event.getPlayer().getLocation());
        boolean insideSystemResidence = current != null
                && current.getName().toLowerCase(Locale.ROOT).startsWith("tt_");
        if (insideSystemResidence || command.contains("tt_")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTianjiTown 系统领地不能通过 Residence 命令管理。");
        }
    }
}

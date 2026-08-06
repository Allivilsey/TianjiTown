package cn.tianji.town.integrations.residence;

import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

public final class ResidenceCommandGuard implements Listener {
    private final Predicate<String> managedName;

    public ResidenceCommandGuard(Predicate<String> managedName) {
        this.managedName = Objects.requireNonNull(managedName, "managedName");
    }

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
                && current.isServerLand()
                && managedName.test(current.getName().toLowerCase(Locale.ROOT));
        Predicate<String> protectedName = name -> {
            if (!managedName.test(name)) {
                return false;
            }
            ClaimedResidence residence = manager.getByName(name);
            return residence != null && residence.isServerLand();
        };
        if (insideSystemResidence || mentionsManagedName(command, protectedName)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTianjiTown 系统领地不能通过 Residence 命令管理。");
        }
    }

    static boolean mentionsManagedName(String command, Predicate<String> managedName) {
        String normalized = command.toLowerCase(Locale.ROOT).strip();
        return Arrays.stream(normalized.split("\\s+")).skip(1).anyMatch(managedName);
    }
}

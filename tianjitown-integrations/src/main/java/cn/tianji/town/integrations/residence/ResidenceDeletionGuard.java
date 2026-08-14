package cn.tianji.town.integrations.residence;

import com.bekvon.bukkit.residence.event.ResidenceAreaDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class ResidenceDeletionGuard implements Listener {
    private final Plugin owner;
    private final Predicate<String> managedName;
    private final BooleanSupplier internalMutation;
    private final Runnable recovery;

    public ResidenceDeletionGuard(Plugin owner, Predicate<String> managedName,
                                  BooleanSupplier internalMutation, Runnable recovery) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.managedName = Objects.requireNonNull(managedName, "managedName");
        this.internalMutation = Objects.requireNonNull(internalMutation, "internalMutation");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onResidenceDelete(ResidenceDeleteEvent event) {
        protect(event.getResidence(), event.getPlayer(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAreaDelete(ResidenceAreaDeleteEvent event) {
        protect(event.getResidence(), event.getPlayer(), event::setCancelled);
    }

    private void protect(ClaimedResidence residence, Player source,
                         java.util.function.Consumer<Boolean> cancellation) {
        if (residence == null || internalMutation.getAsBoolean()
                || !managedName.test(residence.getName().toLowerCase(Locale.ROOT))) {
            return;
        }
        cancellation.accept(true);
        String message = "TianjiTown 小镇领地不能从外部删除，系统已自动恢复保护。";
        if (source != null) {
            source.sendMessage("§c" + message);
        } else {
            owner.getLogger().warning(message + " 来源=非玩家，领地=" + residence.getName());
        }
        Server server = owner.getServer();
        server.getScheduler().runTask(owner, recovery);
    }
}

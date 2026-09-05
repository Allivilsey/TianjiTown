package org.allivlisey.tianjitown.paper.buff;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Optional HuskSync 3 integration, without bundling or requiring its API. */
final class HuskSyncBuffHook {
    private HuskSyncBuffHook() {}

    static boolean register(TianjiTownPlugin plugin, Listener listener, Consumer<Player> complete) {
        Plugin huskSync = plugin.getServer().getPluginManager().getPlugin("HuskSync");
        if (huskSync == null || !huskSync.isEnabled()) {
            return false;
        }
        try {
            ClassLoader loader = huskSync.getClass().getClassLoader();
            Class<? extends Event> eventType = Class.forName(
                    "net.william278.husksync.event.BukkitSyncCompleteEvent", true, loader)
                    .asSubclass(Event.class);
            Method getUser = eventType.getMethod("getUser");
            Method getPlayer = Class.forName("net.william278.husksync.user.BukkitUser", true, loader)
                    .getMethod("getPlayer");
            plugin.getServer().getPluginManager().registerEvent(eventType, listener,
                    EventPriority.MONITOR, (ignored, event) -> {
                        if (!eventType.isInstance(event)) {
                            return;
                        }
                        try {
                            complete.accept((Player) getPlayer.invoke(getUser.invoke(event)));
                        } catch (ReflectiveOperationException exception) {
                            throw new EventException(exception);
                        }
                    }, plugin);
            return true;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError exception) {
            throw new IllegalStateException("Cannot register HuskSync Buff synchronization hook", exception);
        }
    }
}

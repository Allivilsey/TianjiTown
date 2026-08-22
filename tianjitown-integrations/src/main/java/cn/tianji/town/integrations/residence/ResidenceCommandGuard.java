package cn.tianji.town.integrations.residence;

import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

public final class ResidenceCommandGuard implements Listener {
    private final Predicate<String> managedName;
    private final java.util.function.Consumer<String> failureLogger;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public ResidenceCommandGuard(Predicate<String> managedName) {
        this(managedName, ignored -> {
        });
    }

    public ResidenceCommandGuard(Plugin owner, Predicate<String> managedName) {
        this(managedName, message -> owner.getLogger().severe(message));
    }

    ResidenceCommandGuard(Predicate<String> managedName,
                          java.util.function.Consumer<String> failureLogger) {
        this.managedName = Objects.requireNonNull(managedName, "managedName");
        this.failureLogger = Objects.requireNonNull(failureLogger, "failureLogger");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase(Locale.ROOT);
        if (!command.startsWith("/res ") && !command.equals("/res")
                && !command.startsWith("/residence ") && !command.equals("/residence")) {
            return;
        }
        try {
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
                event.getPlayer().sendMessage(
                        "§cTianjiTown 系统领地不能通过 Residence 命令管理。");
            }
        } catch (RuntimeException | LinkageError exception) {
            // 依赖失效时按保护优先原则拒绝 Residence 写命令。
            try {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cResidence 当前不可用，领地命令已安全拒绝。");
            } catch (RuntimeException | LinkageError ignored) {
                // 事件对象本身已经失效时只能停止继续处理。
            }
            if (failureLogged.compareAndSet(false, true)) {
                try {
                    failureLogger.accept("Residence 命令保护异常，已按失败关闭策略拒绝命令: "
                            + safeMessage(exception) + "；同类后续错误将被抑制");
                } catch (RuntimeException | LinkageError ignored) {
                    // 故障记录器失效时仍不能污染 Paper 事件循环。
                }
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    static boolean mentionsManagedName(String command, Predicate<String> managedName) {
        String normalized = command.toLowerCase(Locale.ROOT).strip();
        return Arrays.stream(normalized.split("\\s+")).skip(1).anyMatch(managedName);
    }
}

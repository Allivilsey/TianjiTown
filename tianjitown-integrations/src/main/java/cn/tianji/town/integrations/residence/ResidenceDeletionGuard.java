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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class ResidenceDeletionGuard implements Listener {
    private final Plugin owner;
    private final Predicate<String> managedName;
    private final BooleanSupplier internalMutation;
    private final Runnable recovery;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public ResidenceDeletionGuard(Plugin owner, Predicate<String> managedName,
                                  BooleanSupplier internalMutation, Runnable recovery) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.managedName = Objects.requireNonNull(managedName, "managedName");
        this.internalMutation = Objects.requireNonNull(internalMutation, "internalMutation");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onResidenceDelete(ResidenceDeleteEvent event) {
        try {
            protect(event.getResidence(), event.getPlayer(), event::setCancelled);
        } catch (RuntimeException | LinkageError exception) {
            failClosed(event::setCancelled, exception);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAreaDelete(ResidenceAreaDeleteEvent event) {
        try {
            protect(event.getResidence(), event.getPlayer(), event::setCancelled);
        } catch (RuntimeException | LinkageError exception) {
            failClosed(event::setCancelled, exception);
        }
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
        if (owner.isEnabled()) {
            server.getScheduler().runTask(owner, () -> {
                if (!owner.isEnabled()) {
                    return;
                }
                try {
                    recovery.run();
                } catch (RuntimeException | LinkageError exception) {
                    logFailure("Residence 删除后的对账恢复失败", exception);
                }
            });
        }
    }

    private void failClosed(java.util.function.Consumer<Boolean> cancellation,
                            Throwable throwable) {
        try {
            cancellation.accept(true);
        } catch (RuntimeException | LinkageError cancellationFailure) {
            throwable.addSuppressed(cancellationFailure);
        }
        logFailure("Residence 删除保护异常，已按失败关闭策略取消删除", throwable);
    }

    private void logFailure(String context, Throwable throwable) {
        if (failureLogged.compareAndSet(false, true)) {
            try {
                owner.getLogger().severe(context + ": " + safeMessage(throwable)
                        + "；同类后续错误将被抑制");
            } catch (RuntimeException | LinkageError ignored) {
                // 故障记录器失效时仍不能污染 Paper 事件循环。
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }
}

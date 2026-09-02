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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public final class ResidenceCommandGuard implements Listener {
    private static final String COMMAND_GUARD_FAILURE_MESSAGE =
            "log.residence.command-guard-failure";
    private final Predicate<String> managedName;
    private final Predicate<String> activeName;
    private final java.util.function.Consumer<String> failureLogger;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;
    private final BiFunction<String, Map<String, ?>, String> logMessageResolver;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public ResidenceCommandGuard(Predicate<String> managedName) {
        this(managedName, managedName, ignored -> { }, ResidenceCommandGuard::fallbackMessage,
                ResidenceCommandGuard::fallbackMessage);
    }

    public ResidenceCommandGuard(Plugin owner, Predicate<String> managedName) {
        this(managedName, managedName, message -> owner.getLogger().severe(message),
                ResidenceCommandGuard::fallbackMessage, ResidenceCommandGuard::fallbackMessage);
    }

    public ResidenceCommandGuard(Plugin owner, Predicate<String> managedName,
                                 BiFunction<String, Map<String, ?>, String> messageResolver) {
        this(managedName, managedName, message -> owner.getLogger().severe(message),
                messageResolver, messageResolver);
    }

    public ResidenceCommandGuard(Plugin owner, Predicate<String> managedName,
                                 Predicate<String> activeName,
                                 BiFunction<String, Map<String, ?>, String> messageResolver) {
        this(managedName, activeName, message -> owner.getLogger().severe(message),
                messageResolver, messageResolver);
    }

    public ResidenceCommandGuard(Plugin owner, Predicate<String> managedName,
                                 Predicate<String> activeName,
                                 BiFunction<String, Map<String, ?>, String> messageResolver,
                                 BiFunction<String, Map<String, ?>, String> logMessageResolver) {
        this(managedName, activeName, message -> owner.getLogger().severe(message),
                messageResolver, logMessageResolver);
    }

    ResidenceCommandGuard(Predicate<String> managedName,
                           java.util.function.Consumer<String> failureLogger) {
        this(managedName, managedName, failureLogger, ResidenceCommandGuard::fallbackMessage,
                ResidenceCommandGuard::fallbackMessage);
    }

    private ResidenceCommandGuard(Predicate<String> managedName,
                                  Predicate<String> activeName,
                                  java.util.function.Consumer<String> failureLogger,
                                  BiFunction<String, Map<String, ?>, String> messageResolver,
                                  BiFunction<String, Map<String, ?>, String> logMessageResolver) {
        this.managedName = Objects.requireNonNull(managedName, "managedName");
        this.activeName = Objects.requireNonNull(activeName, "activeName");
        this.failureLogger = Objects.requireNonNull(failureLogger, "failureLogger");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        this.logMessageResolver = Objects.requireNonNull(logMessageResolver, "logMessageResolver");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        ParsedCommand parsed = parse(event.getMessage());
        if (parsed == null || !parsed.protectedOperation()) {
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
            if (parsed.teleport()) {
                String target = parsed.targetName();
                ClaimedResidence destination = target == null ? null : manager.getByName(target);
                if (target != null && activeName.test(target) && managedName.test(target)
                        && destination != null
                        && destination.isServerLand()) {
                    return;
                }
                event.setCancelled(true);
                event.getPlayer().sendMessage(messageResolver.apply(
                        "chat.residence.command-blocked", Map.of()));
                return;
            }
            if (insideSystemResidence || mentionsManagedName(parsed.normalized(), protectedName)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(messageResolver.apply(
                        "chat.residence.command-blocked", Map.of()));
            }
        } catch (RuntimeException | LinkageError exception) {
            // 依赖失效时按保护优先原则拒绝 Residence 写命令。
            try {
                event.setCancelled(true);
                event.getPlayer().sendMessage(messageResolver.apply(
                        "chat.residence.unavailable", Map.of()));
            } catch (RuntimeException | LinkageError ignored) {
                // 事件对象本身已经失效时只能停止继续处理。
            }
            if (failureLogged.compareAndSet(false, true)) {
                try {
                    failureLogger.accept(resolveFailureMessage(logMessageResolver, exception));
                } catch (RuntimeException | LinkageError ignored) {
                    // 故障记录器失效时仍不能污染 Paper 事件循环。
                }
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String detail = message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
        return detail.replace('&', '＆').replace('§', '�');
    }

    static String resolveFailureMessage(BiFunction<String, Map<String, ?>, String> resolver,
                                        Throwable exception) {
        return resolveMessage(resolver, COMMAND_GUARD_FAILURE_MESSAGE,
                Map.of("detail", safeMessage(exception)));
    }

    private static String resolveMessage(BiFunction<String, Map<String, ?>, String> resolver,
                                         String key, Map<String, ?> placeholders) {
        try {
            String resolved = resolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError ignored) {
            return key + " " + placeholders;
        }
    }

    private static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
    }

    static boolean mentionsManagedName(String command, Predicate<String> managedName) {
        String normalized = command.toLowerCase(Locale.ROOT).strip();
        return Arrays.stream(normalized.split("\\s+")).skip(1).anyMatch(managedName);
    }

    static ParsedCommand parse(String command) {
        if (command == null) {
            return null;
        }
        String normalized = command.toLowerCase(Locale.ROOT).strip();
        String[] tokens = normalized.split("\\s+");
        if (tokens.length == 0 || (!tokens[0].equals("/res")
                && !tokens[0].equals("/residence"))) {
            return null;
        }
        if (tokens.length < 2) {
            return new ParsedCommand(normalized, false, false, null);
        }
        String subcommand = tokens[1];
        if (subcommand.equals("tp") || subcommand.equals("teleport")) {
            return new ParsedCommand(normalized, true, true,
                    tokens.length == 3 ? tokens[2] : null);
        }
        // Residence 子命令默认按写操作处理；只有明确列出的查询命令放行。
        // 这样新增的修改子命令不会因为保护列表过时而绕过小镇领地保护。
        boolean write = !READ_ONLY_SUBCOMMANDS.contains(subcommand);
        return new ParsedCommand(normalized, write, false, null);
    }

    private static final Set<String> READ_ONLY_SUBCOMMANDS = Set.of(
            "list", "info", "check", "limits", "version", "help");

    record ParsedCommand(String normalized, boolean protectedOperation, boolean teleport,
                         String targetName) {
    }
}

package org.allivlisey.tianjitown.integrations.vault;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

public final class VaultEconomyProbe {
    private static final String NOT_REGISTERED_MESSAGE =
            "diagnostic.vault.economy-not-registered";
    private static final String ENABLED_MESSAGE = "diagnostic.vault.economy-provider-enabled";
    private static final String DISABLED_MESSAGE = "diagnostic.vault.economy-provider-disabled";
    private static final String PROBE_FAILURE_MESSAGE = "diagnostic.vault.economy-probe-failure";
    private final Server server;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    public VaultEconomyProbe(Server server) {
        this(server, VaultEconomyProbe::fallbackMessage);
    }

    public VaultEconomyProbe(Server server,
                             BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.server = Objects.requireNonNull(server, "server");
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
    }

    public Result verify() {
        try {
            RegisteredServiceProvider<Economy> registration =
                    server.getServicesManager().getRegistration(Economy.class);
            if (registration == null || registration.getProvider() == null) {
                return new Result(false, null, resolveMessage(NOT_REGISTERED_MESSAGE, Map.of()));
            }
            Economy economy = registration.getProvider();
            boolean enabled = economy.isEnabled();
            return new Result(enabled, safeText(economy.getName()),
                    resolveMessage(enabled ? ENABLED_MESSAGE : DISABLED_MESSAGE, Map.of()));
        } catch (RuntimeException | LinkageError exception) {
            return new Result(false, null, resolveMessage(PROBE_FAILURE_MESSAGE,
                    Map.of("detail", safeMessage(exception))));
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String detail = message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
        return safeText(detail);
    }

    private static String safeText(String text) {
        return text == null ? null : text.replace('&', '＆').replace('§', '�');
    }

    private String resolveMessage(String key, Map<String, ?> placeholders) {
        try {
            String resolved = messageResolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError exception) {
            return key + " " + placeholders;
        }
    }

    private static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
    }

    public record Result(boolean healthy, String provider, String message) {
    }
}

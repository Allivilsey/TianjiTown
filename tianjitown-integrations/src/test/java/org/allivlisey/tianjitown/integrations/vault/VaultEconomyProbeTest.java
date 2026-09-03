package cn.tianji.town.integrations.vault;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultEconomyProbeTest {
    @Test
    void resolvesEveryProbeOutcomeThroughTheInjectedMessageResolver() {
        BiFunction<String, Map<String, ?>, String> resolver = (key, placeholders) -> switch (key) {
            case "diagnostic.vault.economy-not-registered" -> "missing economy service";
            case "diagnostic.vault.economy-provider-enabled" -> "provider enabled";
            case "diagnostic.vault.economy-provider-disabled" -> "provider disabled";
            case "diagnostic.vault.economy-probe-failure" ->
                    "probe failed: " + placeholders.get("detail");
            default -> throw new AssertionError("unexpected message key: " + key);
        };

        VaultEconomyProbe.Result missing = new VaultEconomyProbe(server(null), resolver)
                .verify();
        assertFalse(missing.healthy());
        assertNull(missing.provider());
        assertEquals("missing economy service", missing.message());

        Economy enabledEconomy = economy(true);
        VaultEconomyProbe.Result enabled = new VaultEconomyProbe(
                server(registration(enabledEconomy)), resolver).verify();
        assertTrue(enabled.healthy());
        assertEquals("ExampleEconomy", enabled.provider());
        assertEquals("provider enabled", enabled.message());

        Economy disabledEconomy = economy(false);
        VaultEconomyProbe.Result disabled = new VaultEconomyProbe(
                server(registration(disabledEconomy)), resolver).verify();
        assertFalse(disabled.healthy());
        assertEquals("ExampleEconomy", disabled.provider());
        assertEquals("provider disabled", disabled.message());
    }

    @Test
    void sanitizesProviderFailureDetailsBeforeResolvingTheMessage() {
        Economy failingEconomy = proxy(Economy.class, (ignored, method, arguments) -> {
            if (method.getName().equals("isEnabled")) {
                throw new NoSuchMethodError("boom&§");
            }
            if (method.getName().equals("getName")) {
                return "ExampleEconomy";
            }
            return defaultValue(method.getReturnType());
        });

        VaultEconomyProbe.Result result = new VaultEconomyProbe(
                server(registration(failingEconomy)), (key, placeholders) -> {
                    assertEquals("diagnostic.vault.economy-probe-failure", key);
                    assertEquals("boom＆�", placeholders.get("detail"));
                    return "custom probe failure: " + placeholders.get("detail");
                }).verify();

        assertFalse(result.healthy());
        assertNull(result.provider());
        assertEquals("custom probe failure: boom＆�", result.message());
    }

    @Test
    void sanitizesTheProviderNameBeforeReturningTheDiagnosticResult() {
        Economy economy = proxy(Economy.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "isEnabled" -> true;
            case "getName" -> "Example&§Economy";
            default -> defaultValue(method.getReturnType());
        });

        VaultEconomyProbe.Result result = new VaultEconomyProbe(
                server(registration(economy)), (key, placeholders) -> key).verify();

        assertTrue(result.healthy());
        assertEquals("Example＆�Economy", result.provider());
    }

    @Test
    void fallsBackToTheStableMessageKeyWhenNoResolverIsProvided() {
        VaultEconomyProbe.Result result = new VaultEconomyProbe(server(null)).verify();

        assertFalse(result.healthy());
        assertEquals("diagnostic.vault.economy-not-registered", result.message());
    }

    private static Economy economy(boolean enabled) {
        return proxy(Economy.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "isEnabled" -> enabled;
            case "getName" -> "ExampleEconomy";
            default -> defaultValue(method.getReturnType());
        });
    }

    private static RegisteredServiceProvider<Economy> registration(Economy economy) {
        return new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal,
                null);
    }

    private static Server server(RegisteredServiceProvider<Economy> registration) {
        ServicesManager services = proxy(ServicesManager.class,
                (ignored, method, arguments) -> method.getName().equals("getRegistration")
                        ? registration : defaultValue(method.getReturnType()));
        return proxy(Server.class, (ignored, method, arguments) ->
                method.getName().equals("getServicesManager")
                        ? services : defaultValue(method.getReturnType()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }
}

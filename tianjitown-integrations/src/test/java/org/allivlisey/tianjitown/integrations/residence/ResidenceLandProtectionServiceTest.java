package org.allivlisey.tianjitown.integrations.residence;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidenceLandProtectionServiceTest {
    @Test
    void returnsSafeFailureWhenResidenceIsDisabled() {
        Plugin residence = proxy(Plugin.class, (ignored, method, arguments) ->
                method.getName().equals("isEnabled") ? false : defaultValue(method.getReturnType()));
        PluginManager plugins = proxy(PluginManager.class, (ignored, method, arguments) ->
                method.getName().equals("getPlugin") ? residence
                        : defaultValue(method.getReturnType()));
        Server server = proxy(Server.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getPluginManager" -> plugins;
            case "isPrimaryThread" -> true;
            default -> defaultValue(method.getReturnType());
        });
        ResidenceLandProtectionService service = new ResidenceLandProtectionService(server,
                new HashSet<>());
        InitialTerritory territory = new InitialTerritory(
                new ChunkPosition(UUID.randomUUID(), "world", 10, 20));

        LandProtectionService.Result result = service.remove("SKY", territory);

        assertFalse(result.success());
        assertEquals(LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE, result.code());
        assertEquals("RESIDENCE_PLUGIN_UNAVAILABLE", result.parameters().get("detail"));
    }

    @Test
    void reportsWorldUnavailableAsStructuredCollisionFailure() {
        Server server = proxy(Server.class, (ignored, method, arguments) -> switch (
                method.getName()) {
            case "getWorld", "isPrimaryThread" -> method.getName().equals("isPrimaryThread")
                    ? true : null;
            default -> defaultValue(method.getReturnType());
        });
        ResidenceLandProtectionService service = new ResidenceLandProtectionService(server,
                new HashSet<>());
        InitialTerritory territory = new InitialTerritory(
                new ChunkPosition(UUID.randomUUID(), "unloaded-world", 10, 20));

        LandProtectionService.Collision collision = service.findCollision(territory);

        assertTrue(collision.occupied());
        assertEquals(LandProtectionService.ResultCode.WORLD_UNLOADED, collision.code());
        assertEquals("unloaded-world", collision.parameters().get("world"));
        assertTrue(collision.residenceName() == null);
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

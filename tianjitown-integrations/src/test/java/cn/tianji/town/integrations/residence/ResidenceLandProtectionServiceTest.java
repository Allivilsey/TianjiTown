package cn.tianji.town.integrations.residence;

import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.ports.LandProtectionService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(result.message().contains("完整重启服务端"));
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

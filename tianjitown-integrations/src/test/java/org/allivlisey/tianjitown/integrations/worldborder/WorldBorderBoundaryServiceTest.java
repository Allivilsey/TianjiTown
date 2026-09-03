package org.allivlisey.tianjitown.integrations.worldborder;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.WorldBoundaryService;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBorderBoundaryServiceTest {
    private static final UUID WORLD_ID = UUID.randomUUID();

    @Test
    void loadsClassicWorldBorderApiFromPluginClassLoader() {
        com.wimbli.WorldBorder.Config.border =
                new com.wimbli.WorldBorder.BorderData(0, 1000);
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                WorldBorderBoundaryServiceTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (ignored, method, arguments) -> method.getName().equals("isEnabled")
                        ? true : defaultValue(method.getReturnType()));

        WorldBoundaryService.Check result = new WorldBorderBoundaryService(
                server(true), plugin).check(territory(), 1);

        assertTrue(result.configured());
        assertTrue(result.inside());
    }

    @Test
    void checksAllBufferedTerritoryCorners() {
        Object border = new Object();
        List<Point> checked = new ArrayList<>();
        WorldBorderBoundaryService.BorderAccess access = new WorldBorderBoundaryService.BorderAccess() {
            @Override
            public Object border(String worldName) {
                assertEquals("world", worldName);
                return border;
            }

            @Override
            public boolean inside(Object candidate, double x, double z) {
                assertTrue(candidate == border);
                checked.add(new Point(x, z));
                return true;
            }
        };
        WorldBorderBoundaryService service = new WorldBorderBoundaryService(
                server(true), access);

        WorldBoundaryService.Check result = service.check(territory(), 1);

        assertTrue(result.configured());
        assertTrue(result.inside());
        assertEquals(List.of(
                new Point(112, 272),
                new Point(112, 383),
                new Point(223, 272),
                new Point(223, 383)), checked);
    }

    @Test
    void reportsMissingWorldBorderConfiguration() {
        WorldBorderBoundaryService.BorderAccess access = new WorldBorderBoundaryService.BorderAccess() {
            @Override
            public Object border(String worldName) {
                return null;
            }

            @Override
            public boolean inside(Object border, double x, double z) {
                throw new AssertionError("没有边界时不应检查坐标");
            }
        };

        WorldBoundaryService.Check result = new WorldBorderBoundaryService(
                server(true), access).check(territory(), 0);

        assertFalse(result.configured());
        assertFalse(result.inside());
    }

    @Test
    void rejectsOffThreadAccess() {
        WorldBorderBoundaryService.BorderAccess access = new WorldBorderBoundaryService.BorderAccess() {
            @Override
            public Object border(String worldName) {
                return new Object();
            }

            @Override
            public boolean inside(Object border, double x, double z) {
                return true;
            }
        };

        assertThrows(IllegalStateException.class, () -> new WorldBorderBoundaryService(
                server(false), access).check(territory(), 0));
    }

    @Test
    void resolvesOffThreadFailureThroughInjectedMessageResolver() {
        WorldBorderBoundaryService.BorderAccess access = new WorldBorderBoundaryService.BorderAccess() {
            @Override
            public Object border(String worldName) {
                return new Object();
            }

            @Override
            public boolean inside(Object border, double x, double z) {
                return true;
            }
        };
        BiFunction<String, Map<String, ?>, String> resolver = (key, placeholders) -> {
            assertEquals("diagnostic.world-border.main-thread-required", key);
            assertTrue(placeholders.isEmpty());
            return "custom world border thread diagnostic";
        };

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new WorldBorderBoundaryService(server(false), access, resolver)
                        .check(territory(), 0));

        assertEquals("custom world border thread diagnostic", exception.getMessage());
    }

    @Test
    void resolvesDisabledPluginFailureThroughInjectedMessageResolver() {
        BiFunction<String, Map<String, ?>, String> resolver = (key, placeholders) -> {
            assertEquals("diagnostic.world-border.plugin-disabled", key);
            assertTrue(placeholders.isEmpty());
            return "custom world border disabled diagnostic";
        };
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                WorldBorderBoundaryServiceTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (ignored, method, arguments) -> method.getName().equals("isEnabled")
                        ? false : defaultValue(method.getReturnType()));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new WorldBorderBoundaryService(server(true), plugin, resolver));

        assertEquals("custom world border disabled diagnostic", exception.getMessage());
    }

    private static InitialTerritory territory() {
        return new InitialTerritory(new ChunkPosition(WORLD_ID, "world", 10, 20));
    }

    private static Server server(boolean primaryThread) {
        World world = proxy(World.class, (ignored, method, arguments) ->
                method.getName().equals("getName") ? "world"
                        : defaultValue(method.getReturnType()));
        return proxy(Server.class, (ignored, method, arguments) -> switch (method.getName()) {
            case "isPrimaryThread" -> primaryThread;
            case "getWorld" -> world;
            default -> defaultValue(method.getReturnType());
        });
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

    private record Point(double x, double z) {
    }
}

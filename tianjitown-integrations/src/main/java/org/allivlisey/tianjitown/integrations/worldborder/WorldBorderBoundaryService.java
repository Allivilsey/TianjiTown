package org.allivlisey.tianjitown.integrations.worldborder;

import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.WorldBoundaryService;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 对接经典 WorldBorder 插件的公开边界模型，同时避免将运行时插件类打入 TianjiTown。
 */
public final class WorldBorderBoundaryService implements WorldBoundaryService {
    private static final String MAIN_THREAD_REQUIRED =
            "diagnostic.world-border.main-thread-required";
    private static final String PLUGIN_DISABLED = "diagnostic.world-border.plugin-disabled";
    private static final String API_CAPABILITY_MISSING =
            "diagnostic.world-border.api-capability-missing";
    private static final String INVALID_RETURN = "diagnostic.world-border.invalid-return";
    private static final String API_INACCESSIBLE = "diagnostic.world-border.api-inaccessible";
    private static final String API_CALL_FAILED = "diagnostic.world-border.api-call-failed";
    private final Server server;
    private final BorderAccess borderAccess;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    public WorldBorderBoundaryService(Server server, Plugin worldBorderPlugin) {
        this(server, worldBorderPlugin, WorldBorderBoundaryService::fallbackMessage);
    }

    public WorldBorderBoundaryService(Server server, Plugin worldBorderPlugin,
                                      BiFunction<String, Map<String, ?>, String> messageResolver) {
        this(server, new ReflectiveBorderAccess(worldBorderPlugin,
                        requireResolver(messageResolver)), requireResolver(messageResolver));
    }

    WorldBorderBoundaryService(Server server, BorderAccess borderAccess) {
        this(server, borderAccess, WorldBorderBoundaryService::fallbackMessage);
    }

    WorldBorderBoundaryService(Server server, BorderAccess borderAccess,
                               BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.server = Objects.requireNonNull(server, "server");
        this.borderAccess = Objects.requireNonNull(borderAccess, "borderAccess");
        this.messageResolver = requireResolver(messageResolver);
    }

    @Override
    public Check check(InitialTerritory territory, int bufferChunks) {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException(resolveMessage(MAIN_THREAD_REQUIRED, Map.of()));
        }
        World world = server.getWorld(territory.center().worldId());
        if (world == null) {
            world = server.getWorld(territory.center().worldName());
        }
        if (world == null) {
            return Check.missing();
        }
        Object border = borderAccess.border(world.getName());
        if (border == null) {
            return Check.missing();
        }
        int bufferBlocks = Math.multiplyExact(Math.max(0, bufferChunks), 16);
        double minimumX = Math.subtractExact(Math.multiplyExact(
                territory.minimumChunkX(), 16), bufferBlocks);
        double minimumZ = Math.subtractExact(Math.multiplyExact(
                territory.minimumChunkZ(), 16), bufferBlocks);
        double maximumX = Math.addExact(Math.addExact(Math.multiplyExact(
                territory.maximumChunkX(), 16), 15), bufferBlocks);
        double maximumZ = Math.addExact(Math.addExact(Math.multiplyExact(
                territory.maximumChunkZ(), 16), 15), bufferBlocks);
        boolean inside = borderAccess.inside(border, minimumX, minimumZ)
                && borderAccess.inside(border, minimumX, maximumZ)
                && borderAccess.inside(border, maximumX, minimumZ)
                && borderAccess.inside(border, maximumX, maximumZ);
        return inside ? Check.configuredInside() : Check.configuredOutside();
    }

    interface BorderAccess {
        Object border(String worldName);

        boolean inside(Object border, double x, double z);
    }

    private static final class ReflectiveBorderAccess implements BorderAccess {
        private final Method borderMethod;
        private final Method insideMethod;
        private final BiFunction<String, Map<String, ?>, String> messageResolver;

        private ReflectiveBorderAccess(Plugin plugin,
                                       BiFunction<String, Map<String, ?>, String> messageResolver) {
            Objects.requireNonNull(plugin, "WorldBorder");
            this.messageResolver = requireResolver(messageResolver);
            if (!plugin.isEnabled()) {
                throw new IllegalStateException(resolveMessage(messageResolver,
                        PLUGIN_DISABLED, Map.of()));
            }
            try {
                ClassLoader loader = plugin.getClass().getClassLoader();
                Class<?> configClass = Class.forName(
                        "com.wimbli.WorldBorder.Config", true, loader);
                Class<?> borderClass = Class.forName(
                        "com.wimbli.WorldBorder.BorderData", true, loader);
                borderMethod = configClass.getMethod("Border", String.class);
                insideMethod = borderClass.getMethod(
                        "insideBorder", double.class, double.class);
            } catch (ClassNotFoundException | NoSuchMethodException exception) {
                throw new IllegalStateException(
                        resolveMessage(messageResolver, API_CAPABILITY_MISSING, Map.of()), exception);
            }
        }

        @Override
        public Object border(String worldName) {
            return invoke(borderMethod, null, worldName);
        }

        @Override
        public boolean inside(Object border, double x, double z) {
            Object result = invoke(insideMethod, border, x, z);
            if (!(result instanceof Boolean value)) {
                throw new IllegalStateException(resolveMessage(messageResolver,
                        INVALID_RETURN, Map.of()));
            }
            return value;
        }

        private Object invoke(Method method, Object target, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(resolveMessage(messageResolver,
                        API_INACCESSIBLE, Map.of()), exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(resolveMessage(messageResolver,
                        API_CALL_FAILED, Map.of()), cause);
            }
        }
    }

    private String resolveMessage(String key, Map<String, ?> placeholders) {
        return resolveMessage(messageResolver, key, placeholders);
    }

    private static String resolveMessage(BiFunction<String, Map<String, ?>, String> resolver,
                                         String key, Map<String, ?> placeholders) {
        try {
            String resolved = resolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError exception) {
            return key + " " + placeholders;
        }
    }

    private static BiFunction<String, Map<String, ?>, String> requireResolver(
            BiFunction<String, Map<String, ?>, String> resolver) {
        return Objects.requireNonNull(resolver, "messageResolver");
    }

    private static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
    }
}

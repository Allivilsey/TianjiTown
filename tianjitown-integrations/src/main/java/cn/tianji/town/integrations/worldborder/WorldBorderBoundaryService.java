package cn.tianji.town.integrations.worldborder;

import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.ports.WorldBoundaryService;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 对接经典 WorldBorder 插件的公开边界模型，同时避免将运行时插件类打入 TianjiTown。
 */
public final class WorldBorderBoundaryService implements WorldBoundaryService {
    private final Server server;
    private final BorderAccess borderAccess;

    public WorldBorderBoundaryService(Server server, Plugin worldBorderPlugin) {
        this(server, new ReflectiveBorderAccess(worldBorderPlugin));
    }

    WorldBorderBoundaryService(Server server, BorderAccess borderAccess) {
        this.server = Objects.requireNonNull(server, "server");
        this.borderAccess = Objects.requireNonNull(borderAccess, "borderAccess");
    }

    @Override
    public Check check(InitialTerritory territory, int bufferChunks) {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("WorldBorder API 必须在 Paper 主线程调用");
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

        private ReflectiveBorderAccess(Plugin plugin) {
            Objects.requireNonNull(plugin, "WorldBorder");
            if (!plugin.isEnabled()) {
                throw new IllegalStateException("WorldBorder 未启用");
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
                        "WorldBorder 缺少 Config.Border/BorderData.insideBorder 能力", exception);
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
                throw new IllegalStateException("WorldBorder insideBorder 返回值无效");
            }
            return value;
        }

        private static Object invoke(Method method, Object target, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("WorldBorder API 不可访问", exception);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("WorldBorder API 调用失败", cause);
            }
        }
    }
}

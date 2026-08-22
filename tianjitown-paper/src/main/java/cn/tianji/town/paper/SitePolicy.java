package cn.tianji.town.paper;

import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.ports.WorldBoundaryService;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SitePolicy {
    private final TianjiTownPlugin plugin;
    private final LandProtectionService landProtection;
    private final WorldBoundaryService worldBoundaries;
    private final Map<UUID, BukkitTask> previews = new HashMap<>();

    SitePolicy(TianjiTownPlugin plugin, LandProtectionService landProtection,
               WorldBoundaryService worldBoundaries) {
        this.plugin = plugin;
        this.landProtection = landProtection;
        this.worldBoundaries = worldBoundaries;
    }

    Validation validate(Player player) {
        World world = player.getWorld();
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(world.getUID(),
                world.getName(), player.getChunk().getX(), player.getChunk().getZ()));
        return validate(territory);
    }

    Validation validate(InitialTerritory territory) {
        Validation environment = validateEnvironment(territory);
        if (!environment.valid()) {
            return environment;
        }
        LandProtectionService.Collision collision;
        try {
            collision = landProtection.findCollision(territory);
        } catch (RuntimeException | LinkageError exception) {
            return Validation.failure("Residence 碰撞检查不可用: " + safeMessage(exception));
        }
        if (collision.occupied()) {
            return Validation.failure("3×3 区块与现有 Residence 冲突: "
                    + collision.residenceName());
        }
        return environment;
    }

    Validation validateExpansion(InitialTerritory territory, String residenceName) {
        Validation environment = validateEnvironment(territory);
        if (!environment.valid()) {
            return environment;
        }
        LandProtectionService.Collision collision;
        try {
            collision = landProtection.findCollision(territory);
        } catch (RuntimeException | LinkageError exception) {
            return Validation.failure("Residence 碰撞检查不可用: " + safeMessage(exception));
        }
        if (collision.occupied() && (collision.residenceName() == null
                || !collision.residenceName().equalsIgnoreCase(residenceName))) {
            return Validation.failure("3×3 区块与其他 Residence 冲突: "
                    + collision.residenceName());
        }
        return environment;
    }

    Validation validateEnvironment(InitialTerritory territory) {
        World world = plugin.getServer().getWorld(territory.center().worldId());
        if (world == null) {
            return Validation.failure("目标世界当前未加载");
        }
        if (rectangles("phase1.site.blacklist", world.getName()).stream()
                .anyMatch(area -> area.overlaps(territory))) {
            return Validation.failure("3×3 区块与出生点、活动区或管理黑名单重叠");
        }
        int bufferChunks = Math.max(0,
                plugin.getConfig().getInt("phase1.site.minimum-buffer-chunks", 1));
        WorldBoundaryService.Check boundary;
        try {
            boundary = worldBoundaries.check(territory, bufferChunks);
        } catch (RuntimeException | LinkageError exception) {
            return Validation.failure("WorldBorder 边界检查不可用: " + safeMessage(exception));
        }
        if (!boundary.configured()) {
            return Validation.failure("WorldBorder 未配置目标世界的边界");
        }
        if (!boundary.inside()) {
            return Validation.failure("3×3 区块或其缓冲范围会超出 WorldBorder 边界");
        }
        return Validation.success(territory);
    }

    void teleportAndPreview(Player player, InitialTerritory territory) {
        World world = plugin.getServer().getWorld(territory.center().worldId());
        if (world == null) {
            world = plugin.getServer().getWorld(territory.center().worldName());
        }
        if (world == null) {
            player.sendMessage("§c领地所在世界当前未加载。");
            return;
        }
        int centerX = Math.addExact(Math.multiplyExact(territory.center().x(), 16), 8);
        int centerZ = Math.addExact(Math.multiplyExact(territory.center().z(), 16), 8);
        int surfaceY = world.getHighestBlockYAt(centerX, centerZ,
                HeightMap.MOTION_BLOCKING_NO_LEAVES);
        double targetY = Math.min(world.getMaxHeight() - 1, surfaceY + 1);
        Location destination = new Location(world, centerX + 0.5, targetY, centerZ + 0.5,
                player.getYaw(), player.getPitch());
        if (!player.teleport(destination)) {
            player.sendMessage("§c无法传送到领地传送点。");
            return;
        }
        player.sendMessage("§a已传送至领地中心传送点。");
        preview(player, territory);
    }

    void preview(Player player, InitialTerritory territory) {
        if (!player.getWorld().getUID().equals(territory.center().worldId())) {
            player.sendMessage("§c预览领地不在当前世界。");
            return;
        }
        UUID playerId = player.getUniqueId();
        stopPreview(playerId);
        int durationSeconds = Math.max(5,
                plugin.getConfig().getInt("phase1.site.preview-duration-seconds", 15));
        int intervalTicks = Math.max(5,
                plugin.getConfig().getInt("phase1.site.preview-interval-ticks", 20));
        int renderCount = Math.max(1, durationSeconds * 20 / intervalTicks);
        BukkitRunnable runnable = new BukkitRunnable() {
            private int remaining = renderCount;

            @Override
            public void run() {
                try {
                    if (!player.isOnline() || !player.getWorld().getUID()
                            .equals(territory.center().worldId()) || remaining-- <= 0) {
                        previews.remove(playerId);
                        cancel();
                        return;
                    }
                    renderPreview(player, territory);
                } catch (RuntimeException | LinkageError exception) {
                    // 玩家、世界或插件在周期回调前失效时立即结束本次预览。
                    previews.remove(playerId);
                    try {
                        cancel();
                    } catch (RuntimeException | LinkageError ignored) {
                        exception.addSuppressed(ignored);
                    }
                    try {
                        if (plugin.isEnabled()) {
                            plugin.getLogger().warning("领地预览对象已失效，任务已结束: "
                                    + safeMessage(exception));
                        }
                    } catch (RuntimeException | LinkageError ignored) {
                        // 插件关闭阶段记录器失效时不再向事件循环抛出异常。
                    }
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, intervalTicks);
        previews.put(playerId, task);
        player.sendMessage("§e已显示 3×3 区块三维边界，粒子将持续约 " + durationSeconds
                + " 秒；中心区块为 " + territory.center().x() + ", "
                + territory.center().z() + "。");
    }

    void stopPreview(UUID playerId) {
        BukkitTask task = previews.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    void clearPreviews() {
        for (BukkitTask task : List.copyOf(previews.values())) {
            try {
                task.cancel();
            } catch (RuntimeException | LinkageError exception) {
                try {
                    if (plugin.isEnabled()) {
                        plugin.getLogger().warning("取消领地预览任务失败: "
                                + safeMessage(exception));
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // 插件关闭阶段记录器失效时继续清理预览索引。
                }
            }
        }
        previews.clear();
    }

    private void renderPreview(Player player, InitialTerritory territory) {
        World world = player.getWorld();
        double minimumX = territory.minimumChunkX() * 16.0;
        double minimumZ = territory.minimumChunkZ() * 16.0;
        double maximumX = (territory.maximumChunkX() + 1) * 16.0;
        double maximumZ = (territory.maximumChunkZ() + 1) * 16.0;
        int verticalRange = Math.max(8,
                plugin.getConfig().getInt("phase1.site.preview-vertical-range-blocks", 24));
        double centerY = player.getLocation().getY() + 1;
        double minimumY = Math.max(world.getMinHeight() + 1, centerY - verticalRange);
        double maximumY = Math.min(world.getMaxHeight() - 1, centerY + verticalRange);
        for (double y = minimumY; y <= maximumY; y += 2) {
            for (double x = minimumX; x <= maximumX; x += 2) {
                particle(player, x, y, minimumZ);
                particle(player, x, y, maximumZ);
            }
            for (double z = minimumZ + 2; z < maximumZ; z += 2) {
                particle(player, minimumX, y, z);
                particle(player, maximumX, y, z);
            }
        }
    }

    private static void particle(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.FLAME, x, y, z, 1, 0, 0, 0, 0);
    }

    private List<Rectangle> rectangles(String path, String world) {
        List<Rectangle> result = new ArrayList<>();
        for (java.util.Map<?, ?> raw : plugin.getConfig().getMapList(path)) {
            if (!world.equals(String.valueOf(raw.get("world")))) {
                continue;
            }
            try {
                result.add(new Rectangle(number(raw, "min-chunk-x"), number(raw, "max-chunk-x"),
                        number(raw, "min-chunk-z"), number(raw, "max-chunk-z")));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(path + " 存在无效区域: " + exception.getMessage());
            }
        }
        return List.copyOf(result);
    }

    private static int number(java.util.Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("缺少整数 " + key);
        }
        return number.intValue();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    record Validation(boolean valid, String error, InitialTerritory territory) {
        static Validation success(InitialTerritory territory) {
            return new Validation(true, null, territory);
        }

        static Validation failure(String error) {
            return new Validation(false, error, null);
        }
    }

    private record Rectangle(int minimumX, int maximumX, int minimumZ, int maximumZ) {
        Rectangle {
            if (minimumX > maximumX || minimumZ > maximumZ) {
                throw new IllegalArgumentException("区域最小值不能大于最大值");
            }
        }

        boolean contains(InitialTerritory territory) {
            return minimumX <= territory.minimumChunkX() && maximumX >= territory.maximumChunkX()
                    && minimumZ <= territory.minimumChunkZ() && maximumZ >= territory.maximumChunkZ();
        }

        boolean overlaps(InitialTerritory territory) {
            return minimumX <= territory.maximumChunkX() && maximumX >= territory.minimumChunkX()
                    && minimumZ <= territory.maximumChunkZ() && maximumZ >= territory.minimumChunkZ();
        }
    }
}

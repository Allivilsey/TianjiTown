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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        teleportAndPreview(player, territory, List.of(territory));
    }

    void teleportAndPreview(Player player, InitialTerritory focus,
                            List<InitialTerritory> territories) {
        List<InitialTerritory> areas = previewAreas(focus, territories);
        World world = plugin.getServer().getWorld(focus.center().worldId());
        if (world == null) {
            world = plugin.getServer().getWorld(focus.center().worldName());
        }
        if (world == null) {
            player.sendMessage("§c领地所在世界当前未加载。");
            return;
        }
        int centerX = Math.addExact(Math.multiplyExact(focus.center().x(), 16), 8);
        int centerZ = Math.addExact(Math.multiplyExact(focus.center().z(), 16), 8);
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
        preview(player, areas);
    }

    void preview(Player player, InitialTerritory territory) {
        preview(player, List.of(territory));
    }

    void preview(Player player, List<InitialTerritory> territories) {
        List<InitialTerritory> areas = previewAreas(null, territories);
        UUID worldId = areas.getFirst().center().worldId();
        if (!player.getWorld().getUID().equals(worldId)) {
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
                            .equals(worldId) || remaining-- <= 0) {
                        previews.remove(playerId);
                        cancel();
                        return;
                    }
                    renderPreview(player, areas);
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
        String scope = areas.size() == 1 ? "3×3 区块" : areas.size() + " 个领地单元";
        player.sendMessage("§e已显示 " + scope + " 的完整三维边界，粒子将持续约 "
                + durationSeconds + " 秒。");
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

    private void renderPreview(Player player, List<InitialTerritory> territories) {
        World world = player.getWorld();
        int verticalRange = Math.max(8,
                plugin.getConfig().getInt("phase1.site.preview-vertical-range-blocks", 24));
        double centerY = player.getLocation().getY() + 1;
        double minimumY = Math.max(world.getMinHeight() + 1, centerY - verticalRange);
        double maximumY = Math.min(world.getMaxHeight() - 1, centerY + verticalRange);
        double spacing = territories.size() == 1 ? 2.0 : 4.0;
        Set<ChunkCenter> centers = new HashSet<>();
        territories.forEach(territory -> centers.add(new ChunkCenter(
                territory.center().x(), territory.center().z())));
        for (InitialTerritory territory : territories) {
            renderPreview(player, territory, centers, minimumY, maximumY, spacing);
        }
    }

    private void renderPreview(Player player, InitialTerritory territory,
                               Set<ChunkCenter> centers, double minimumY, double maximumY,
                               double spacing) {
        double minimumX = territory.minimumBlockX();
        double minimumZ = territory.minimumBlockZ();
        double maximumX = territory.maximumBlockXExclusive();
        double maximumZ = territory.maximumBlockZExclusive();
        int centerX = territory.center().x();
        int centerZ = territory.center().z();
        boolean north = !centers.contains(new ChunkCenter(centerX, centerZ - 3));
        boolean south = !centers.contains(new ChunkCenter(centerX, centerZ + 3));
        boolean west = !centers.contains(new ChunkCenter(centerX - 3, centerZ));
        boolean east = !centers.contains(new ChunkCenter(centerX + 3, centerZ));
        for (double y = minimumY; y <= maximumY; y += spacing) {
            if (north || south) {
                for (double x = minimumX; x <= maximumX; x += spacing) {
                    if (north) {
                        particle(player, x, y, minimumZ);
                    }
                    if (south) {
                        particle(player, x, y, maximumZ);
                    }
                }
            }
            if (west || east) {
                for (double z = minimumZ + spacing; z < maximumZ; z += spacing) {
                    if (west) {
                        particle(player, minimumX, y, z);
                    }
                    if (east) {
                        particle(player, maximumX, y, z);
                    }
                }
            }
        }
    }

    private static List<InitialTerritory> previewAreas(
            InitialTerritory required, List<InitialTerritory> territories) {
        if (territories == null || territories.isEmpty()) {
            throw new IllegalArgumentException("领地预览至少需要一个区域");
        }
        List<InitialTerritory> areas = List.copyOf(territories);
        UUID worldId = areas.getFirst().center().worldId();
        if (areas.stream().anyMatch(area -> !area.center().worldId().equals(worldId))) {
            throw new IllegalArgumentException("领地预览区域必须位于同一世界");
        }
        if (required != null && (!required.center().worldId().equals(worldId)
                || !areas.contains(required))) {
            throw new IllegalArgumentException("领地中心不在预览区域中");
        }
        return areas;
    }

    private static void particle(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.FLAME, x, y, z, 1, 0, 0, 0, 0,
                null, true);
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

    private record ChunkCenter(int x, int z) {
    }
}

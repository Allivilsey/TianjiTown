package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.ports.WorldBoundaryService;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

final class SitePolicy {
    private static final String PREVIEW_INVALIDATED = "log.site.preview-invalidated";
    private static final String PREVIEW_CANCEL_FAILED = "log.site.preview-cancel-failed";
    private static final String PREVIEW_AREAS_REQUIRED = "validation.site.areas-required";
    private static final String PREVIEW_WORLD_MISMATCH = "validation.site.world-mismatch";
    private static final String PREVIEW_FOCUS_MISSING = "validation.site.focus-missing";
    private static final String PREVIEW_SCOPE_SINGLE = "chat.site.preview-scope-single";
    private static final String PREVIEW_SCOPE_MULTIPLE = "chat.site.preview-scope-multiple";
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
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.residence-unavailable",
                    Map.of("detail", safeMessage(exception))));
        }
        if (collision.code() != null) {
            return Validation.failure(LandProtectionMessages.detail(plugin.messages(), collision));
        }
        if (collision.occupied()) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.residence-collision",
                    Map.of("name", String.valueOf(collision.residenceName()))));
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
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.residence-unavailable",
                    Map.of("detail", safeMessage(exception))));
        }
        if (collision.code() != null) {
            return Validation.failure(LandProtectionMessages.detail(plugin.messages(), collision));
        }
        if (collision.occupied() && (collision.residenceName() == null
                || !collision.residenceName().equalsIgnoreCase(residenceName))) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.expansion-collision",
                    Map.of("name", String.valueOf(collision.residenceName()))));
        }
        return environment;
    }

    Validation validateEnvironment(InitialTerritory territory) {
        World world = plugin.getServer().getWorld(territory.center().worldId());
        if (world == null) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.world-unloaded"));
        }
        int bufferChunks = Math.max(0,
                plugin.getConfig().getInt("town.site.minimum-buffer-chunks", 1));
        WorldBoundaryService.Check boundary;
        try {
            boundary = worldBoundaries.check(territory, bufferChunks);
        } catch (RuntimeException | LinkageError exception) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.worldborder-unavailable",
                    Map.of("detail", safeMessage(exception))));
        }
        if (!boundary.configured()) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.worldborder-unconfigured"));
        }
        if (!boundary.inside()) {
            return Validation.failure(plugin.messages().plainText(
                    "chat.site-validation.worldborder-outside"));
        }
        return Validation.success(territory);
    }

    void teleportAndPreview(Player player, InitialTerritory territory) {
        teleportAndPreview(player, territory, List.of(territory));
    }

    void teleportAndPreviewSilently(Player player, InitialTerritory territory) {
        teleportAndPreview(player, territory, List.of(territory), false);
    }

    void teleportAndPreview(Player player, InitialTerritory focus,
                            List<InitialTerritory> territories) {
        teleportAndPreview(player, focus, territories, true);
    }

    private void teleportAndPreview(Player player, InitialTerritory focus,
                                    List<InitialTerritory> territories,
                                    boolean announcePreview) {
        List<InitialTerritory> areas = previewAreas(focus, territories,
                plugin.messages()::plainText);
        World world = plugin.getServer().getWorld(focus.center().worldId());
        if (world == null) {
            world = plugin.getServer().getWorld(focus.center().worldName());
        }
        if (world == null) {
            plugin.messages().send(player, "chat.site.world-unloaded");
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
            plugin.messages().send(player, "chat.site.teleport-failed");
            return;
        }
        plugin.messages().send(player, "chat.site.teleported");
        preview(player, areas, announcePreview);
    }

    void preview(Player player, InitialTerritory territory) {
        preview(player, List.of(territory));
    }

    void previewSilently(Player player, InitialTerritory territory) {
        preview(player, List.of(territory), false);
    }

    void previewSilently(Player player, List<InitialTerritory> territories) {
        preview(player, territories, false);
    }

    void preview(Player player, List<InitialTerritory> territories) {
        preview(player, territories, true);
    }

    private void preview(Player player, List<InitialTerritory> territories,
                         boolean announce) {
        List<InitialTerritory> areas = previewAreas(null, territories,
                plugin.messages()::plainText);
        UUID worldId = areas.getFirst().center().worldId();
        if (!player.getWorld().getUID().equals(worldId)) {
            plugin.messages().send(player, "chat.site.wrong-world");
            return;
        }
        UUID playerId = player.getUniqueId();
        stopPreview(playerId);
        int durationSeconds = Math.max(5,
                plugin.getConfig().getInt("town.site.preview-duration-seconds", 15));
        int intervalTicks = Math.max(5,
                plugin.getConfig().getInt("town.site.preview-interval-ticks", 20));
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
                            plugin.getLogger().warning(plugin.messages().plainText(
                                    PREVIEW_INVALIDATED,
                                    Map.of("detail", safeMessage(exception))));
                        }
                    } catch (RuntimeException | LinkageError ignored) {
                        // 插件关闭阶段记录器失效时不再向事件循环抛出异常。
                    }
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, intervalTicks);
        previews.put(playerId, task);
        if (announce) {
            String scope = previewScope(areas.size(), plugin.messages()::plainText);
            plugin.messages().send(player, "chat.site.preview-started", Map.of(
                    "scope", scope, "duration", durationSeconds));
        }
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
                        plugin.getLogger().warning(plugin.messages().plainText(
                                PREVIEW_CANCEL_FAILED,
                                Map.of("detail", safeMessage(exception))));
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
                plugin.getConfig().getInt("town.site.preview-vertical-range-blocks", 24));
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

    static String previewScope(int areaCount,
                               BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(messageResolver, "messageResolver");
        String key = areaCount == 1 ? PREVIEW_SCOPE_SINGLE : PREVIEW_SCOPE_MULTIPLE;
        Map<String, ?> placeholders = areaCount == 1
                ? Map.of() : Map.of("count", areaCount);
        return resolveMessage(messageResolver, key, placeholders);
    }

    static List<InitialTerritory> previewAreas(
            InitialTerritory required, List<InitialTerritory> territories,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(messageResolver, "messageResolver");
        if (territories == null || territories.isEmpty()) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    PREVIEW_AREAS_REQUIRED, Map.of()));
        }
        List<InitialTerritory> areas = List.copyOf(territories);
        UUID worldId = areas.getFirst().center().worldId();
        if (areas.stream().anyMatch(area -> !area.center().worldId().equals(worldId))) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    PREVIEW_WORLD_MISMATCH, Map.of()));
        }
        if (required != null && (!required.center().worldId().equals(worldId)
                || !areas.contains(required))) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    PREVIEW_FOCUS_MISSING, Map.of()));
        }
        return areas;
    }

    private static void particle(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.FLAME, x, y, z, 1, 0, 0, 0, 0,
                null, true);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return safeText(message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message);
    }

    private static String resolveMessage(
            BiFunction<String, Map<String, ?>, String> messageResolver,
            String key, Map<String, ?> placeholders) {
        Objects.requireNonNull(messageResolver, "messageResolver");
        try {
            String message = messageResolver.apply(key, placeholders);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 运行时文案解析失败时保留稳定键，避免预览流程重新抛出本地化异常。
        }
        return key;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    record Validation(boolean valid, String error, InitialTerritory territory) {
        static Validation success(InitialTerritory territory) {
            return new Validation(true, null, territory);
        }

        static Validation failure(String error) {
            return new Validation(false, error, null);
        }
    }

    private record ChunkCenter(int x, int z) {
    }
}

package cn.tianji.town.paper;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

record PhaseFiveSettings(BuildingRefund buildingRefund, BeaconEnhancement beacon,
                         Operations operations) {
    static PhaseFiveSettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        return new PhaseFiveSettings(loadBuildingRefund(config), loadBeacon(config),
                loadOperations(config));
    }

    private static BuildingRefund loadBuildingRefund(ConfigurationSection config) {
        String root = "phase5.building-refund";
        double chance = config.getDouble(root + ".chance", 0.1D);
        int dailyLimit = config.getInt(root + ".daily-limit", 64);
        int retentionDays = config.getInt(root + ".counter-retention-days", 30);
        if (!Double.isFinite(chance) || chance <= 0 || chance > 1) {
            throw new IllegalArgumentException(root + ".chance 必须在 (0, 1] 范围内");
        }
        if (dailyLimit < 1 || dailyLimit > 10_000) {
            throw new IllegalArgumentException(root + ".daily-limit 必须在 1~10000 范围内");
        }
        if (retentionDays < 2 || retentionDays > 366) {
            throw new IllegalArgumentException(root
                    + ".counter-retention-days 必须在 2~366 范围内");
        }
        List<String> configured = config.getStringList(root + ".materials");
        if (configured.isEmpty()) {
            throw new IllegalArgumentException(root + ".materials 至少需要一个方块");
        }
        Set<Material> materials = new LinkedHashSet<>();
        for (String value : configured) {
            Material material = Material.matchMaterial(value);
            if (material == null) {
                throw new IllegalArgumentException("建筑返还材料无效: " + value);
            }
            if (!isSafeSingleBlock(material)) {
                throw new IllegalArgumentException("建筑返还拒绝特殊/多方块/容器材料: "
                        + material);
            }
            materials.add(material);
        }
        return new BuildingRefund(config.getBoolean(root + ".enabled", true), chance,
                dailyLimit, retentionDays, materials);
    }

    private static BeaconEnhancement loadBeacon(ConfigurationSection config) {
        String root = "phase5.beacon";
        double multiplier = config.getDouble(root + ".range-multiplier", 1.5D);
        double maximumRange = config.getDouble(root + ".maximum-range", 128D);
        int maximumTier = config.getInt(root + ".maximum-tier", 4);
        int levelBonus = config.getInt(root + ".effect-level-bonus", 1);
        int maximumEffectLevel = config.getInt(root + ".maximum-effect-level", 2);
        long scanTicks = config.getLong(root + ".scan-interval-ticks", 100L);
        if (!Double.isFinite(multiplier) || multiplier < 1 || multiplier > 8) {
            throw new IllegalArgumentException(root + ".range-multiplier 必须在 1~8 范围内");
        }
        if (!Double.isFinite(maximumRange) || maximumRange < 10 || maximumRange > 512) {
            throw new IllegalArgumentException(root + ".maximum-range 必须在 10~512 范围内");
        }
        if (maximumTier < 1 || maximumTier > 4) {
            throw new IllegalArgumentException(root + ".maximum-tier 必须在 1~4 范围内");
        }
        if (levelBonus < 0 || levelBonus > 4 || maximumEffectLevel < 1
                || maximumEffectLevel > 5) {
            throw new IllegalArgumentException("信标效果等级配置超出安全范围");
        }
        if (scanTicks < 20 || scanTicks > 20L * 60) {
            throw new IllegalArgumentException(root
                    + ".scan-interval-ticks 必须在 20~1200 范围内");
        }
        Set<String> worlds = config.getStringList(root + ".allowed-worlds").stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());
        if (worlds.isEmpty()) {
            throw new IllegalArgumentException(root + ".allowed-worlds 至少需要一个世界");
        }
        return new BeaconEnhancement(config.getBoolean(root + ".enabled", true), multiplier,
                maximumRange, maximumTier, levelBonus, maximumEffectLevel, scanTicks, worlds);
    }

    private static Operations loadOperations(ConfigurationSection config) {
        String root = "phase5.operations";
        int diagnosticsDays = config.getInt(root + ".quickshop-diagnostic-days", 7);
        long diagnosticsMinutes = config.getLong(root + ".diagnostics-interval-minutes", 60);
        long backupHours = config.getLong(root + ".backup.interval-hours", 6);
        int retention = config.getInt(root + ".backup.retention-count", 14);
        String directory = config.getString(root + ".backup.directory", "backups");
        if (diagnosticsDays < 1 || diagnosticsDays > 180) {
            throw new IllegalArgumentException(root
                    + ".quickshop-diagnostic-days 必须在 1~180 范围内");
        }
        if (diagnosticsMinutes < 5 || diagnosticsMinutes > 24L * 60) {
            throw new IllegalArgumentException(root
                    + ".diagnostics-interval-minutes 必须在 5~1440 范围内");
        }
        if (backupHours < 1 || backupHours > 24L * 30 || retention < 2 || retention > 1000) {
            throw new IllegalArgumentException("定时备份间隔或保留数量超出安全范围");
        }
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException(root + ".backup.directory 不能为空");
        }
        return new Operations(diagnosticsDays, Duration.ofMinutes(diagnosticsMinutes),
                new Backup(config.getBoolean(root + ".backup.enabled", true),
                        Duration.ofHours(backupHours), retention, Path.of(directory)));
    }

    static boolean isSafeSingleBlock(Material material) {
        String name = material.name();
        if (Set.of(Material.BEACON, Material.TNT, Material.RESPAWN_ANCHOR,
                Material.END_PORTAL_FRAME, Material.DRAGON_EGG).contains(material)) {
            return false;
        }
        return java.util.Arrays.stream(new String[]{
                "_BED", "_DOOR", "_SIGN", "_HANGING_SIGN", "_BANNER", "_HEAD", "_SKULL",
                "CHEST", "SHULKER_BOX", "BARREL", "FURNACE", "SMOKER", "BLAST_FURNACE",
                "DISPENSER", "DROPPER", "HOPPER", "BREWING_STAND", "LECTERN", "CHISELED_BOOKSHELF",
                "DECORATED_POT", "JUKEBOX", "SPAWNER", "COMMAND_BLOCK", "STRUCTURE_BLOCK",
                "JIGSAW", "VAULT", "TRIAL_SPAWNER", "CAULDRON", "CAMPFIRE", "CANDLE_CAKE",
                "PISTON", "MOVING_PISTON", "SLIME_BLOCK", "HONEY_BLOCK"
        }).noneMatch(name::contains);
    }

    record BuildingRefund(boolean enabled, double chance, int dailyLimit, int retentionDays,
                          Set<Material> materials) {
        BuildingRefund {
            materials = Set.copyOf(materials);
        }
    }

    record BeaconEnhancement(boolean enabled, double rangeMultiplier, double maximumRange,
                             int maximumTier, int effectLevelBonus, int maximumEffectLevel,
                             long scanIntervalTicks, Set<String> allowedWorlds) {
        BeaconEnhancement {
            allowedWorlds = Set.copyOf(allowedWorlds);
        }

        boolean allowsWorld(String worldName) {
            return allowedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
        }
    }

    record Operations(int quickShopDiagnosticDays, Duration diagnosticsInterval, Backup backup) {
    }

    record Backup(boolean enabled, Duration interval, int retentionCount, Path directory) {
    }
}

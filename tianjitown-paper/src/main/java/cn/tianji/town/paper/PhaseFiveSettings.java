package cn.tianji.town.paper;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
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
        int weeklyLimit = config.getInt(root + ".weekly-limit", 3_000);
        int retentionWeeks = config.getInt(root + ".counter-retention-weeks", 12);
        if (!Double.isFinite(chance) || chance <= 0 || chance > 1) {
            throw new IllegalArgumentException(root + ".chance 必须在 (0, 1] 范围内");
        }
        if (weeklyLimit < 1 || weeklyLimit > 100_000) {
            throw new IllegalArgumentException(root + ".weekly-limit 必须在 1~100000 范围内");
        }
        if (retentionWeeks < 2 || retentionWeeks > 260) {
            throw new IllegalArgumentException(root
                    + ".counter-retention-weeks 必须在 2~260 范围内");
        }
        ZoneId resetZone;
        try {
            resetZone = ZoneId.of(config.getString(root + ".reset-zone", "Asia/Shanghai"));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(root + ".reset-zone 不是有效时区", exception);
        }
        List<String> configured = config.getStringList(root + ".blacklist");
        if (configured.isEmpty()) {
            throw new IllegalArgumentException(root + ".blacklist 至少需要一个方块或分组");
        }
        Set<Material> blacklist = new LinkedHashSet<>();
        for (String value : configured) {
            if (value.equalsIgnoreCase("REDSTONE_CATEGORY")) {
                Arrays.stream(Material.values()).filter(PhaseFiveSettings::isRedstoneCategory)
                        .forEach(blacklist::add);
                continue;
            }
            Material material = Material.matchMaterial(value);
            if (material == null) {
                throw new IllegalArgumentException("建筑返还黑名单材料无效: " + value);
            }
            blacklist.add(material);
        }
        return new BuildingRefund(config.getBoolean(root + ".enabled", true), chance,
                weeklyLimit, retentionWeeks, resetZone, blacklist);
    }

    private static BeaconEnhancement loadBeacon(ConfigurationSection config) {
        String root = "phase5.beacon";
        long scanTicks = config.getLong(root + ".scan-interval-ticks", 100L);
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
        return new BeaconEnhancement(config.getBoolean(root + ".enabled", true), scanTicks,
                worlds);
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

    static boolean isRedstoneCategory(Material material) {
        String name = material.name();
        if (name.contains("REDSTONE") || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE") || name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE")
                || name.endsWith("RAIL")) {
            return true;
        }
        return Set.of("REPEATER", "COMPARATOR", "TARGET", "LEVER", "LIGHTNING_ROD",
                "DAYLIGHT_DETECTOR", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR", "TRIPWIRE",
                "TRIPWIRE_HOOK", "TRAPPED_CHEST", "TNT", "NOTE_BLOCK", "LECTERN",
                "CHISELED_BOOKSHELF", "OBSERVER", "PISTON", "STICKY_PISTON", "MOVING_PISTON",
                "SLIME_BLOCK", "HONEY_BLOCK", "DISPENSER", "DROPPER", "HOPPER", "CRAFTER")
                .contains(name);
    }

    record BuildingRefund(boolean enabled, double chance, int weeklyLimit, int retentionWeeks,
                          ZoneId resetZone, Set<Material> blacklist) {
        BuildingRefund {
            Objects.requireNonNull(resetZone, "resetZone");
            blacklist = Set.copyOf(blacklist);
        }
    }

    record BeaconEnhancement(boolean enabled, long scanIntervalTicks, Set<String> allowedWorlds) {
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

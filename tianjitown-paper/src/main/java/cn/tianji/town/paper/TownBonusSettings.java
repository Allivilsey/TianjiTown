package cn.tianji.town.paper;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
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

record TownBonusSettings(BuildingRefund buildingRefund, BeaconEnhancement beacon,
                         Operations operations) {
    static TownBonusSettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        return new TownBonusSettings(loadBuildingRefund(config), loadBeacon(config),
                loadOperations(config));
    }

    private static BuildingRefund loadBuildingRefund(ConfigurationSection config) {
        String root = "phase5.building-refund";
        double chance = decimal(config, root + ".chance", 0.1D);
        int weeklyLimit = integer(config, root + ".weekly-limit", 3_000);
        int retentionWeeks = integer(config, root + ".counter-retention-weeks", 12);
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
            resetZone = ZoneId.of(text(config, root + ".reset-zone", "Asia/Shanghai"));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(root + ".reset-zone 不是有效时区", exception);
        }
        List<String> configured = stringList(config, root + ".blacklist");
        if (configured.isEmpty()) {
            throw new IllegalArgumentException(root + ".blacklist 至少需要一个方块或分组");
        }
        Set<Material> blacklist = new LinkedHashSet<>();
        for (String value : configured) {
            if (value.equalsIgnoreCase("REDSTONE_CATEGORY")) {
                Arrays.stream(Material.values()).filter(TownBonusSettings::isRedstoneCategory)
                        .forEach(blacklist::add);
                continue;
            }
            Material material = Material.matchMaterial(value);
            if (material == null) {
                throw new IllegalArgumentException("建筑返还黑名单材料无效: " + value);
            }
            blacklist.add(material);
        }
        return new BuildingRefund(bool(config, root + ".enabled", true), chance,
                weeklyLimit, retentionWeeks, resetZone, blacklist);
    }

    private static BeaconEnhancement loadBeacon(ConfigurationSection config) {
        String root = "phase5.beacon";
        long refreshTicks = longInteger(config, root + ".refresh-interval-ticks", 100L);
        if (refreshTicks < 20 || refreshTicks > 20L * 60) {
            throw new IllegalArgumentException(root
                    + ".refresh-interval-ticks 必须在 20~1200 范围内");
        }
        Set<String> worlds = stringList(config, root + ".allowed-worlds").stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());
        if (worlds.isEmpty()) {
            throw new IllegalArgumentException(root + ".allowed-worlds 至少需要一个世界");
        }
        return new BeaconEnhancement(bool(config, root + ".enabled", true), refreshTicks,
                worlds);
    }

    private static Operations loadOperations(ConfigurationSection config) {
        String root = "phase5.operations";
        int diagnosticsDays = integer(config, root + ".quickshop-diagnostic-days", 7);
        long diagnosticsMinutes = longInteger(config, root + ".diagnostics-interval-minutes", 60);
        long backupHours = longInteger(config, root + ".backup.interval-hours", 6);
        int retention = integer(config, root + ".backup.retention-count", 14);
        String directory = text(config, root + ".backup.directory", "backups");
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
                new Backup(bool(config, root + ".backup.enabled", true),
                        Duration.ofHours(backupHours), retention, Path.of(directory)));
    }

    private static boolean bool(ConfigurationSection config, String path, boolean defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalidType(path, "布尔值");
    }

    private static double decimal(ConfigurationSection config, String path, double defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw invalidType(path, "数字");
    }

    private static int integer(ConfigurationSection config, String path, int defaultValue) {
        long value = longInteger(config, path, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " 超出整数范围");
        }
        return (int) value;
    }

    private static long longInteger(ConfigurationSection config, String path, long defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw invalidType(path, "整数");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(path + " 必须为 long 范围内的整数", exception);
        }
    }

    private static String text(ConfigurationSection config, String path, String defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String text) {
            return text;
        }
        throw invalidType(path, "文本");
    }

    private static List<String> stringList(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (!(value instanceof List<?> values)
                || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw invalidType(path, "文本列表");
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static IllegalArgumentException invalidType(String path, String expected) {
        return new IllegalArgumentException(path + " 必须为" + expected);
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

    record BeaconEnhancement(boolean enabled, long refreshIntervalTicks,
                             Set<String> allowedWorlds) {
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

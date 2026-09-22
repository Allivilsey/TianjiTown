package org.allivlisey.tianjitown.paper.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

public record TownBonusSettings(BuildingRefund buildingRefund, BeaconEnhancement beacon) {
    private static final String REFUND_CHANCE_RANGE =
            "validation.bonus.building-refund-chance-range";
    private static final String INTEGER_RANGE = "validation.bonus.integer-range";
    private static final String REFUND_WEEKLY_LIMIT_RANGE =
            "validation.common.range";
    private static final String REFUND_RETENTION_RANGE =
            "validation.common.range";
    private static final String RESET_ZONE_INVALID =
            "validation.bonus.building-refund-reset-zone-invalid";
    private static final String BLACKLIST_REQUIRED =
            "validation.bonus.building-refund-blacklist-required";
    private static final String BLACKLIST_MATERIAL_INVALID =
            "validation.bonus.building-refund-blacklist-material-invalid";
    private static final String BEACON_REFRESH_INTERVAL_RANGE =
            "validation.common.range";
    public static TownBonusSettings load(ConfigurationSection config) {
        return load(config, ConfigurationValues::fallbackMessage);
    }

    public static TownBonusSettings load(ConfigurationSection config,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messageResolver, "messageResolver");
        return new TownBonusSettings(loadBuildingRefund(config, messageResolver),
                loadBeacon(config, messageResolver));
    }

    private static BuildingRefund loadBuildingRefund(
            ConfigurationSection config,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        String root = "territory.building-refund";
        double chance = decimal(config, root + ".chance", 0.25D, messageResolver);
        int weeklyLimit = integer(config, root + ".weekly-limit", 3_000, messageResolver);
        int retentionWeeks = integer(config, root + ".counter-retention-weeks", 12,
                messageResolver);
        if (!Double.isFinite(chance) || chance <= 0 || chance > 1) {
            throw invalid(messageResolver, REFUND_CHANCE_RANGE,
                    Map.of("path", root + ".chance"));
        }
        if (weeklyLimit < 1 || weeklyLimit > 100_000) {
            throw invalid(messageResolver, REFUND_WEEKLY_LIMIT_RANGE,
                    Map.of("path", root + ".weekly-limit", "minimum", 1, "maximum", 100_000));
        }
        if (retentionWeeks < 2 || retentionWeeks > 260) {
            throw invalid(messageResolver, REFUND_RETENTION_RANGE,
                    Map.of("path", root + ".counter-retention-weeks", "minimum", 2,
                            "maximum", 260));
        }
        ZoneId resetZone;
        try {
            resetZone = ZoneId.of(ConfigurationValues.text(config, root + ".reset-zone",
                    "Asia/Shanghai", messageResolver));
        } catch (DateTimeException exception) {
            throw invalid(messageResolver, RESET_ZONE_INVALID,
                    Map.of("path", root + ".reset-zone"), exception);
        }
        List<String> configured = ConfigurationValues.stringList(config, root + ".blacklist",
                messageResolver);
        if (configured.isEmpty()) {
            throw invalid(messageResolver, BLACKLIST_REQUIRED,
                    Map.of("path", root + ".blacklist"));
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
                throw invalid(messageResolver, BLACKLIST_MATERIAL_INVALID,
                        Map.of("value", safeText(value)));
            }
            blacklist.add(material);
        }
        return new BuildingRefund(ConfigurationValues.bool(config, root + ".enabled", true,
                messageResolver), chance,
                weeklyLimit, retentionWeeks, resetZone, blacklist);
    }

    private static BeaconEnhancement loadBeacon(
            ConfigurationSection config,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        String root = "territory.beacon";
        long refreshTicks = ConfigurationValues.longInteger(config,
                root + ".refresh-interval-ticks", 100L, messageResolver);
        // A tier-one beacon lasts 220 ticks; renewal must finish before it expires.
        if (refreshTicks < 20 || refreshTicks > 200) {
            throw invalid(messageResolver, BEACON_REFRESH_INTERVAL_RANGE,
                    Map.of("path", root + ".refresh-interval-ticks", "minimum", 20,
                            "maximum", 200));
        }
        return new BeaconEnhancement(ConfigurationValues.bool(config, root + ".enabled", true,
                messageResolver), refreshTicks);
    }

    private static double decimal(ConfigurationSection config, String path, double defaultValue,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw invalid(messageResolver, "validation.configuration.number-type",
                Map.of("path", safeText(path)));
    }

    private static int integer(ConfigurationSection config, String path, int defaultValue,
                               BiFunction<String, Map<String, ?>, String> messageResolver) {
        long value = ConfigurationValues.longInteger(config, path, defaultValue, messageResolver);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(messageResolver, INTEGER_RANGE,
                    Map.of("path", safeText(path)));
        }
        return (int) value;
    }

    private static IllegalArgumentException invalid(
            BiFunction<String, Map<String, ?>, String> messageResolver,
            String key, Map<String, ?> placeholders) {
        return new IllegalArgumentException(resolveMessage(messageResolver, key, placeholders));
    }

    private static IllegalArgumentException invalid(
            BiFunction<String, Map<String, ?>, String> messageResolver,
            String key, Map<String, ?> placeholders, RuntimeException cause) {
        return new IllegalArgumentException(resolveMessage(messageResolver, key, placeholders),
                cause);
    }

    private static String resolveMessage(
            BiFunction<String, Map<String, ?>, String> messageResolver,
            String key, Map<String, ?> placeholders) {
        try {
            String message = messageResolver.apply(key, placeholders);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 配置解析必须在消息加载失败时仍能暴露稳定诊断标识。
        }
        return ConfigurationValues.fallbackMessage(key, placeholders);
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    public static boolean isSafeSingleBlock(Material material) {
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

    public static boolean isRedstoneCategory(Material material) {
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

    public record BuildingRefund(boolean enabled, double chance, int weeklyLimit, int retentionWeeks,
                          ZoneId resetZone, Set<Material> blacklist) {
        public BuildingRefund {
            Objects.requireNonNull(resetZone, "resetZone");
            blacklist = Set.copyOf(blacklist);
        }
    }

    public record BeaconEnhancement(boolean enabled, long refreshIntervalTicks) {
    }

}

package cn.tianji.town.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

final class RuntimeConfigurationValidator {
    private static final long MAXIMUM_TIMEOUT_MILLIS = 300_000;

    private RuntimeConfigurationValidator() {
    }

    static DatabaseSettings validate(ConfigurationSection config,
                                     Predicate<String> loadedWorld) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(loadedWorld, "loadedWorld");
        TownUiMode.load(config);
        ConfigurationValues.bool(config, "test-command.enabled", false);
        ConfigurationValues.bool(config, "phase1.maintenance-mode", false);
        ConfigurationValues.list(config, "phase1.service-stations");
        validatePhaseOne(config, loadedWorld);
        GovernanceSettings.load(config);
        EconomySettings economy = EconomySettings.load(config);
        BuffSettings.load(config, economy.fallbackScale());
        TownBonusSettings bonuses = TownBonusSettings.load(config);
        List<String> missingWorlds = bonuses.beacon().allowedWorlds().stream()
                .filter(world -> !loadedWorld.test(world)).sorted().toList();
        if (!missingWorlds.isEmpty()) {
            throw new IllegalArgumentException("phase5.beacon.allowed-worlds 包含未加载世界: "
                    + String.join(", ", missingWorlds));
        }
        return databaseSettings(config);
    }

    static DatabaseSettings databaseSettings(ConfigurationSection config) {
        String file = ConfigurationValues.text(config, "database.file", "tianjitown.db");
        if (file.isBlank()) {
            throw new IllegalArgumentException("database.file 不能为空");
        }
        long connectionTimeout = ConfigurationValues.longInteger(config,
                "database.connection-timeout-ms", 5_000);
        long busyTimeout = ConfigurationValues.longInteger(config,
                "database.busy-timeout-ms", 5_000);
        requireRange("database.connection-timeout-ms", connectionTimeout, 250,
                MAXIMUM_TIMEOUT_MILLIS);
        requireRange("database.busy-timeout-ms", busyTimeout, 1,
                MAXIMUM_TIMEOUT_MILLIS);
        return new DatabaseSettings(connectionTimeout, busyTimeout);
    }

    private static void validatePhaseOne(ConfigurationSection config,
                                         Predicate<String> loadedWorld) {
        requireRange(config, "phase1.application.cooldown-hours", 24, 0, 8_760);
        requireRange(config, "phase1.application.reservation-minutes", 60, 1, 1_440);
        requireRange(config, "phase1.membership.maximum-pending-applications", 3, 1, 100);
        requireRange(config, "phase1.membership.application-lifetime-hours", 48, 1, 8_760);
        requireRange(config, "phase1.membership.rejection-cooldown-hours", 24, 0, 8_760);
        requireRange(config, "phase1.membership.leave-cooldown-hours", 24, 0, 8_760);
        requireRange(config, "phase1.site.minimum-buffer-chunks", 1, 0, 64);
        requireRange(config, "phase1.site.preview-duration-seconds", 15, 5, 300);
        requireRange(config, "phase1.site.preview-interval-ticks", 20, 5, 1_200);
        requireRange(config, "phase1.site.preview-vertical-range-blocks", 24, 8, 384);
        for (Object item : ConfigurationValues.list(config, "phase1.site.blacklist")) {
            if (!(item instanceof Map<?, ?> area)) {
                throw new IllegalArgumentException("phase1.site.blacklist 必须为区域列表");
            }
            String world = mapText(area, "world", "phase1.site.blacklist");
            if (!loadedWorld.test(world.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("phase1.site.blacklist 包含未加载世界: " + world);
            }
            int minimumX = mapInteger(area, "min-chunk-x", "phase1.site.blacklist");
            int maximumX = mapInteger(area, "max-chunk-x", "phase1.site.blacklist");
            int minimumZ = mapInteger(area, "min-chunk-z", "phase1.site.blacklist");
            int maximumZ = mapInteger(area, "max-chunk-z", "phase1.site.blacklist");
            if (minimumX > maximumX || minimumZ > maximumZ) {
                throw new IllegalArgumentException("phase1.site.blacklist 区域最小坐标不能大于最大坐标");
            }
        }
    }

    private static String mapText(Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException(path + "." + key + " 必须为非空文本");
    }

    private static int mapInteger(Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + "." + key + " 必须为整数");
        }
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(path + "." + key + " 必须为 int 范围内的整数",
                    exception);
        }
    }

    private static void requireRange(ConfigurationSection config, String path, long defaultValue,
                                     long minimum, long maximum) {
        long value = ConfigurationValues.longInteger(config, path, defaultValue);
        requireRange(path, value, minimum, maximum);
    }

    private static void requireRange(String path, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " 必须在 " + minimum + "~" + maximum
                    + " 范围内");
        }
    }

    record DatabaseSettings(long connectionTimeoutMillis, long busyTimeoutMillis) {
    }
}

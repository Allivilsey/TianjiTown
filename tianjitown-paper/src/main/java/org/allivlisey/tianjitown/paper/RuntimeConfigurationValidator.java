package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

final class RuntimeConfigurationValidator {
    private static final long MAXIMUM_TIMEOUT_MILLIS = 300_000;
    private static final String DATABASE_FILE_REQUIRED =
            "validation.runtime-configuration.database-file-required";
    private static final String BLACKLIST_TYPE =
            "validation.runtime-configuration.blacklist-type";
    private static final String BLACKLIST_WORLD_UNLOADED =
            "validation.runtime-configuration.blacklist-world-unloaded";
    private static final String BLACKLIST_BOUNDS =
            "validation.runtime-configuration.blacklist-bounds";
    private static final String BLACKLIST_TEXT_REQUIRED =
            "validation.runtime-configuration.blacklist-text-required";
    private static final String BLACKLIST_INTEGER_TYPE =
            "validation.common.integer-type";
    private static final String BLACKLIST_INTEGER_RANGE =
            "validation.runtime-configuration.blacklist-integer-range";
    private static final String RANGE = "validation.common.range";

    private RuntimeConfigurationValidator() {
    }

    static DatabaseSettings validate(ConfigurationSection config,
                                     Predicate<String> loadedWorld) {
        return validate(config, loadedWorld, RuntimeConfigurationValidator::fallbackMessage,
                key -> key);
    }

    static DatabaseSettings validate(ConfigurationSection config,
                                     Predicate<String> loadedWorld,
                                     BiFunction<String, Map<String, ?>, String> messageResolver) {
        return validate(config, loadedWorld, messageResolver,
                key -> messageResolver.apply(key, Map.of()));
    }

    static DatabaseSettings validate(ConfigurationSection config,
                                     Predicate<String> loadedWorld,
                                     PluginMessages messages) {
        Objects.requireNonNull(messages, "messages");
        return validate(config, loadedWorld, messages::plainText, messages::requiredPlainText);
    }

    private static DatabaseSettings validate(
            ConfigurationSection config,
            Predicate<String> loadedWorld,
            BiFunction<String, Map<String, ?>, String> messageResolver,
            Function<String, String> requiredMessageResolver) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(loadedWorld, "loadedWorld");
        Objects.requireNonNull(messageResolver, "messageResolver");
        Objects.requireNonNull(requiredMessageResolver, "requiredMessageResolver");
        ConfigurationValues.bool(config, "town.maintenance-mode", false, messageResolver);
        ConfigurationValues.list(config, "town.service-stations", messageResolver);
        validateTown(config, loadedWorld, messageResolver);
        EconomySettings.load(config, messageResolver);
        BuffSettings.load(config, messageResolver,
                requiredMessageResolver);
        TownBonusSettings.load(config, messageResolver);
        return databaseSettings(config, messageResolver);
    }

    static DatabaseSettings databaseSettings(ConfigurationSection config) {
        return databaseSettings(config, RuntimeConfigurationValidator::fallbackMessage);
    }

    static DatabaseSettings databaseSettings(
            ConfigurationSection config,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messageResolver, "messageResolver");
        String file = ConfigurationValues.text(config, "database.file", "tianjitown.db",
                messageResolver);
        if (file.isBlank()) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    DATABASE_FILE_REQUIRED, Map.of("path", "database.file")));
        }
        long connectionTimeout = ConfigurationValues.longInteger(config,
                "database.connection-timeout-ms", 5_000, messageResolver);
        long busyTimeout = ConfigurationValues.longInteger(config,
                "database.busy-timeout-ms", 5_000, messageResolver);
        requireRange("database.connection-timeout-ms", connectionTimeout, 250,
                MAXIMUM_TIMEOUT_MILLIS, messageResolver);
        requireRange("database.busy-timeout-ms", busyTimeout, 1,
                MAXIMUM_TIMEOUT_MILLIS, messageResolver);
        return new DatabaseSettings(connectionTimeout, busyTimeout);
    }

    private static void validateTown(ConfigurationSection config,
                                     Predicate<String> loadedWorld,
                                     BiFunction<String, Map<String, ?>, String> messageResolver) {
        requireRange(config, "town.application.cooldown-hours", 24, 0, 8_760,
                messageResolver);
        requireRange(config, "town.application.reservation-minutes", 60, 1, 1_440,
                messageResolver);
        requireRange(config, "town.membership.maximum-pending-applications", 3, 1, 100,
                messageResolver);
        requireRange(config, "town.membership.application-lifetime-hours", 48, 1, 8_760,
                messageResolver);
        requireRange(config, "town.membership.rejection-cooldown-hours", 24, 0, 8_760,
                messageResolver);
        requireRange(config, "town.membership.leave-cooldown-hours", 24, 0, 8_760,
                messageResolver);
        requireRange(config, "town.site.minimum-buffer-chunks", 1, 0, 64,
                messageResolver);
        requireRange(config, "town.site.preview-duration-seconds", 15, 5, 300,
                messageResolver);
        requireRange(config, "town.site.preview-interval-ticks", 20, 5, 1_200,
                messageResolver);
        requireRange(config, "town.site.preview-vertical-range-blocks", 24, 8, 384,
                messageResolver);
        for (Object item : ConfigurationValues.list(config, "town.site.blacklist", messageResolver)) {
            if (!(item instanceof Map<?, ?> area)) {
                throw new IllegalArgumentException(resolveMessage(messageResolver, BLACKLIST_TYPE,
                        Map.of()));
            }
            String world = mapText(area, "world", "town.site.blacklist", messageResolver);
            if (!loadedWorld.test(world.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(resolveMessage(messageResolver,
                        BLACKLIST_WORLD_UNLOADED, Map.of("world", safeText(world))));
            }
            int minimumX = mapInteger(area, "min-chunk-x", "town.site.blacklist",
                    messageResolver);
            int maximumX = mapInteger(area, "max-chunk-x", "town.site.blacklist",
                    messageResolver);
            int minimumZ = mapInteger(area, "min-chunk-z", "town.site.blacklist",
                    messageResolver);
            int maximumZ = mapInteger(area, "max-chunk-z", "town.site.blacklist",
                    messageResolver);
            if (minimumX > maximumX || minimumZ > maximumZ) {
                throw new IllegalArgumentException(resolveMessage(messageResolver,
                        BLACKLIST_BOUNDS, Map.of()));
            }
        }
    }

    private static String mapText(Map<?, ?> map, String key, String path,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = map.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException(resolveMessage(messageResolver,
                BLACKLIST_TEXT_REQUIRED,
                Map.of("path", safeText(path + "." + key))));
    }

    private static int mapInteger(Map<?, ?> map, String key, String path,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    BLACKLIST_INTEGER_TYPE,
                    Map.of("path", safeText(path + "." + key))));
        }
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    BLACKLIST_INTEGER_RANGE,
                    Map.of("path", safeText(path + "." + key))), exception);
        }
    }

    private static void requireRange(ConfigurationSection config, String path, long defaultValue,
                                     long minimum, long maximum,
                                     BiFunction<String, Map<String, ?>, String> messageResolver) {
        long value = ConfigurationValues.longInteger(config, path, defaultValue, messageResolver);
        requireRange(path, value, minimum, maximum, messageResolver);
    }

    private static void requireRange(String path, long value, long minimum, long maximum,
                                     BiFunction<String, Map<String, ?>, String> messageResolver) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, RANGE,
                    Map.of("path", safeText(path), "minimum", minimum, "maximum", maximum)));
        }
    }

    private static String resolveMessage(BiFunction<String, Map<String, ?>, String> messageResolver,
                                         String key, Map<String, ?> placeholders) {
        try {
            String message = messageResolver.apply(key, placeholders);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Configuration parsing must still expose a stable diagnostic if messages fail.
        }
        return fallbackMessage(key, placeholders);
    }

    private static String fallbackMessage(String key, Map<String, ?> placeholders) {
        if (placeholders.isEmpty() || placeholders.containsKey("path")) {
            return ConfigurationValues.fallbackMessage(key, placeholders);
        }
        return key + " [" + placeholders + "]";
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    record DatabaseSettings(long connectionTimeoutMillis, long busyTimeoutMillis) {
    }
}

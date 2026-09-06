package org.allivlisey.tianjitown.paper.config;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class RuntimeConfigurationValidator {
    private static final long MAXIMUM_TIMEOUT_MILLIS = 300_000;
    private static final String DATABASE_FILE_REQUIRED =
            "validation.runtime-configuration.database-file-required";
    private static final String RANGE = "validation.common.range";

    private RuntimeConfigurationValidator() {
    }

    public static DatabaseSettings validate(ConfigurationSection config) {
        return validate(config, RuntimeConfigurationValidator::fallbackMessage,
                key -> key);
    }

    public static DatabaseSettings validate(ConfigurationSection config,
                                     BiFunction<String, Map<String, ?>, String> messageResolver) {
        return validate(config, messageResolver,
                key -> messageResolver.apply(key, Map.of()));
    }

    public static DatabaseSettings validate(ConfigurationSection config,
                                     PluginMessages messages) {
        Objects.requireNonNull(messages, "messages");
        return validate(config, messages::plainText, messages::requiredPlainText);
    }

    private static DatabaseSettings validate(
            ConfigurationSection config,
            BiFunction<String, Map<String, ?>, String> messageResolver,
            Function<String, String> requiredMessageResolver) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messageResolver, "messageResolver");
        Objects.requireNonNull(requiredMessageResolver, "requiredMessageResolver");
        ConfigurationValues.bool(config, "town.maintenance-mode", false, messageResolver);
        ConfigurationValues.list(config, "town.service-stations", messageResolver);
        validateTown(config, messageResolver);
        EconomySettings economy = EconomySettings.load(config, messageResolver);
        ApplicationSettings.feeMinor(config, economy.fallbackScale(), messageResolver);
        BuffSettings.load(config, messageResolver,
                requiredMessageResolver);
        TownBonusSettings.load(config, messageResolver);
        return databaseSettings(config, messageResolver);
    }

    public static DatabaseSettings databaseSettings(ConfigurationSection config) {
        return databaseSettings(config, RuntimeConfigurationValidator::fallbackMessage);
    }

    public static DatabaseSettings databaseSettings(
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

    public record DatabaseSettings(long connectionTimeoutMillis, long busyTimeoutMillis) {
    }
}

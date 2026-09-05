package org.allivlisey.tianjitown.paper.config;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

public final class ConfigurationValues {
    private static final String BOOLEAN_TYPE = "validation.configuration.boolean-type";
    private static final String INT_RANGE = "validation.configuration.int-range";
    private static final String VALUE_REQUIRED = "validation.common.value-required";
    private static final String NUMBER_TYPE = "validation.configuration.number-type";
    private static final String FINITE_NUMBER = "validation.configuration.finite-number";
    private static final String TEXT_TYPE = "validation.configuration.text-type";
    private static final String DECIMAL_TEXT = "validation.configuration.decimal-text";
    private static final String STRING_LIST_TYPE =
            "validation.configuration.string-list-type";
    private static final String LIST_TYPE = "validation.configuration.list-type";
    private static final String INTEGER_TYPE = "validation.common.integer-type";
    private static final String LONG_RANGE = "validation.configuration.long-range";

    private ConfigurationValues() {
    }

    public static boolean bool(ConfigurationSection config, String path, boolean defaultValue) {
        return bool(config, path, defaultValue, ConfigurationValues::fallbackMessage);
    }

    public static boolean bool(ConfigurationSection config, String path, boolean defaultValue,
                        BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalidType(path, BOOLEAN_TYPE, messageResolver);
    }

    public static int integer(ConfigurationSection config, String path, int defaultValue) {
        return integer(config, path, defaultValue, ConfigurationValues::fallbackMessage);
    }

    public static int integer(ConfigurationSection config, String path, int defaultValue,
                       BiFunction<String, Map<String, ?>, String> messageResolver) {
        long value = longInteger(config, path, defaultValue, messageResolver);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(path, INT_RANGE, messageResolver);
        }
        return (int) value;
    }

    public static int integer(ConfigurationSection config, String path) {
        return integer(config, path, ConfigurationValues::fallbackMessage);
    }

    public static int integer(ConfigurationSection config, String path,
                       BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (value == null) {
            throw invalid(path, VALUE_REQUIRED, messageResolver);
        }
        return integerValue(path, value, messageResolver);
    }

    public static long longInteger(ConfigurationSection config, String path, long defaultValue) {
        return longInteger(config, path, defaultValue, ConfigurationValues::fallbackMessage);
    }

    public static long longInteger(ConfigurationSection config, String path, long defaultValue,
                            BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        return longIntegerValue(path, value, messageResolver);
    }

    public static double decimalNumber(ConfigurationSection config, String path) {
        return decimalNumber(config, path, ConfigurationValues::fallbackMessage);
    }

    public static double decimalNumber(ConfigurationSection config, String path,
                                BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (!(value instanceof Number number)) {
            throw invalidType(path, NUMBER_TYPE, messageResolver);
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw invalid(path, FINITE_NUMBER, messageResolver);
        }
        return result;
    }

    public static String text(ConfigurationSection config, String path, String defaultValue) {
        return text(config, path, defaultValue, ConfigurationValues::fallbackMessage);
    }

    public static String text(ConfigurationSection config, String path, String defaultValue,
                       BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String text) {
            return text;
        }
        throw invalidType(path, TEXT_TYPE, messageResolver);
    }

    public static String text(ConfigurationSection config, String path) {
        return text(config, path, ConfigurationValues::fallbackMessage);
    }

    public static String text(ConfigurationSection config, String path,
                       BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (!(value instanceof String text)) {
            throw invalidType(path, TEXT_TYPE, messageResolver);
        }
        return text;
    }

    public static BigDecimal decimalText(ConfigurationSection config, String path,
                                  String defaultValue) {
        return decimalText(config, path, defaultValue, ConfigurationValues::fallbackMessage);
    }

    public static BigDecimal decimalText(ConfigurationSection config, String path,
                                  String defaultValue,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        String value = text(config, path, defaultValue, messageResolver);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw invalid(path, DECIMAL_TEXT, messageResolver, exception);
        }
    }

    public static BigDecimal decimalText(ConfigurationSection config, String path) {
        return decimalText(config, path, ConfigurationValues::fallbackMessage);
    }

    public static BigDecimal decimalText(ConfigurationSection config, String path,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        String value = text(config, path, messageResolver);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw invalid(path, DECIMAL_TEXT, messageResolver, exception);
        }
    }

    public static List<String> stringList(ConfigurationSection config, String path) {
        return stringList(config, path, ConfigurationValues::fallbackMessage);
    }

    public static List<String> stringList(ConfigurationSection config, String path,
                                   BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (!(value instanceof List<?> values)
                || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw invalidType(path, STRING_LIST_TYPE, messageResolver);
        }
        return values.stream().map(String.class::cast).toList();
    }

    public static List<?> list(ConfigurationSection config, String path) {
        return list(config, path, ConfigurationValues::fallbackMessage);
    }

    public static List<?> list(ConfigurationSection config, String path,
                       BiFunction<String, Map<String, ?>, String> messageResolver) {
        Object value = config.get(path);
        if (!(value instanceof List<?> values)) {
            throw invalidType(path, LIST_TYPE, messageResolver);
        }
        return values;
    }

    private static int integerValue(String path, Object value,
                                    BiFunction<String, Map<String, ?>, String> messageResolver) {
        long result = longIntegerValue(path, value, messageResolver);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw invalid(path, INT_RANGE, messageResolver);
        }
        return (int) result;
    }

    private static long longIntegerValue(String path, Object value,
                                         BiFunction<String, Map<String, ?>, String>
                                                 messageResolver) {
        if (!(value instanceof Number number)) {
            throw invalidType(path, INTEGER_TYPE, messageResolver);
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(path, LONG_RANGE, messageResolver, exception);
        }
    }

    private static IllegalArgumentException invalidType(
            String path, String messageKey,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        return invalid(path, messageKey, messageResolver);
    }

    private static IllegalArgumentException invalid(
            String path, String messageKey,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        return new IllegalArgumentException(resolve(messageKey, path, messageResolver));
    }

    private static IllegalArgumentException invalid(
            String path, String messageKey,
            BiFunction<String, Map<String, ?>, String> messageResolver,
            RuntimeException cause) {
        return new IllegalArgumentException(resolve(messageKey, path, messageResolver), cause);
    }

    private static String resolve(String messageKey, String path,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(messageResolver, "messageResolver");
        Map<String, ?> placeholders = Map.of("path", safeText(path));
        try {
            String message = messageResolver.apply(messageKey, placeholders);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Configuration parsing must still expose a stable diagnostic if message loading fails.
        }
        return fallbackMessage(messageKey, placeholders);
    }

    public static String fallbackMessage(String key, Map<String, ?> placeholders) {
        Object path = placeholders.get("path");
        return path == null ? key : key + " [" + path + "]";
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }
}

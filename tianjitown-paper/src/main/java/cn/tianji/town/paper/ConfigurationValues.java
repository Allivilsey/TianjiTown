package cn.tianji.town.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.List;

final class ConfigurationValues {
    private ConfigurationValues() {
    }

    static boolean bool(ConfigurationSection config, String path, boolean defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalidType(path, "布尔值");
    }

    static int integer(ConfigurationSection config, String path, int defaultValue) {
        long value = longInteger(config, path, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " 超出 int 范围");
        }
        return (int) value;
    }

    static int integer(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (value == null) {
            throw new IllegalArgumentException(path + " 不能为空");
        }
        return integerValue(path, value);
    }

    static long longInteger(ConfigurationSection config, String path, long defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        return longIntegerValue(path, value);
    }

    static double decimalNumber(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (!(value instanceof Number number)) {
            throw invalidType(path, "数字");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(path + " 必须为有限数");
        }
        return result;
    }

    static String text(ConfigurationSection config, String path, String defaultValue) {
        Object value = config.get(path);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String text) {
            return text;
        }
        throw invalidType(path, "文本");
    }

    static String text(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (!(value instanceof String text)) {
            throw invalidType(path, "文本");
        }
        return text;
    }

    static BigDecimal decimalText(ConfigurationSection config, String path,
                                  String defaultValue) {
        String value = text(config, path, defaultValue);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " 必须为十进制文本", exception);
        }
    }

    static BigDecimal decimalText(ConfigurationSection config, String path) {
        String value = text(config, path);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " 必须为十进制文本", exception);
        }
    }

    static List<String> stringList(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (!(value instanceof List<?> values)
                || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw invalidType(path, "文本列表");
        }
        return values.stream().map(String.class::cast).toList();
    }

    static List<?> list(ConfigurationSection config, String path) {
        Object value = config.get(path);
        if (!(value instanceof List<?> values)) {
            throw invalidType(path, "列表");
        }
        return values;
    }

    private static int integerValue(String path, Object value) {
        long result = longIntegerValue(path, value);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " 超出 int 范围");
        }
        return (int) result;
    }

    private static long longIntegerValue(String path, Object value) {
        if (!(value instanceof Number number)) {
            throw invalidType(path, "整数");
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(path + " 必须为 long 范围内的整数", exception);
        }
    }

    private static IllegalArgumentException invalidType(String path, String expected) {
        return new IllegalArgumentException(path + " 必须为" + expected);
    }
}

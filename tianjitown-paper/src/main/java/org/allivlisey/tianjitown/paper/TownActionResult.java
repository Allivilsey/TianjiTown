package cn.tianji.town.paper;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家界面与测试命令共享的稳定业务结果。玩家文案由各入口自行负责。
 */
record TownActionResult(boolean success, String action, String reason,
                        Map<String, String> data) {
    TownActionResult {
        action = requireToken(action, "action");
        reason = success ? "NONE" : requireToken(reason, "reason");
        Map<String, String> copy = new LinkedHashMap<>();
        Objects.requireNonNull(data, "data").entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> copy.put(requireKey(entry.getKey()),
                        Objects.requireNonNull(entry.getValue(), "data value")));
        data = Collections.unmodifiableMap(copy);
    }

    static TownActionResult success(String action, Map<String, ?> data) {
        return new TownActionResult(true, action, "NONE", stringify(data));
    }

    static TownActionResult failure(String action, String reason) {
        return new TownActionResult(false, action, reason, Map.of());
    }

    static TownActionResult failure(String action, String reason, Map<String, ?> data) {
        return new TownActionResult(false, action, reason, stringify(data));
    }

    String machineLine() {
        StringBuilder output = new StringBuilder("RESULT success=")
                .append(success)
                .append(" action=").append(action);
        if (!success) {
            output.append(" reason=").append(reason);
        }
        data.forEach((key, value) -> output.append(' ').append(key).append('=')
                .append(quote(value)));
        return output.toString();
    }

    private static Map<String, String> stringify(Map<String, ?> values) {
        Map<String, String> result = new LinkedHashMap<>();
        Objects.requireNonNull(values, "values").forEach((key, value) ->
                result.put(key, Objects.toString(value, "null")));
        return result;
    }

    private static String quote(String value) {
        if (!value.isEmpty() && value.chars().allMatch(character ->
                Character.isLetterOrDigit(character) || "._:/@+-".indexOf(character) >= 0)) {
            return value;
        }
        return '"' + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + '"';
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException(name + " 必须是大写稳定标识");
        }
        return value;
    }

    private static String requireKey(String value) {
        Objects.requireNonNull(value, "data key");
        if (!value.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("data key 必须是小写稳定标识");
        }
        return value;
    }
}

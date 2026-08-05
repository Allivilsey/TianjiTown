package cn.tianji.town.core.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record ApplicationText(String name, String shortName, String description, List<String> rules) {
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}_\\-\\u00b7 ]+");
    private static final Pattern FORMAT_CODE = Pattern.compile("(?i)(?:§|&)[0-9A-FK-ORX]");
    private static final Pattern MINI_MESSAGE = Pattern.compile("<[^>\\r\\n]{1,64}>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");

    public ApplicationText {
        name = normalize(Objects.requireNonNull(name, "name"));
        shortName = normalize(Objects.requireNonNull(shortName, "shortName"));
        description = normalizeMultiline(Objects.requireNonNull(description, "description"));
        rules = Objects.requireNonNull(rules, "rules").stream()
                .map(ApplicationText::normalizeMultiline)
                .filter(rule -> !rule.isBlank())
                .toList();
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        validateName(name, "名称", 2, 24, errors);
        validateName(shortName, "简称", 1, 8, errors);
        validateSafeText(description, "简介", 500, errors);
        if (rules.isEmpty() || rules.size() > 50) {
            errors.add("规则数量必须为 1..50");
        }
        for (int index = 0; index < rules.size(); index++) {
            validateSafeText(rules.get(index), "规则 " + (index + 1), 300, errors);
        }
        return List.copyOf(errors);
    }

    public String normalizedName() {
        return normalizeKey(name);
    }

    public String normalizedShortName() {
        return normalizeKey(shortName);
    }

    public void requireValid() {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
    }

    private static void validateName(String value, String label, int minimum, int maximum,
                                     List<String> errors) {
        if (value.length() < minimum || value.length() > maximum) {
            errors.add(label + "长度必须为 " + minimum + ".." + maximum);
        } else if (!SAFE_NAME.matcher(value).matches()) {
            errors.add(label + "只能包含文字、数字、空格、下划线、连字符和间隔点");
        }
        validateFormatting(value, label, errors);
    }

    private static void validateSafeText(String value, String label, int maximum,
                                         List<String> errors) {
        if (value.length() > maximum) {
            errors.add(label + "不能超过 " + maximum + " 字符");
        }
        validateFormatting(value, label, errors);
    }

    private static void validateFormatting(String value, String label, List<String> errors) {
        if (FORMAT_CODE.matcher(value).find() || MINI_MESSAGE.matcher(value).find()
                || CONTROL.matcher(value).find()) {
            errors.add(label + "含有不允许的格式或控制字符");
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
    }

    private static String normalizeMultiline(String value) {
        return Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n').strip(),
                Normalizer.Form.NFC);
    }

    private static String normalizeKey(String value) {
        return normalize(value).toLowerCase(Locale.ROOT).replace(" ", "");
    }
}

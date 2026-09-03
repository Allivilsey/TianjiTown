package cn.tianji.town.core.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record ApplicationText(String name, String shortName, String residenceName,
                              String description, List<String> rules) {
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}_\\-\\u00b7 ]+");
    private static final Pattern RESIDENCE_NAME = Pattern.compile("[A-Za-z]+");
    private static final Pattern FORMAT_CODE = Pattern.compile("(?i)(?:§|&)[0-9A-FK-ORX]");
    private static final Pattern MINI_MESSAGE = Pattern.compile("<[^>\\r\\n]{1,64}>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");

    public ApplicationText {
        name = normalize(Objects.requireNonNull(name, "name"));
        shortName = normalize(Objects.requireNonNull(shortName, "shortName"));
        residenceName = normalize(Objects.requireNonNull(residenceName, "residenceName"));
        description = normalizeMultiline(Objects.requireNonNull(description, "description"));
        rules = Objects.requireNonNull(rules, "rules").stream()
                .map(ApplicationText::normalizeMultiline)
                .filter(rule -> !rule.isBlank())
                .toList();
    }

    public List<ValidationIssue> validate() {
        List<ValidationIssue> errors = new ArrayList<>();
        validateName(name, 2, 24, errors);
        if (residenceName.isEmpty() || residenceName.length() > 12) {
            errors.add(issue(ValidationIssue.Code.RESIDENCE_NAME_LENGTH,
                    bounds(1, 12)));
        } else if (!RESIDENCE_NAME.matcher(residenceName).matches()) {
            errors.add(issue(ValidationIssue.Code.RESIDENCE_NAME_CHARACTERS));
        }
        validateSafeText(description, 500, ValidationIssue.Code.DESCRIPTION_LENGTH,
                ValidationIssue.Code.DESCRIPTION_FORMAT, errors);
        if (rules.isEmpty() || rules.size() > 50) {
            errors.add(issue(ValidationIssue.Code.RULE_COUNT, bounds(1, 50)));
        }
        for (int index = 0; index < rules.size(); index++) {
            validateSafeText(rules.get(index), 300, ValidationIssue.Code.RULE_LENGTH,
                    ValidationIssue.Code.RULE_FORMAT, errors, index + 1);
        }
        return List.copyOf(errors);
    }

    public String normalizedName() {
        return normalizeNameKey(name);
    }

    public String normalizedShortName() {
        return normalizeKey(shortName);
    }

    public String normalizedResidenceName() {
        return residenceName.toLowerCase(Locale.ROOT);
    }

    public static String normalizeNameKey(String value) {
        return normalizeKey(Objects.requireNonNull(value, "value"));
    }

    public void requireValid() {
        List<ValidationIssue> errors = validate();
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private static void validateName(String value, int minimum, int maximum,
                                     List<ValidationIssue> errors) {
        if (value.length() < minimum || value.length() > maximum) {
            errors.add(issue(ValidationIssue.Code.NAME_LENGTH, bounds(minimum, maximum)));
        } else if (!SAFE_NAME.matcher(value).matches()) {
            errors.add(issue(ValidationIssue.Code.NAME_CHARACTERS));
        }
        validateFormatting(value, ValidationIssue.Code.NAME_FORMAT, errors);
    }

    private static void validateSafeText(String value, int maximum,
                                         ValidationIssue.Code lengthCode,
                                         ValidationIssue.Code formatCode,
                                         List<ValidationIssue> errors) {
        if (value.length() > maximum) {
            errors.add(issue(lengthCode, Map.of("maximum", Integer.toString(maximum))));
        }
        validateFormatting(value, formatCode, errors);
    }

    private static void validateSafeText(String value, int maximum,
                                         ValidationIssue.Code lengthCode,
                                         ValidationIssue.Code formatCode,
                                         List<ValidationIssue> errors, int ruleIndex) {
        if (value.length() > maximum) {
            errors.add(issue(lengthCode, Map.of("index", Integer.toString(ruleIndex),
                    "maximum", Integer.toString(maximum))));
        }
        validateFormatting(value, formatCode, errors, ruleIndex);
    }

    private static void validateFormatting(String value, ValidationIssue.Code code,
                                           List<ValidationIssue> errors) {
        if (FORMAT_CODE.matcher(value).find() || MINI_MESSAGE.matcher(value).find()
                || CONTROL.matcher(value).find()) {
            errors.add(issue(code));
        }
    }

    private static void validateFormatting(String value, ValidationIssue.Code code,
                                           List<ValidationIssue> errors, int ruleIndex) {
        if (FORMAT_CODE.matcher(value).find() || MINI_MESSAGE.matcher(value).find()
                || CONTROL.matcher(value).find()) {
            errors.add(issue(code, Map.of("index", Integer.toString(ruleIndex))));
        }
    }

    private static ValidationIssue issue(ValidationIssue.Code code) {
        return issue(code, Map.of());
    }

    private static ValidationIssue issue(ValidationIssue.Code code,
                                         Map<String, String> parameters) {
        return new ValidationIssue(code, parameters);
    }

    private static Map<String, String> bounds(int minimum, int maximum) {
        return Map.of("minimum", Integer.toString(minimum),
                "maximum", Integer.toString(maximum));
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

    public record ValidationIssue(Code code, Map<String, String> parameters) {
        public ValidationIssue {
            code = Objects.requireNonNull(code, "code");
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }

        public Field field() {
            return code.field();
        }

        public enum Code {
            NAME_LENGTH(Field.NAME),
            NAME_CHARACTERS(Field.NAME),
            NAME_FORMAT(Field.NAME),
            RESIDENCE_NAME_LENGTH(Field.RESIDENCE_NAME),
            RESIDENCE_NAME_CHARACTERS(Field.RESIDENCE_NAME),
            DESCRIPTION_LENGTH(Field.DESCRIPTION),
            DESCRIPTION_FORMAT(Field.DESCRIPTION),
            RULE_COUNT(Field.RULES),
            RULE_LENGTH(Field.RULES),
            RULE_FORMAT(Field.RULES);

            private final Field field;

            Code(Field field) {
                this.field = field;
            }

            public Field field() {
                return field;
            }
        }

        public enum Field {
            NAME,
            RESIDENCE_NAME,
            DESCRIPTION,
            RULES
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        private final List<ValidationIssue> issues;

        public ValidationException(List<ValidationIssue> issues) {
            super("APPLICATION_TEXT_VALIDATION_FAILED");
            if (issues == null || issues.isEmpty()) {
                throw new IllegalArgumentException("issues");
            }
            this.issues = List.copyOf(issues);
        }

        public List<ValidationIssue> issues() {
            return issues;
        }
    }
}

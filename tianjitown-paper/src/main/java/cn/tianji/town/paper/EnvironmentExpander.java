package cn.tianji.town.paper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EnvironmentExpander {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    String expand(String value) {
        Matcher matcher = TOKEN.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = System.getenv(matcher.group(1));
            if (replacement == null || replacement.isBlank()) {
                throw new IllegalArgumentException("缺少环境变量 " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}


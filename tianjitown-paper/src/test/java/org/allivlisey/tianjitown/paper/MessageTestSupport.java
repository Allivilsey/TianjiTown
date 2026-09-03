package org.allivlisey.tianjitown.paper;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MessageTestSupport {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private MessageTestSupport() {
    }

    static String assertConfigured(PluginMessages messages, String key) {
        return assertConfigured(messages, key,
                samplePlaceholders(messages.rawText(key)));
    }

    static String assertConfigured(PluginMessages messages, String key,
                                   Map<String, ?> placeholders) {
        assertTrue(messages.hasMessage(key), key);

        String rendered = messages.plainText(key, placeholders);
        assertFalse(rendered.isBlank(), key);
        assertFalse(rendered.startsWith("TT-MESSAGES-MISSING-KEY"), key);
        assertFalse(PLACEHOLDER.matcher(rendered).find(), key);
        return rendered;
    }

    private static Map<String, Object> samplePlaceholders(String template) {
        Map<String, Object> placeholders = new HashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            placeholders.put(matcher.group(1), "value-" + matcher.group(1));
        }
        return placeholders;
    }
}

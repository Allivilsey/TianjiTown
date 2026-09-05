package org.allivlisey.tianjitown.paper.config;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationValuesTest {
    private static final List<String> MESSAGE_KEYS = List.of(
            "validation.configuration.boolean-type",
            "validation.configuration.int-range",
            "validation.common.value-required",
            "validation.configuration.number-type",
            "validation.configuration.finite-number",
            "validation.configuration.text-type",
            "validation.configuration.decimal-text",
            "validation.configuration.string-list-type",
            "validation.configuration.list-type",
            "validation.common.integer-type",
            "validation.configuration.long-range");

    @TempDir
    Path temporaryDirectory;

    @Test
    void providesCompleteBuiltInMessagesForEveryValidationOutcome() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, String> expected = Map.ofEntries(
                Map.entry("validation.configuration.boolean-type", "config.value 必须为布尔值"),
                Map.entry("validation.configuration.int-range", "config.value 超出 int 范围"),
                Map.entry("validation.common.value-required", "config.value 不能为空"),
                Map.entry("validation.configuration.number-type", "config.value 必须为数字"),
                Map.entry("validation.configuration.finite-number", "config.value 必须为有限数"),
                Map.entry("validation.configuration.text-type", "config.value 必须为文本"),
                Map.entry("validation.configuration.decimal-text", "config.value 必须为十进制文本"),
                Map.entry("validation.configuration.string-list-type", "config.value 必须为文本列表"),
                Map.entry("validation.configuration.list-type", "config.value 必须为列表"),
                Map.entry("validation.common.integer-type", "config.value 必须为整数"),
                Map.entry("validation.configuration.long-range",
                        "config.value 必须为 long 范围内的整数"));

        for (String key : MESSAGE_KEYS) {
            String rendered = messages.plainText(key, Map.of("path", "config.value"));
            assertEquals(expected.get(key), rendered);
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{path}"));
        }
    }

    @Test
    void resolvesEveryValidationFailureThroughTheProvidedResolver() {
        YamlConfiguration config = new YamlConfiguration();
        BiFunction<String, Map<String, ?>, String> resolver =
                (key, placeholders) -> key + "|" + placeholders.get("path");

        config.set("bool", "true");
        assertValidation("validation.configuration.boolean-type",
                () -> ConfigurationValues.bool(config, "bool", false, resolver));

        config.set("int-range", Integer.MAX_VALUE + 1L);
        assertValidation("validation.configuration.int-range",
                () -> ConfigurationValues.integer(config, "int-range", 0, resolver));

        assertValidation("validation.common.value-required",
                () -> ConfigurationValues.integer(config, "missing", resolver));

        config.set("number", "1");
        assertValidation("validation.configuration.number-type",
                () -> ConfigurationValues.decimalNumber(config, "number", resolver));

        config.set("finite", new BigDecimal("1E+10000"));
        assertValidation("validation.configuration.finite-number",
                () -> ConfigurationValues.decimalNumber(config, "finite", resolver));

        config.set("text", 1);
        assertValidation("validation.configuration.text-type",
                () -> ConfigurationValues.text(config, "text", "default", resolver));
        assertValidation("validation.configuration.text-type",
                () -> ConfigurationValues.text(config, "text", resolver));

        config.set("decimal", "not-a-decimal");
        assertValidation("validation.configuration.decimal-text",
                () -> ConfigurationValues.decimalText(config, "decimal", "0", resolver));
        assertValidation("validation.configuration.decimal-text",
                () -> ConfigurationValues.decimalText(config, "decimal", resolver));

        config.set("string-list", List.of("ok", 1));
        assertValidation("validation.configuration.string-list-type",
                () -> ConfigurationValues.stringList(config, "string-list", resolver));

        config.set("list", "not-a-list");
        assertValidation("validation.configuration.list-type",
                () -> ConfigurationValues.list(config, "list", resolver));

        config.set("integer", "not-an-integer");
        assertValidation("validation.common.integer-type",
                () -> ConfigurationValues.longInteger(config, "integer", 0, resolver));

        config.set("long-range", 1.5D);
        assertValidation("validation.configuration.long-range",
                () -> ConfigurationValues.longInteger(config, "long-range", 0, resolver));
    }

    @Test
    void usesTheCurrentMessageOverrideWhenAConfigurationValueIsRead() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", "true");

        IllegalArgumentException initial = assertThrows(IllegalArgumentException.class,
                () -> ConfigurationValues.bool(config, "enabled", false,
                        messages::plainText));
        assertEquals("enabled 必须为布尔值", initial.getMessage());

        YamlConfiguration override = new YamlConfiguration();
        override.set("validation.configuration.boolean-type", "自定义配置类型: {path}");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        IllegalArgumentException reloaded = assertThrows(IllegalArgumentException.class,
                () -> ConfigurationValues.bool(config, "enabled", false,
                        messages::plainText));
        assertEquals("自定义配置类型: enabled", reloaded.getMessage());
    }

    @Test
    void passesTheResolverThroughRuntimeDatabaseConfigurationValidation() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.connection-timeout-ms", "5000");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.databaseSettings(config,
                        messages::plainText));

        assertEquals("database.connection-timeout-ms 必须为整数", exception.getMessage());
    }

    private static void assertValidation(String key, Runnable action) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(exception.getMessage().startsWith(key + "|"), exception.getMessage());
    }
}

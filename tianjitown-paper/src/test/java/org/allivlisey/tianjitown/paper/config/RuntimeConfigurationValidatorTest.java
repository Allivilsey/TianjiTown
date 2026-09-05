package org.allivlisey.tianjitown.paper.config;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigurationValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsDefaultConfigurationAndReturnsExactTimeouts() {
        RuntimeConfigurationValidator.DatabaseSettings settings =
                RuntimeConfigurationValidator.validate(configuration());

        assertEquals(5_000, settings.connectionTimeoutMillis());
        assertEquals(5_000, settings.busyTimeoutMillis());
    }

    @Test
    void rejectsWrongScalarTypesInsteadOfUsingBukkitFallbacks() {
        for (Setting setting : List.of(
                new Setting("database.connection-timeout-ms", "5000"),
                new Setting("database.busy-timeout-ms", "5000"),
                new Setting("town.application.reservation-minutes", "60"),
                new Setting("economy.tax.enabled", "true"),
                new Setting("buffs.shop-enabled", "true"))) {
            YamlConfiguration config = configuration();
            config.set(setting.path(), setting.value());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> RuntimeConfigurationValidator.validate(config));
            assertTrue(exception.getMessage().contains(setting.path()), exception.getMessage());
        }
    }

    @Test
    void ignoresLegacyGovernanceDurationConfiguration() {
        YamlConfiguration config = configuration();
        config.set("governance.transfer-confirmation-hours", "not-a-duration");
        config.set("governance.voting.active-member-days", -1);
        config.set("governance.voting.minimum-membership-days", Long.MAX_VALUE);
        config.set("governance.voting.duration-hours", 0);

        assertDoesNotThrow(() -> RuntimeConfigurationValidator.validate(config));
    }

    @Test
    void rendersRuntimeConfigurationValidationMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("database.file 不能为空",
                messages.plainText("validation.runtime-configuration.database-file-required"));
        assertEquals("town.application.reservation-minutes 必须在 1~1440 范围内",
                messages.plainText("validation.runtime-configuration.range", Map.of(
                        "path", "town.application.reservation-minutes", "minimum", 1,
                        "maximum", 1_440)));

        Map<String, ?> placeholders = Map.of(
                "path", "town.application.reservation-minutes", "minimum", 1, "maximum", 2);
        for (String key : List.of(
                "validation.runtime-configuration.database-file-required",
                "validation.runtime-configuration.range")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{"));
        }
    }

    @Test
    void routesEveryRuntimeConfigurationFailureThroughTheMessageResolver() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        YamlConfiguration database = configuration();
        database.set("database.file", " ");
        assertEquals("database.file 不能为空", databaseFailure(database, messages).getMessage());

        YamlConfiguration range = configuration();
        range.set("town.application.reservation-minutes", 0);
        assertEquals("town.application.reservation-minutes 必须在 1~1440 范围内",
                validationFailure(range, messages).getMessage());
    }

    @Test
    void usesRuntimeConfigurationValidationOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration config = configuration();
        config.set("town.application.reservation-minutes", 0);

        assertEquals("town.application.reservation-minutes 必须在 1~1440 范围内",
                validationFailure(config, messages).getMessage());

        YamlConfiguration override = new YamlConfiguration();
        override.set("validation.runtime-configuration.range",
                "自定义配置范围错误: {path} [{minimum}-{maximum}]");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义配置范围错误: town.application.reservation-minutes [1-1440]",
                validationFailure(config, messages).getMessage());
    }

    private static IllegalArgumentException validationFailure(YamlConfiguration config,
                                                               PluginMessages messages) {
        return assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.validate(config, messages::plainText));
    }

    private static IllegalArgumentException databaseFailure(YamlConfiguration config,
                                                              PluginMessages messages) {
        return assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.databaseSettings(config, messages::plainText));
    }

    private static YamlConfiguration configuration() {
        try (InputStream stream = RuntimeConfigurationValidatorTest.class
                .getResourceAsStream("/config.yml")) {
            if (stream == null) {
                throw new AssertionError("config.yml 未进入测试类路径");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream,
                    StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new AssertionError("读取默认配置失败", exception);
        }
    }

    private record Setting(String path, Object value) {
    }
}

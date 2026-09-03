package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigurationValidatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsDefaultConfigurationAndReturnsExactTimeouts() {
        RuntimeConfigurationValidator.DatabaseSettings settings =
                RuntimeConfigurationValidator.validate(configuration(),
                        world -> world.equals("world"));

        assertEquals(5_000, settings.connectionTimeoutMillis());
        assertEquals(5_000, settings.busyTimeoutMillis());
    }

    @Test
    void rejectsWrongScalarTypesInsteadOfUsingBukkitFallbacks() {
        for (Setting setting : List.of(
                new Setting("database.connection-timeout-ms", "5000"),
                new Setting("database.busy-timeout-ms", "5000"),
                new Setting("town.application.reservation-minutes", "60"),
                new Setting("governance.voting.duration-hours", "72"),
                new Setting("economy.tax.enabled", "true"),
                new Setting("buffs.shop-enabled", "true"))) {
            YamlConfiguration config = configuration();
            config.set(setting.path(), setting.value());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> RuntimeConfigurationValidator.validate(config,
                            world -> world.equals("world")));
            assertTrue(exception.getMessage().contains(setting.path()), exception.getMessage());
        }
    }

    @Test
    void rejectsReferencesToWorldsThatAreNotLoaded() {
        YamlConfiguration beacon = configuration();
        beacon.set("territory.beacon.allowed-worlds", List.of("missing_world"));
        IllegalArgumentException beaconFailure = assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.validate(beacon,
                        world -> world.equals("world")));
        assertTrue(beaconFailure.getMessage().contains("missing_world"));

        YamlConfiguration blacklist = configuration();
        LinkedHashMap<String, Object> area = new LinkedHashMap<>();
        blacklist.getMapList("town.site.blacklist").getFirst()
                .forEach((key, value) -> area.put(String.valueOf(key), value));
        area.put("world", "missing_world");
        blacklist.set("town.site.blacklist", List.of(area));
        IllegalArgumentException blacklistFailure = assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.validate(blacklist,
                        world -> world.equals("world")));
        assertTrue(blacklistFailure.getMessage().contains("missing_world"));
    }

    @Test
    void rendersRuntimeConfigurationValidationMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("territory.beacon.allowed-worlds 包含未加载世界: world_nether, world_the_end",
                messages.plainText("validation.runtime-configuration.beacon-worlds-unloaded",
                        Map.of("worlds", "world_nether, world_the_end")));
        assertEquals("database.file 不能为空",
                messages.plainText("validation.runtime-configuration.database-file-required"));
        assertEquals("town.site.blacklist 必须为区域列表",
                messages.plainText("validation.runtime-configuration.blacklist-type"));
        assertEquals("town.site.blacklist 包含未加载世界: world_nether",
                messages.plainText("validation.runtime-configuration.blacklist-world-unloaded",
                        Map.of("world", "world_nether")));
        assertEquals("town.site.blacklist 区域最小坐标不能大于最大坐标",
                messages.plainText("validation.runtime-configuration.blacklist-bounds"));
        assertEquals("town.site.blacklist.world 必须为非空文本",
                messages.plainText("validation.runtime-configuration.blacklist-text-required",
                        Map.of("path", "town.site.blacklist.world")));
        assertEquals("town.site.blacklist.min-chunk-x 必须为整数",
                messages.plainText("validation.runtime-configuration.blacklist-integer-type",
                        Map.of("path", "town.site.blacklist.min-chunk-x")));
        assertEquals("town.site.blacklist.min-chunk-x 必须为 int 范围内的整数",
                messages.plainText("validation.runtime-configuration.blacklist-integer-range",
                        Map.of("path", "town.site.blacklist.min-chunk-x")));
        assertEquals("town.application.reservation-minutes 必须在 1~1440 范围内",
                messages.plainText("validation.runtime-configuration.range", Map.of(
                        "path", "town.application.reservation-minutes", "minimum", 1,
                        "maximum", 1_440)));

        Map<String, ?> placeholders = Map.of(
                "path", "town.site.blacklist.min-chunk-x", "minimum", 1, "maximum", 2,
                "world", "world_nether", "worlds", "world_nether, world_the_end");
        for (String key : List.of(
                "validation.runtime-configuration.beacon-worlds-unloaded",
                "validation.runtime-configuration.database-file-required",
                "validation.runtime-configuration.blacklist-type",
                "validation.runtime-configuration.blacklist-world-unloaded",
                "validation.runtime-configuration.blacklist-bounds",
                "validation.runtime-configuration.blacklist-text-required",
                "validation.runtime-configuration.blacklist-integer-type",
                "validation.runtime-configuration.blacklist-integer-range",
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

        YamlConfiguration beacon = configuration();
        beacon.set("territory.beacon.allowed-worlds", List.of("missing_world"));
        assertEquals("territory.beacon.allowed-worlds 包含未加载世界: missing_world",
                validationFailure(beacon, messages).getMessage());

        YamlConfiguration blacklistType = configuration();
        blacklistType.set("town.site.blacklist", List.of("not-an-area"));
        assertEquals("town.site.blacklist 必须为区域列表",
                validationFailure(blacklistType, messages).getMessage());

        LinkedHashMap<String, Object> blacklistWorldArea = blacklistArea();
        blacklistWorldArea.put("world", "missing_world");
        YamlConfiguration blacklistWorld = configurationWithBlacklist(blacklistWorldArea);
        assertEquals("town.site.blacklist 包含未加载世界: missing_world",
                validationFailure(blacklistWorld, messages).getMessage());

        LinkedHashMap<String, Object> blacklistTextArea = blacklistArea();
        blacklistTextArea.put("world", " ");
        YamlConfiguration blacklistText = configurationWithBlacklist(blacklistTextArea);
        assertEquals("town.site.blacklist.world 必须为非空文本",
                validationFailure(blacklistText, messages).getMessage());

        LinkedHashMap<String, Object> blacklistIntegerTypeArea = blacklistArea();
        blacklistIntegerTypeArea.put("min-chunk-x", "not-an-integer");
        YamlConfiguration blacklistIntegerType = configurationWithBlacklist(
                blacklistIntegerTypeArea);
        assertEquals("town.site.blacklist.min-chunk-x 必须为整数",
                validationFailure(blacklistIntegerType, messages).getMessage());

        LinkedHashMap<String, Object> blacklistIntegerRangeArea = blacklistArea();
        blacklistIntegerRangeArea.put("min-chunk-x", 1.5D);
        YamlConfiguration blacklistIntegerRange = configurationWithBlacklist(
                blacklistIntegerRangeArea);
        assertEquals("town.site.blacklist.min-chunk-x 必须为 int 范围内的整数",
                validationFailure(blacklistIntegerRange, messages).getMessage());

        LinkedHashMap<String, Object> blacklistBoundsArea = blacklistArea();
        blacklistBoundsArea.put("min-chunk-x", 2);
        blacklistBoundsArea.put("max-chunk-x", 1);
        YamlConfiguration blacklistBounds = configurationWithBlacklist(blacklistBoundsArea);
        assertEquals("town.site.blacklist 区域最小坐标不能大于最大坐标",
                validationFailure(blacklistBounds, messages).getMessage());

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
                () -> RuntimeConfigurationValidator.validate(config,
                        world -> world.equals("world"), messages::plainText));
    }

    private static IllegalArgumentException databaseFailure(YamlConfiguration config,
                                                              PluginMessages messages) {
        return assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.databaseSettings(config, messages::plainText));
    }

    private static YamlConfiguration configurationWithBlacklist(Object area) {
        YamlConfiguration config = configuration();
        config.set("town.site.blacklist", List.of(area));
        return config;
    }

    private static LinkedHashMap<String, Object> blacklistArea() {
        LinkedHashMap<String, Object> area = new LinkedHashMap<>();
        area.put("world", "world");
        area.put("min-chunk-x", 0);
        area.put("max-chunk-x", 1);
        area.put("min-chunk-z", 0);
        area.put("max-chunk-z", 1);
        return area;
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

package org.allivlisey.tianjitown.paper;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownBonusSettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsSafeRefundBeaconAndOperationsSettings() throws Exception {
        PluginMessages messages = messages();
        TownBonusSettings settings = TownBonusSettings.load(configuration("REDSTONE_CATEGORY, CHEST",
                "0.25"), messages::plainText);

        assertEquals(0.25D, settings.buildingRefund().chance());
        assertEquals(3_000, settings.buildingRefund().weeklyLimit());
        assertTrue(settings.buildingRefund().blacklist().contains(Material.REDSTONE_BLOCK));
        assertTrue(settings.buildingRefund().blacklist().contains(Material.CHEST));
        assertTrue(settings.beacon().allowsWorld("WORLD"));
        assertEquals(100, settings.beacon().refreshIntervalTicks());
        assertEquals(14, settings.operations().backup().retentionCount());
    }

    @Test
    void rejectsInvalidBlacklistChanceAndWrongType() throws Exception {
        PluginMessages messages = messages();
        IllegalArgumentException material = assertThrows(IllegalArgumentException.class,
                () -> TownBonusSettings.load(configuration("NOT_A_MATERIAL", "0.25"),
                        messages::plainText));
        assertEquals("建筑返还黑名单材料无效: NOT_A_MATERIAL", material.getMessage());

        IllegalArgumentException chance = assertThrows(IllegalArgumentException.class,
                () -> TownBonusSettings.load(configuration("STONE", "1.5"),
                        messages::plainText));
        assertEquals("territory.building-refund.chance 必须在 (0, 1] 范围内",
                chance.getMessage());

        IllegalArgumentException wrongType = assertThrows(IllegalArgumentException.class,
                () -> TownBonusSettings.load(configuration("STONE", "not-a-number"),
                        messages::plainText));
        assertEquals("territory.building-refund.chance 必须为数字", wrongType.getMessage());
        assertTrue(TownBonusSettings.isRedstoneCategory(Material.OAK_BUTTON));
        assertFalse(TownBonusSettings.isSafeSingleBlock(Material.OAK_DOOR));
        assertFalse(TownBonusSettings.isSafeSingleBlock(Material.SHULKER_BOX));
    }

    @Test
    void rendersTownBonusValidationMessagesWithCompletePlaceholders() {
        PluginMessages messages = messages();
        Map<String, ?> placeholders = Map.of(
                "path", "territory.building-refund.chance",
                "minimum", 1,
                "maximum", 100_000,
                "value", "NOT_A_MATERIAL");
        List<String> keys = List.of(
                "validation.bonus.building-refund-chance-range",
                "validation.bonus.integer-range",
                "validation.bonus.building-refund-weekly-limit-range",
                "validation.bonus.building-refund-retention-range",
                "validation.bonus.building-refund-reset-zone-invalid",
                "validation.bonus.building-refund-blacklist-required",
                "validation.bonus.building-refund-blacklist-material-invalid",
                "validation.bonus.beacon-refresh-interval-range",
                "validation.bonus.beacon-worlds-required",
                "validation.bonus.diagnostic-days-range",
                "validation.bonus.backup-range",
                "validation.bonus.backup-directory-required");

        for (String key : keys) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }

        assertEquals("territory.building-refund.chance 必须在 (0, 1] 范围内",
                messages.plainText("validation.bonus.building-refund-chance-range",
                        Map.of("path", "territory.building-refund.chance")));
        assertEquals("territory.building-refund.weekly-limit 超出整数范围",
                messages.plainText("validation.bonus.integer-range",
                        Map.of("path", "territory.building-refund.weekly-limit")));
        assertEquals("territory.building-refund.weekly-limit 必须在 1~100000 范围内",
                messages.plainText("validation.bonus.building-refund-weekly-limit-range",
                        Map.of("path", "territory.building-refund.weekly-limit", "minimum", 1,
                                "maximum", 100_000)));
        assertEquals("territory.building-refund.counter-retention-weeks 必须在 2~260 范围内",
                messages.plainText("validation.bonus.building-refund-retention-range",
                        Map.of("path", "territory.building-refund.counter-retention-weeks",
                                "minimum", 2, "maximum", 260)));
        assertEquals("territory.building-refund.reset-zone 不是有效时区",
                messages.plainText("validation.bonus.building-refund-reset-zone-invalid",
                        Map.of("path", "territory.building-refund.reset-zone")));
        assertEquals("territory.building-refund.blacklist 至少需要一个方块或分组",
                messages.plainText("validation.bonus.building-refund-blacklist-required",
                        Map.of("path", "territory.building-refund.blacklist")));
        assertEquals("建筑返还黑名单材料无效: NOT_A_MATERIAL",
                messages.plainText("validation.bonus.building-refund-blacklist-material-invalid",
                        Map.of("value", "NOT_A_MATERIAL")));
        assertEquals("territory.beacon.refresh-interval-ticks 必须在 20~1200 范围内",
                messages.plainText("validation.bonus.beacon-refresh-interval-range",
                        Map.of("path", "territory.beacon.refresh-interval-ticks", "minimum", 20,
                                "maximum", 1_200)));
        assertEquals("territory.beacon.allowed-worlds 至少需要一个世界",
                messages.plainText("validation.bonus.beacon-worlds-required",
                        Map.of("path", "territory.beacon.allowed-worlds")));
        assertEquals("operations.quickshop-diagnostic-days 必须在 1~180 天之间",
                messages.plainText("validation.bonus.diagnostic-days-range",
                        Map.of("path", "operations.quickshop-diagnostic-days", "minimum", 1,
                                "maximum", 180)));
        assertEquals("定时备份间隔或保留数量超出安全范围",
                messages.plainText("validation.bonus.backup-range"));
        assertEquals("operations.backup.directory 不能为空",
                messages.plainText("validation.bonus.backup-directory-required",
                        Map.of("path", "operations.backup.directory")));
    }

    @Test
    void routesEveryTownBonusValidationFailureThroughConfiguredMessages() throws Exception {
        PluginMessages messages = messages();

        YamlConfiguration weeklyLimit = configuration("STONE", "0.25");
        weeklyLimit.set("territory.building-refund.weekly-limit", 0);
        assertFailure(messages, weeklyLimit,
                "territory.building-refund.weekly-limit 必须在 1~100000 范围内");

        YamlConfiguration integerRange = configuration("STONE", "0.25");
        integerRange.set("territory.building-refund.weekly-limit", Integer.MAX_VALUE + 1L);
        assertFailure(messages, integerRange,
                "territory.building-refund.weekly-limit 超出整数范围");

        YamlConfiguration retentionWeeks = configuration("STONE", "0.25");
        retentionWeeks.set("territory.building-refund.counter-retention-weeks", 1);
        assertFailure(messages, retentionWeeks,
                "territory.building-refund.counter-retention-weeks 必须在 2~260 范围内");

        YamlConfiguration resetZone = configuration("STONE", "0.25");
        resetZone.set("territory.building-refund.reset-zone", "not-a-time-zone");
        assertFailure(messages, resetZone,
                "territory.building-refund.reset-zone 不是有效时区");

        YamlConfiguration blacklist = configuration("STONE", "0.25");
        blacklist.set("territory.building-refund.blacklist", List.of());
        assertFailure(messages, blacklist,
                "territory.building-refund.blacklist 至少需要一个方块或分组");

        YamlConfiguration beaconRefresh = configuration("STONE", "0.25");
        beaconRefresh.set("territory.beacon.refresh-interval-ticks", 19);
        assertFailure(messages, beaconRefresh,
                "territory.beacon.refresh-interval-ticks 必须在 20~1200 范围内");

        YamlConfiguration beaconWorlds = configuration("STONE", "0.25");
        beaconWorlds.set("territory.beacon.allowed-worlds", List.of());
        assertFailure(messages, beaconWorlds,
                "territory.beacon.allowed-worlds 至少需要一个世界");

        YamlConfiguration diagnosticDays = configuration("STONE", "0.25");
        diagnosticDays.set("operations.quickshop-diagnostic-days", 0);
        assertFailure(messages, diagnosticDays,
                "operations.quickshop-diagnostic-days 必须在 1~180 天之间");

        YamlConfiguration backup = configuration("STONE", "0.25");
        backup.set("operations.backup.interval-hours", 0);
        assertFailure(messages, backup, "定时备份间隔或保留数量超出安全范围");

        YamlConfiguration directory = configuration("STONE", "0.25");
        directory.set("operations.backup.directory", " ");
        assertFailure(messages, directory, "operations.backup.directory 不能为空");
    }

    @Test
    void usesReloadedTownBonusValidationMessageForTheNextLoad() throws Exception {
        PluginMessages messages = messages();
        YamlConfiguration config = configuration("STONE", "1.5");

        assertFailure(messages, config,
                "territory.building-refund.chance 必须在 (0, 1] 范围内");

        YamlConfiguration override = new YamlConfiguration();
        override.set("validation.bonus.building-refund-chance-range",
                "自定义返还概率错误: {path}");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertFailure(messages, config,
                "自定义返还概率错误: territory.building-refund.chance");
    }

    private void assertFailure(PluginMessages messages, YamlConfiguration config,
                               String expected) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> TownBonusSettings.load(config, messages::plainText));
        assertEquals(expected, exception.getMessage());
    }

    private PluginMessages messages() {
        return new PluginMessages(temporaryDirectory.toFile());
    }

    private static YamlConfiguration configuration(String material, String chance)
            throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                territory:
                  building-refund:
                    enabled: true
                    chance: %s
                    weekly-limit: 3000
                    counter-retention-weeks: 12
                    reset-zone: Asia/Shanghai
                    blacklist: [%s]
                  beacon:
                    enabled: true
                    refresh-interval-ticks: 100
                    allowed-worlds: [world]
                  operations:
                    quickshop-diagnostic-days: 7
                    backup:
                      enabled: true
                      interval-hours: 6
                      retention-count: 14
                      directory: backups
                """.formatted(chance, material));
        return config;
    }
}

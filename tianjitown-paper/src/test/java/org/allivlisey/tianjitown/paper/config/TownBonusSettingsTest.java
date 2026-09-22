package org.allivlisey.tianjitown.paper.config;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

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
    void loadsSafeRefundAndBeaconSettings() throws Exception {
        PluginMessages messages = messages();
        TownBonusSettings settings = TownBonusSettings.load(configuration("REDSTONE_CATEGORY, CHEST",
                "0.25"), messages::plainText);

        assertEquals(0.25D, settings.buildingRefund().chance());
        assertEquals(3_000, settings.buildingRefund().weeklyLimit());
        assertTrue(settings.buildingRefund().blacklist().contains(Material.REDSTONE_BLOCK));
        assertTrue(settings.buildingRefund().blacklist().contains(Material.CHEST));
        assertEquals(100, settings.beacon().refreshIntervalTicks());
    }

    @Test
    void beaconRenewalIntervalMustStayBelowShortestNativeEffectDuration() throws Exception {
        PluginMessages messages = messages();
        YamlConfiguration config = configuration("STONE", "0.25");
        config.set("territory.beacon.refresh-interval-ticks", 200);
        assertEquals(200, TownBonusSettings.load(config, messages::plainText)
                .beacon().refreshIntervalTicks());
        config.set("territory.beacon.refresh-interval-ticks", 201);
        assertFailure(messages, config, messages.plainText("validation.common.range",
                Map.of("path", "territory.beacon.refresh-interval-ticks", "minimum", 20, "maximum", 200)));
    }

    @Test
    void rejectsInvalidBlacklistChanceAndWrongType() throws Exception {
        PluginMessages messages = messages();
        IllegalArgumentException material = assertThrows(IllegalArgumentException.class,
                () -> TownBonusSettings.load(configuration("NOT_A_MATERIAL", "0.25"),
                        messages::plainText));
        assertEquals(messages.plainText("validation.bonus.building-refund-blacklist-material-invalid",
                Map.of("value", "NOT_A_MATERIAL")),
                material.getMessage());

        IllegalArgumentException chance = assertThrows(IllegalArgumentException.class,
                () -> TownBonusSettings.load(configuration("STONE", "1.5"),
                        messages::plainText));
        assertEquals(messages.plainText("validation.bonus.building-refund-chance-range",
                Map.of("path", "territory.building-refund.chance")),
                chance.getMessage());

        IllegalArgumentException wrongType = assertThrows(IllegalArgumentException.class,
                () -> TownBonusSettings.load(configuration("STONE", "not-a-number"),
                        messages::plainText));
        assertEquals(messages.plainText("validation.configuration.number-type",
                Map.of("path", "territory.building-refund.chance")),
                wrongType.getMessage());
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
                "validation.common.range",
                "validation.common.range",
                "validation.bonus.building-refund-reset-zone-invalid",
                "validation.bonus.building-refund-blacklist-required",
                "validation.bonus.building-refund-blacklist-material-invalid",
                "validation.common.range");

        for (String key : keys) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertTrue(messages.hasMessage(key), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void routesEveryTownBonusValidationFailureThroughConfiguredMessages() throws Exception {
        PluginMessages messages = messages();

        YamlConfiguration weeklyLimit = configuration("STONE", "0.25");
        weeklyLimit.set("territory.building-refund.weekly-limit", 0);
        assertFailure(messages, weeklyLimit,
                messages.plainText("validation.common.range",
                        Map.of("path", "territory.building-refund.weekly-limit", "minimum", 1, "maximum", 100_000)));

        YamlConfiguration integerRange = configuration("STONE", "0.25");
        integerRange.set("territory.building-refund.weekly-limit", Integer.MAX_VALUE + 1L);
        assertFailure(messages, integerRange,
                messages.plainText("validation.bonus.integer-range",
                        Map.of("path", "territory.building-refund.weekly-limit")));

        YamlConfiguration retentionWeeks = configuration("STONE", "0.25");
        retentionWeeks.set("territory.building-refund.counter-retention-weeks", 1);
        assertFailure(messages, retentionWeeks,
                messages.plainText("validation.common.range",
                        Map.of("path", "territory.building-refund.counter-retention-weeks",
                                "minimum", 2, "maximum", 260)));

        YamlConfiguration resetZone = configuration("STONE", "0.25");
        resetZone.set("territory.building-refund.reset-zone", "not-a-time-zone");
        assertFailure(messages, resetZone,
                messages.plainText("validation.bonus.building-refund-reset-zone-invalid",
                        Map.of("path", "territory.building-refund.reset-zone")));

        YamlConfiguration blacklist = configuration("STONE", "0.25");
        blacklist.set("territory.building-refund.blacklist", List.of());
        assertFailure(messages, blacklist,
                messages.plainText("validation.bonus.building-refund-blacklist-required",
                        Map.of("path", "territory.building-refund.blacklist")));

        YamlConfiguration beaconRefresh = configuration("STONE", "0.25");
        beaconRefresh.set("territory.beacon.refresh-interval-ticks", 19);
        assertFailure(messages, beaconRefresh,
                messages.plainText("validation.common.range",
                        Map.of("path", "territory.beacon.refresh-interval-ticks", "minimum", 20, "maximum", 200)));
    }

    @Test
    void usesReloadedTownBonusValidationMessageForTheNextLoad() throws Exception {
        PluginMessages messages = messages();
        YamlConfiguration config = configuration("STONE", "1.5");

        assertFailure(messages, config,
                messages.plainText("validation.bonus.building-refund-chance-range",
                        Map.of("path", "territory.building-refund.chance")));

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
                """.formatted(chance, material));
        return config;
    }
}

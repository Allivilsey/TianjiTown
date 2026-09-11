package org.allivlisey.tianjitown.paper.config;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsSafeEconomyDefaults() {
        EconomySettings settings = EconomySettings.load(new MemoryConfiguration());

        assertTrue(settings.taxEnabled());
        assertTrue(settings.consumptionEnabled());
        assertEquals("tax", settings.settlementAccount());
        assertEquals(2, settings.fallbackScale());
        assertEquals(2500, settings.maximumTaxBps());
        assertEquals(new BigDecimal("5000.00"), settings.expansionCost());
        assertEquals(25, settings.maximumUnits());
        assertEquals(new BigDecimal("10000.00"), settings.weeklySubsidyLimit());
        assertEquals(new BigDecimal("2000.00"), settings.twelveHourSubsidyLimit());
        assertTrue(settings.allowsTaxRate(500));
        assertTrue(settings.allowsTaxRate(600));
        assertTrue(settings.allowsTaxRate(2500));
        assertFalse(settings.allowsTaxRate(2501));
        assertFalse(settings.allowsTaxRate(499));
        assertFalse(settings.allowsTaxRate(501));
    }

    @Test
    void rejectsUnsafeTaxMoneyAndExpansionSettings() {
        for (Setting setting : new Setting[]{
                new Setting("economy.money-scale", 9),
                new Setting("economy.expansion.base-cost", "0")}) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set(setting.path(), setting.value());
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> EconomySettings.load(config));
            assertTrue(exception.getMessage().contains(setting.path()));
        }
    }

    @Test
    void rendersAllEconomyValidationMessagesAndUsesReloadedOverrides() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        for (String key : List.of(
                "validation.common.value-required",
                "validation.economy.money-scale-range",
                "validation.economy.expansion-cost-positive",
                "validation.common.non-negative",
                "validation.common.non-negative",
                "validation.economy.twelve-hour-limit-exceeds-weekly",
                "validation.economy.expansion-cost-overflow",
                "validation.economy.expansion-cost-range")) {
            String rendered = messages.plainText(key, Map.of(
                    "path", "economy.example", "minimum", 0, "maximum", 8));
            assertFalse(rendered.isBlank());
            assertTrue(messages.hasMessage(key), key);
            assertFalse(rendered.contains("{"));
        }

        assertEquals(messages.plainText("validation.common.value-required",
                Map.of("path", "economy.settlement-account")),
                reject(configuration("economy.settlement-account", ""), messages).getMessage());
        assertEquals(messages.plainText("validation.economy.money-scale-range",
                Map.of("path", "economy.money-scale", "minimum", 0, "maximum", 8)),
                reject(configuration("economy.money-scale", 9), messages).getMessage());
        assertEquals(messages.plainText("validation.economy.expansion-cost-positive",
                Map.of("path", "economy.expansion.base-cost")),
                reject(configuration("economy.expansion.base-cost", "0"), messages)
                        .getMessage());
        assertEquals(messages.plainText("validation.common.non-negative",
                Map.of("path", "economy.tax.subsidy.weekly-limit")),
                reject(configuration("economy.tax.subsidy.weekly-limit", "-1"), messages)
                        .getMessage());
        assertEquals(messages.plainText("validation.common.non-negative",
                Map.of("path", "economy.tax.subsidy.twelve-hour-limit")),
                reject(configuration("economy.tax.subsidy.twelve-hour-limit", "-1"), messages)
                        .getMessage());

        MemoryConfiguration relation = new MemoryConfiguration();
        relation.set("economy.tax.subsidy.weekly-limit", "1.00");
        relation.set("economy.tax.subsidy.twelve-hour-limit", "2.00");
        assertEquals(messages.plainText("validation.economy.twelve-hour-limit-exceeds-weekly",
                Map.of("path", "economy.tax.subsidy.twelve-hour-limit")),
                reject(relation, messages).getMessage());

        IllegalArgumentException overflow = reject(
                configuration("economy.expansion.base-cost", "1E100"), messages);
        assertEquals(messages.plainText("validation.economy.expansion-cost-range",
                Map.of("path", "economy.expansion")),
                overflow.getMessage());
        assertEquals(messages.plainText("validation.economy.expansion-cost-overflow"),
                overflow.getCause().getMessage());

        String key = "validation.common.value-required";
        YamlConfiguration override = new YamlConfiguration();
        override.set(key, "自定义经济配置错误: {path}");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义经济配置错误: economy.settlement-account",
                reject(configuration("economy.settlement-account", ""), messages).getMessage());
    }

    private static IllegalArgumentException reject(MemoryConfiguration config,
                                                     PluginMessages messages) {
        return assertThrows(IllegalArgumentException.class,
                () -> EconomySettings.load(config, messages::plainText));
    }

    private static MemoryConfiguration configuration(String path, Object value) {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set(path, value);
        return config;
    }

    private record Setting(String path, Object value) {
    }
}

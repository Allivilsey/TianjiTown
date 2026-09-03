package cn.tianji.town.paper;

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
        assertEquals(new BigDecimal("3000.00"), settings.expansionCost());
        assertEquals(25, settings.maximumUnits());
        assertEquals(new BigDecimal("50000.00"), settings.weeklySubsidyLimit());
        assertEquals(new BigDecimal("5000.00"), settings.twelveHourSubsidyLimit());
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
                new Setting("economy.expansion.fixed-cost", "0")}) {
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
                "validation.economy.settlement-account-required",
                "validation.economy.money-scale-range",
                "validation.economy.expansion-cost-positive",
                "validation.economy.weekly-subsidy-limit-negative",
                "validation.economy.twelve-hour-subsidy-limit-negative",
                "validation.economy.twelve-hour-limit-exceeds-weekly",
                "validation.economy.expansion-cost-overflow",
                "validation.economy.expansion-cost-range")) {
            String rendered = messages.plainText(key, Map.of(
                    "path", "economy.example", "minimum", 0, "maximum", 8));
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{"));
        }

        assertEquals("economy.settlement-account 不能为空",
                reject(configuration("economy.settlement-account", ""), messages).getMessage());
        assertEquals("economy.money-scale 必须在 0~8 之间",
                reject(configuration("economy.money-scale", 9), messages).getMessage());
        assertEquals("economy.expansion.fixed-cost 必须大于 0",
                reject(configuration("economy.expansion.fixed-cost", "0"), messages)
                        .getMessage());
        assertEquals("economy.tax.subsidy.weekly-limit 不能小于 0",
                reject(configuration("economy.tax.subsidy.weekly-limit", "-1"), messages)
                        .getMessage());
        assertEquals("economy.tax.subsidy.twelve-hour-limit 不能小于 0",
                reject(configuration("economy.tax.subsidy.twelve-hour-limit", "-1"), messages)
                        .getMessage());

        MemoryConfiguration relation = new MemoryConfiguration();
        relation.set("economy.tax.subsidy.weekly-limit", "1.00");
        relation.set("economy.tax.subsidy.twelve-hour-limit", "2.00");
        assertEquals("economy.tax.subsidy.twelve-hour-limit 不能大于每周限额",
                reject(relation, messages).getMessage());

        IllegalArgumentException overflow = reject(
                configuration("economy.expansion.fixed-cost", "1E100"), messages);
        assertEquals("economy.expansion 价格超出次级货币单位范围", overflow.getMessage());
        assertEquals("金额超过 long 次级货币单位上限", overflow.getCause().getMessage());

        String key = "validation.economy.settlement-account-required";
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

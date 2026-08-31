package cn.tianji.town.paper;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySettingsTest {
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

    private record Setting(String path, Object value) {
    }
}

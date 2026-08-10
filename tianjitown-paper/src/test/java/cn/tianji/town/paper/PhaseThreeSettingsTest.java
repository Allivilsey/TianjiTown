package cn.tianji.town.paper;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseThreeSettingsTest {
    @Test
    void loadsSafePhaseThreeDefaults() {
        PhaseThreeSettings settings = PhaseThreeSettings.load(new MemoryConfiguration());

        assertTrue(settings.taxEnabled());
        assertTrue(settings.consumptionEnabled());
        assertEquals("tax", settings.settlementAccount());
        assertEquals(2, settings.fallbackScale());
        assertEquals(2500, settings.maximumTaxBps());
        assertEquals(new BigDecimal("1000.00"), settings.expansionBaseCost());
        assertEquals(new BigDecimal("1.50"), settings.expansionGrowthFactor());
        assertEquals(9, settings.maximumUnits());
        assertTrue(settings.allowsTaxRate(2500));
        assertFalse(settings.allowsTaxRate(2501));
        assertFalse(settings.allowsTaxRate(-1));
    }

    @Test
    void rejectsUnsafeTaxMoneyAndExpansionSettings() {
        for (Setting setting : new Setting[]{
                new Setting("phase3.money-scale", 9),
                new Setting("phase3.tax.maximum-basis-points", 10_000),
                new Setting("phase3.expansion.base-cost", "0"),
                new Setting("phase3.expansion.growth-factor", "0.99"),
                new Setting("phase3.expansion.maximum-units", 10)}) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set(setting.path(), setting.value());
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> PhaseThreeSettings.load(config));
            assertTrue(exception.getMessage().contains(setting.path()));
        }
    }

    private record Setting(String path, Object value) {
    }
}

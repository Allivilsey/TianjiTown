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
        assertEquals(new BigDecimal("500.00"), settings.expansionPerUnitIncrease());
        assertEquals(25, settings.maximumUnits());
        assertTrue(settings.allowsTaxRate(500));
        assertTrue(settings.allowsTaxRate(2500));
        assertFalse(settings.allowsTaxRate(2501));
        assertFalse(settings.allowsTaxRate(499));
        assertFalse(settings.allowsTaxRate(501));
    }

    @Test
    void rejectsUnsafeTaxMoneyAndExpansionSettings() {
        for (Setting setting : new Setting[]{
                new Setting("phase3.money-scale", 9),
                new Setting("phase3.expansion.base-cost", "0"),
                new Setting("phase3.expansion.per-unit-increase", "-0.01")}) {
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

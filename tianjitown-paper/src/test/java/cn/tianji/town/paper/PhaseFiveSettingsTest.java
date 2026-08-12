package cn.tianji.town.paper;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseFiveSettingsTest {
    @Test
    void loadsSafeRefundBeaconAndOperationsSettings() throws Exception {
        PhaseFiveSettings settings = PhaseFiveSettings.load(configuration("STONE", "0.25"));

        assertEquals(0.25D, settings.buildingRefund().chance());
        assertEquals(32, settings.buildingRefund().dailyLimit());
        assertTrue(settings.buildingRefund().materials().contains(Material.STONE));
        assertEquals(1.5D, settings.beacon().rangeMultiplier());
        assertEquals(2, settings.beacon().maximumEffectLevel());
        assertTrue(settings.beacon().allowsWorld("WORLD"));
        assertEquals(14, settings.operations().backup().retentionCount());
    }

    @Test
    void rejectsSpecialBlocksAndInvalidChance() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFiveSettings.load(configuration("CHEST", "0.25")));
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFiveSettings.load(configuration("STONE", "1.5")));
        assertFalse(PhaseFiveSettings.isSafeSingleBlock(Material.OAK_DOOR));
        assertFalse(PhaseFiveSettings.isSafeSingleBlock(Material.SHULKER_BOX));
    }

    private static YamlConfiguration configuration(String material, String chance)
            throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                phase5:
                  building-refund:
                    enabled: true
                    chance: %s
                    daily-limit: 32
                    counter-retention-days: 30
                    materials: [%s]
                  beacon:
                    enabled: true
                    range-multiplier: 1.5
                    maximum-range: 128
                    maximum-tier: 4
                    effect-level-bonus: 1
                    maximum-effect-level: 2
                    scan-interval-ticks: 100
                    allowed-worlds: [world]
                  operations:
                    quickshop-diagnostic-days: 7
                    diagnostics-interval-minutes: 60
                    backup:
                      enabled: true
                      interval-hours: 6
                      retention-count: 14
                      directory: backups
                """.formatted(chance, material));
        return config;
    }
}

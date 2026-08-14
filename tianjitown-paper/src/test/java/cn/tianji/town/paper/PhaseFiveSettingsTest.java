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
        PhaseFiveSettings settings = PhaseFiveSettings.load(configuration("REDSTONE_CATEGORY, CHEST",
                "0.25"));

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
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFiveSettings.load(configuration("NOT_A_MATERIAL", "0.25")));
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFiveSettings.load(configuration("STONE", "1.5")));
        IllegalArgumentException wrongType = assertThrows(IllegalArgumentException.class,
                () -> PhaseFiveSettings.load(configuration("STONE", "not-a-number")));
        assertTrue(wrongType.getMessage().contains("phase5.building-refund.chance"));
        assertTrue(PhaseFiveSettings.isRedstoneCategory(Material.OAK_BUTTON));
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

package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffStackingRule;
import cn.tianji.town.core.town.MemberRole;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseFourSettingsTest {
    @Test
    void loadsConfiguredBuffAndResourceCatalogs() throws Exception {
        YamlConfiguration config = configuration("IRON_INGOT", "1.5");

        PhaseFourSettings settings = PhaseFourSettings.load(config);

        assertTrue(settings.buffShopEnabled());
        assertEquals(BuffStackingRule.LEVEL_UP,
                settings.requireBuff("speed").stackingRule());
        assertEquals(Set.of(MemberRole.MAYOR),
                settings.requireBuff("speed").purchasingRoles());
        assertEquals("minecraft:iron_ingot",
                settings.requireResource("iron").materialKey());
        assertEquals(64, settings.requireResource("iron").maximumPerOrder());
    }

    @Test
    void rejectsInvalidCatalogValues() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFourSettings.load(configuration("NOT_AN_ITEM", "1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFourSettings.load(configuration("IRON_INGOT", "0.5")));
    }

    private static YamlConfiguration configuration(String material, String multiplier)
            throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                phase4:
                  buffs:
                    shop-enabled: true
                    catalog:
                      speed:
                        display-name: 公共迅捷
                        effect-kind: POTION
                        effect-key: minecraft:speed
                        operation: AMPLIFIER
                        base-price: '100.00'
                        price-multiplier: '%s'
                        duration-minutes: 60
                        maximum-level: 2
                        stacking: LEVEL_UP
                        amount-per-level: 1.0
                        allowed-worlds: [world]
                        purchasing-roles: [MAYOR]
                  resources:
                    shop-enabled: true
                    catalog:
                      iron:
                        display-name: 铁锭
                        material: %s
                        unit-price: '5.00'
                        maximum-per-order: 64
                        daily-limit: 128
                        quantity-options: [16, 32, 64]
                        purchasing-roles: [MAYOR, OFFICER]
                """.formatted(multiplier, material));
        return config;
    }
}

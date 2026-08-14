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
    void loadsConfiguredBuffCatalog() throws Exception {
        YamlConfiguration config = configuration("100.00", "MAYOR");

        PhaseFourSettings settings = PhaseFourSettings.load(config);

        assertTrue(settings.buffShopEnabled());
        assertEquals(BuffStackingRule.LEVEL_UP,
                settings.requireBuff("speed").stackingRule());
        assertEquals(Set.of(MemberRole.MAYOR),
                settings.requireBuff("speed").purchasingRoles());
    }

    @Test
    void rejectsInvalidCatalogValues() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFourSettings.load(configuration("100.00", "OFFICER")));
        assertThrows(IllegalArgumentException.class,
                () -> PhaseFourSettings.load(configuration("0", "MAYOR")));
    }

    private static YamlConfiguration configuration(String basePrice, String role)
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
                        base-price: '%s'
                        maximum-level: 2
                        stacking: LEVEL_UP
                        amount-per-level: 1.0
                        purchasing-roles: [%s]
                """.formatted(basePrice, role));
        return config;
    }
}

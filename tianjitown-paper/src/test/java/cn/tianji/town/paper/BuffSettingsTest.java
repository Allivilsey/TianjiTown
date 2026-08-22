package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffStackingRule;
import cn.tianji.town.core.town.MemberRole;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuffSettingsTest {
    @Test
    void loadsConfiguredBuffCatalog() throws Exception {
        YamlConfiguration config = configuration("100.00", "MAYOR", "LEVEL_UP");

        BuffSettings settings = BuffSettings.load(config);

        assertTrue(settings.buffShopEnabled());
        assertEquals(BuffStackingRule.LEVEL_UP,
                settings.requireBuff("speed").stackingRule());
        assertEquals(Set.of(MemberRole.MAYOR),
                settings.requireBuff("speed").purchasingRoles());
        assertEquals(BuffStackingRule.EXTEND,
                BuffSettings.load(configuration("100.00", "MAYOR", "EXTEND"))
                        .requireBuff("speed").stackingRule());
        assertEquals(BuffStackingRule.REFRESH,
                BuffSettings.load(configuration("100.00", "MAYOR", "REFRESH"))
                        .requireBuff("speed").stackingRule());
    }

    @Test
    void rejectsInvalidCatalogValues() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("100.00", "OFFICER", "LEVEL_UP")));
        assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("0", "MAYOR", "LEVEL_UP")));
        IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("1E1000000", "MAYOR", "LEVEL_UP")));
        assertTrue(overflow.getMessage().contains("次级货币单位范围"));
    }

    private static YamlConfiguration configuration(String basePrice, String role, String stacking)
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
                        stacking: %s
                        amount-per-level: 1.0
                        purchasing-roles: [%s]
                """.formatted(basePrice, stacking, role));
        return config;
    }
}

package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffDefinition;
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
        assertEquals(BuffDefinition.EffectKind.ATTRIBUTE,
                settings.requireBuff("speed").effectKind());
        assertEquals("minecraft:movement_speed", settings.requireBuff("speed").effectKey());
        assertEquals("ADD_SCALAR", settings.requireBuff("speed").effectOperation());
        assertEquals(0.2D, settings.requireBuff("speed").amountPerLevel());
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
        assertThrows(IllegalArgumentException.class,
                () -> new BuffDefinition("negative", "负数效果",
                        BuffDefinition.EffectKind.ATTRIBUTE, "minecraft:movement_speed",
                        "ADD_SCALAR", new java.math.BigDecimal("10.00"), 1,
                        BuffStackingRule.LEVEL_UP, -0.2D, Set.of(MemberRole.MAYOR)));
        IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("1E1000000", "MAYOR", "LEVEL_UP")));
        assertTrue(overflow.getMessage().contains("次级货币单位范围"));
    }

    private static YamlConfiguration configuration(String basePrice, String role, String stacking)
            throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                buffs:
                  shop-enabled: true
                  catalog:
                    speed:
                      display-name: 公共迅捷
                      effect-kind: ATTRIBUTE
                      effect-key: minecraft:movement_speed
                      operation: ADD_SCALAR
                      base-price: '%s'
                      maximum-level: 2
                      stacking: %s
                      amount-per-level: 0.2
                      purchasing-roles: [%s]
                """.formatted(basePrice, stacking, role));
        return config;
    }
}

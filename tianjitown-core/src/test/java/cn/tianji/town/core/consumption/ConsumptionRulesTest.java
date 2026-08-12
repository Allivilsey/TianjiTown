package cn.tianji.town.core.consumption;

import cn.tianji.town.core.town.MemberRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsumptionRulesTest {
    @Test
    void calculatesEscalatingBuffPricesAndLevelCap() {
        BuffDefinition definition = new BuffDefinition("speed", "公共速度",
                BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                new BigDecimal("100.00"), new BigDecimal("1.50"), Duration.ofHours(1),
                3, BuffStackingRule.LEVEL_UP, 1, Set.of("world"),
                Set.of(MemberRole.MAYOR, MemberRole.DEPUTY_MAYOR));

        assertEquals(10_000, BuffPricing.price(definition, 0, 2).minorUnits());
        assertEquals(15_000, BuffPricing.price(definition, 1, 2).minorUnits());
        assertEquals(new BuffPricing.NextStack(3, 3), BuffPricing.next(definition, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> BuffPricing.next(definition, 3, 3));
    }
}

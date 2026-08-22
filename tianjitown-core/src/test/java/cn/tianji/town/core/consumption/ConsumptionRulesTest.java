package cn.tianji.town.core.consumption;

import cn.tianji.town.core.town.MemberRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsumptionRulesTest {
    @Test
    void calculatesLinearDurationPricesWithDiscountsAndLevelCap() {
        BuffDefinition definition = new BuffDefinition("speed", "公共速度",
                BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                new BigDecimal("100.00"), 3, BuffStackingRule.LEVEL_UP, 1,
                Set.of(MemberRole.MAYOR, MemberRole.DEPUTY_MAYOR));

        assertEquals(10_000, BuffPricing.price(definition, BuffDurationOption.ONE_HOUR,
                1, 2).minorUnits());
        assertEquals(216_000, BuffPricing.price(definition, BuffDurationOption.ONE_DAY,
                1, 2).minorUnits());
        assertEquals(20_000, BuffPricing.price(definition, BuffDurationOption.ONE_HOUR,
                2, 2).minorUnits());
        assertEquals(new BuffPricing.NextStack(3, 3), BuffPricing.next(definition, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> BuffPricing.next(definition, 3, 3));
    }

    @Test
    void appliesEveryDurationDiscountWithDeterministicRounding() {
        BuffDefinition definition = new BuffDefinition("duration", "时长测试",
                BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                new BigDecimal("100.00"), 1, BuffStackingRule.LEVEL_UP, 1,
                Set.of(MemberRole.MAYOR));

        assertEquals(10_000, BuffPricing.price(definition, BuffDurationOption.ONE_HOUR,
                1, 2).minorUnits());
        assertEquals(216_000, BuffPricing.price(definition, BuffDurationOption.ONE_DAY,
                1, 2).minorUnits());
        assertEquals(1_344_000, BuffPricing.price(definition, BuffDurationOption.ONE_WEEK,
                1, 2).minorUnits());
        assertEquals(5_040_000, BuffPricing.price(definition, BuffDurationOption.ONE_MONTH,
                1, 2).minorUnits());

        BuffDefinition fractional = new BuffDefinition("fractional", "舍入测试",
                BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                new BigDecimal("0.01"), 1, BuffStackingRule.LEVEL_UP, 1,
                Set.of(MemberRole.MAYOR));
        assertEquals(135, BuffPricing.price(fractional, BuffDurationOption.ONE_WEEK,
                1, 2).minorUnits());
    }
}

package org.allivlisey.tianjitown.core.consumption;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsumptionRulesTest {
    @Test
    void pricesWeeklyCatalogWithFivePercentagePointsPerAdditionalWeek() {
        BuffDefinition speed = new BuffDefinition("speed", "速度",
                BuffDefinition.EffectKind.ATTRIBUTE, "minecraft:movement_speed", "ADD_SCALAR",
                new BigDecimal("6720"), 5, BuffStackingRule.LEVEL_UP, 0.2);
        long[] prices = {672_000, 1_276_800, 1_814_400, 2_284_800};
        for (int weeks = 1; weeks <= 4; weeks++) {
            assertEquals(prices[weeks - 1], BuffPricing.weeklyPrice(speed, weeks, 1, 2).minorUnits());
            assertEquals(prices[weeks - 1] * 5,
                    BuffPricing.weeklyPrice(speed, weeks, 5, 2).minorUnits());
        }
        assertEquals(BuffPricing.weeklyPrice(speed, 1, 1, 2),
                BuffPricing.price(speed, BuffDurationOption.ONE_WEEK, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> BuffPricing.weeklyPrice(speed, 0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> BuffPricing.weeklyPrice(speed, 5, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> BuffPricing.weeklyPrice(speed, 1, 6, 2));
        BuffDefinition singleLevel = new BuffDefinition("night_vision", "夜视",
                BuffDefinition.EffectKind.POTION, "minecraft:night_vision", "AMPLIFIER",
                new BigDecimal("0.01"), 1, BuffStackingRule.REFRESH, 1);
        assertEquals(2, BuffPricing.weeklyPrice(singleLevel, 2, 1, 2).minorUnits());
        assertThrows(IllegalArgumentException.class,
                () -> BuffPricing.weeklyPrice(singleLevel, 1, 2, 2));
    }

    @Test
    void keepsDurationOptionsAsStableBusinessValuesWithoutPresentationText() {
        assertEquals(1, BuffDurationOption.ONE_HOUR.hours());
        assertEquals(24, BuffDurationOption.ONE_DAY.hours());
        assertEquals(24 * 7, BuffDurationOption.ONE_WEEK.hours());
        assertEquals(24 * 30, BuffDurationOption.ONE_MONTH.hours());
        assertEquals(10_000, BuffDurationOption.ONE_HOUR.discountBasisPoints());
        assertEquals(10_000, BuffDurationOption.ONE_DAY.discountBasisPoints());
        assertEquals(10_000, BuffDurationOption.ONE_WEEK.discountBasisPoints());
        assertEquals(8_500, BuffDurationOption.ONE_MONTH.discountBasisPoints());
    }

    @Test
    void calculatesLinearDurationPricesWithDiscountsAndLevelCap() {
        BuffDefinition definition = new BuffDefinition("speed", "公共速度",
                BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                new BigDecimal("100.00"), 3, BuffStackingRule.LEVEL_UP, 1);

        assertEquals(60, BuffPricing.price(definition, BuffDurationOption.ONE_HOUR,
                1, 2).minorUnits());
        assertEquals(1_429, BuffPricing.price(definition, BuffDurationOption.ONE_DAY,
                1, 2).minorUnits());
        assertEquals(120, BuffPricing.price(definition, BuffDurationOption.ONE_HOUR,
                2, 2).minorUnits());
        assertEquals(new BuffPricing.NextStack(3, 3), BuffPricing.next(definition, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> BuffPricing.next(definition, 3, 3));
    }

    @Test
    void appliesEveryDurationDiscountWithDeterministicRounding() {
        BuffDefinition definition = new BuffDefinition("duration", "时长测试",
                BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                new BigDecimal("100.00"), 1, BuffStackingRule.LEVEL_UP, 1);

        assertEquals(60, BuffPricing.price(definition, BuffDurationOption.ONE_HOUR,
                1, 2).minorUnits());
        assertEquals(1_429, BuffPricing.price(definition, BuffDurationOption.ONE_DAY,
                1, 2).minorUnits());
        assertEquals(10_000, BuffPricing.price(definition, BuffDurationOption.ONE_WEEK,
                1, 2).minorUnits());
        assertEquals(36_429, BuffPricing.price(definition, BuffDurationOption.ONE_MONTH,
                1, 2).minorUnits());

        BuffDefinition fractional = new BuffDefinition("fractional", "舍入测试",
                BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                new BigDecimal("0.01"), 1, BuffStackingRule.LEVEL_UP, 1);
        assertEquals(1, BuffPricing.price(fractional, BuffDurationOption.ONE_WEEK,
                1, 2).minorUnits());
    }
}

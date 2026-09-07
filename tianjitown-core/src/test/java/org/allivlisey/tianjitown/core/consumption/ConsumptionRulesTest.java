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
                new BigDecimal("6720"), 5, 0.2);
        long[] prices = {672_000, 1_276_800, 1_814_400, 2_284_800};
        for (int weeks = 1; weeks <= 4; weeks++) {
            assertEquals(prices[weeks - 1], BuffPricing.weeklyPrice(speed, weeks, 1, 2).minorUnits());
            assertEquals(prices[weeks - 1] * 5,
                    BuffPricing.weeklyPrice(speed, weeks, 5, 2).minorUnits());
        }
        assertThrows(IllegalArgumentException.class, () -> BuffPricing.weeklyPrice(speed, 0, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> BuffPricing.weeklyPrice(speed, 5, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> BuffPricing.weeklyPrice(speed, 1, 6, 2));
        BuffDefinition singleLevel = new BuffDefinition("night_vision", "夜视",
                BuffDefinition.EffectKind.POTION, "minecraft:night_vision", "AMPLIFIER",
                new BigDecimal("0.01"), 1, 1);
        assertEquals(2, BuffPricing.weeklyPrice(singleLevel, 2, 1, 2).minorUnits());
        assertThrows(IllegalArgumentException.class,
                () -> BuffPricing.weeklyPrice(singleLevel, 1, 2, 2));
    }

}

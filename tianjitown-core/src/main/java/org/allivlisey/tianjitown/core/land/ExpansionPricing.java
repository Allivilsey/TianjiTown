package org.allivlisey.tianjitown.core.land;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ExpansionPricing {
    private ExpansionPricing() {
    }

    public static long batchPriceMinor(BigDecimal baseCost, int completedExpansions,
                                      int count, int scale) {
        if (count < 0 || completedExpansions < 0
                || (long) completedExpansions + count >= TerritoryRules.MAXIMUM_UNITS) {
            throw new IllegalArgumentException("批量扩张次数超出领地网格范围");
        }
        long total = 0;
        for (int index = 0; index < count; index++) {
            total = Math.addExact(total, price(baseCost, completedExpansions + index, scale)
                    .minorUnits());
        }
        return total;
    }

    public static MoneyAmount price(BigDecimal baseCost, int completedExpansions, int scale) {
        if (baseCost == null || baseCost.signum() <= 0) {
            throw new IllegalArgumentException("扩张基础价格必须大于 0");
        }
        if (completedExpansions < 0 || completedExpansions >= TerritoryRules.MAXIMUM_UNITS) {
            throw new IllegalArgumentException("已扩张次数超出领地网格范围");
        }
        return MoneyAmount.rounded(baseCost.multiply(new BigDecimal("1.05")
                .pow(completedExpansions)), scale, RoundingMode.CEILING);
    }
}

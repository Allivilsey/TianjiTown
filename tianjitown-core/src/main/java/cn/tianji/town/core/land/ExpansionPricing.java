package cn.tianji.town.core.land;

import cn.tianji.town.core.economy.MoneyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ExpansionPricing {
    private ExpansionPricing() {
    }

    public static MoneyAmount price(BigDecimal baseCost, BigDecimal perUnitIncrease,
                                    int currentUnitCount, int scale) {
        if (baseCost == null || baseCost.signum() <= 0) {
            throw new IllegalArgumentException("扩张基础价格必须大于 0");
        }
        if (perUnitIncrease == null || perUnitIncrease.signum() < 0) {
            throw new IllegalArgumentException("扩张每单元增价不能小于 0");
        }
        if (currentUnitCount < 1) {
            throw new IllegalArgumentException("当前领地单元数必须大于 0");
        }
        BigDecimal value = baseCost.add(perUnitIncrease.multiply(
                BigDecimal.valueOf(currentUnitCount - 1L)));
        return MoneyAmount.rounded(value, scale, RoundingMode.CEILING);
    }
}

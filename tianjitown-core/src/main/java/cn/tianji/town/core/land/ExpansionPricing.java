package cn.tianji.town.core.land;

import cn.tianji.town.core.economy.MoneyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ExpansionPricing {
    private ExpansionPricing() {
    }

    public static MoneyAmount price(BigDecimal baseCost, BigDecimal growthFactor,
                                    int currentUnitCount, int scale) {
        if (baseCost == null || baseCost.signum() <= 0) {
            throw new IllegalArgumentException("扩张基础价格必须大于 0");
        }
        if (growthFactor == null || growthFactor.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("扩张价格增长倍率不能小于 1");
        }
        if (currentUnitCount < 1) {
            throw new IllegalArgumentException("当前领地单元数必须大于 0");
        }
        BigDecimal value = baseCost.multiply(growthFactor.pow(currentUnitCount - 1));
        return MoneyAmount.rounded(value, scale, RoundingMode.CEILING);
    }
}

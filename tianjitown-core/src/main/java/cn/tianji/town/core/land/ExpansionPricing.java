package cn.tianji.town.core.land;

import cn.tianji.town.core.economy.MoneyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ExpansionPricing {
    private ExpansionPricing() {
    }

    public static MoneyAmount price(BigDecimal fixedCost, int scale) {
        if (fixedCost == null || fixedCost.signum() <= 0) {
            throw new IllegalArgumentException("扩张固定价格必须大于 0");
        }
        return MoneyAmount.rounded(fixedCost, scale, RoundingMode.CEILING);
    }
}

package cn.tianji.town.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record TaxRate(int basisPoints) {
    public static final int BASIS_POINT_DIVISOR = 10_000;

    public TaxRate {
        if (basisPoints < 0 || basisPoints >= BASIS_POINT_DIVISOR) {
            throw new IllegalArgumentException("税率基点必须在 0~9999 之间");
        }
    }

    public BigDecimal decimalRate() {
        return BigDecimal.valueOf(basisPoints, 4);
    }

    public MoneyAmount tax(MoneyAmount gross) {
        if (gross == null || gross.minorUnits() < 0) {
            throw new IllegalArgumentException("计税收入不能小于 0");
        }
        BigDecimal value = gross.decimal().multiply(decimalRate());
        return MoneyAmount.rounded(value, gross.scale(), RoundingMode.HALF_UP);
    }
}

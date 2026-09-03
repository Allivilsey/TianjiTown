package cn.tianji.town.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record MoneyAmount(long minorUnits, int scale) implements Comparable<MoneyAmount> {
    public MoneyAmount {
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("金额精度必须在 0~8 之间");
        }
    }

    public static MoneyAmount from(BigDecimal amount, int scale) {
        if (amount == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        BigDecimal scaled = amount.setScale(scale, RoundingMode.UNNECESSARY)
                .movePointRight(scale);
        return new MoneyAmount(scaled.longValueExact(), scale);
    }

    public static MoneyAmount rounded(BigDecimal amount, int scale, RoundingMode roundingMode) {
        if (amount == null || roundingMode == null) {
            throw new IllegalArgumentException("金额和舍入规则不能为空");
        }
        BigDecimal scaled = amount.setScale(scale, roundingMode).movePointRight(scale);
        return new MoneyAmount(scaled.longValueExact(), scale);
    }

    public BigDecimal decimal() {
        return BigDecimal.valueOf(minorUnits, scale);
    }

    public MoneyAmount add(MoneyAmount other) {
        requireSameScale(other);
        return new MoneyAmount(Math.addExact(minorUnits, other.minorUnits), scale);
    }

    public MoneyAmount subtract(MoneyAmount other) {
        requireSameScale(other);
        return new MoneyAmount(Math.subtractExact(minorUnits, other.minorUnits), scale);
    }

    public MoneyAmount negate() {
        return new MoneyAmount(Math.negateExact(minorUnits), scale);
    }

    public boolean positive() {
        return minorUnits > 0;
    }

    @Override
    public int compareTo(MoneyAmount other) {
        requireSameScale(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameScale(MoneyAmount other) {
        if (other == null || scale != other.scale) {
            throw new IllegalArgumentException("金额精度不一致");
        }
    }
}

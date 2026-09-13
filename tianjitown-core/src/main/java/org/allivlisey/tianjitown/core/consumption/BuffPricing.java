package org.allivlisey.tianjitown.core.consumption;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class BuffPricing {
    private BuffPricing() {
    }

    public static long remainingRefund(long paidMinor, java.time.Instant startsAt,
                                       java.time.Instant expiresAt, java.time.Instant now) {
        if (paidMinor < 0) throw new IllegalArgumentException("Buff 支付金额不能为负数");
        java.math.BigInteger total = java.math.BigInteger.valueOf(expiresAt.toEpochMilli())
                .subtract(java.math.BigInteger.valueOf(startsAt.toEpochMilli()));
        if (total.signum() <= 0) throw new IllegalArgumentException("Buff 有效期无效");
        java.math.BigInteger remaining = java.math.BigInteger.valueOf(expiresAt.toEpochMilli())
                .subtract(java.math.BigInteger.valueOf(now.toEpochMilli()))
                .max(java.math.BigInteger.ZERO).min(total);
        return java.math.BigInteger.valueOf(paidMinor).multiply(remaining).divide(total).longValueExact();
    }

    public static MoneyAmount weeklyPrice(BuffDefinition definition, int weeks, int level,
                                          int scale) {
        if (weeks < 1 || weeks > 4) {
            throw new IllegalArgumentException("Buff 购买周数必须在 1~4 之间");
        }
        if (level < 1 || level > Math.min(5, definition.maximumLevel())) {
            throw new IllegalArgumentException("Buff 强度必须在 1~"
                    + Math.min(5, definition.maximumLevel()) + " 之间");
        }
        BigDecimal value = definition.basePrice()
                .multiply(BigDecimal.valueOf(weeks))
                .multiply(BigDecimal.valueOf(10_000 - (weeks - 1) * 500, 4))
                .multiply(BigDecimal.valueOf(level));
        return MoneyAmount.rounded(value, scale, RoundingMode.CEILING);
    }

}

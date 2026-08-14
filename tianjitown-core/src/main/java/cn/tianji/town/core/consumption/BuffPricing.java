package cn.tianji.town.core.consumption;

import cn.tianji.town.core.economy.MoneyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class BuffPricing {
    private BuffPricing() {
    }

    public static MoneyAmount price(BuffDefinition definition, BuffDurationOption duration,
                                    int nextLevel, int scale) {
        if (duration == null) {
            throw new IllegalArgumentException("Buff 时长不能为空");
        }
        if (nextLevel < 1) {
            throw new IllegalArgumentException("购买后的 Buff 等级必须大于 0");
        }
        BigDecimal value = definition.basePrice()
                .multiply(BigDecimal.valueOf(duration.hours()))
                .multiply(BigDecimal.valueOf(duration.discountBasisPoints(), 4))
                .multiply(BigDecimal.valueOf(nextLevel));
        return MoneyAmount.rounded(value, scale, RoundingMode.CEILING);
    }

    public static NextStack next(BuffDefinition definition, int currentLevel,
                                 int currentStacks) {
        if (currentLevel < 0 || currentStacks < 0) {
            throw new IllegalArgumentException("当前 Buff 等级和层数不能小于 0");
        }
        return switch (definition.stackingRule()) {
            case LEVEL_UP -> {
                int level = Math.addExact(currentLevel, 1);
                if (level > definition.maximumLevel()) {
                    throw new IllegalArgumentException("Buff 已达到最大等级");
                }
                yield new NextStack(level, level);
            }
            case EXTEND -> {
                int stacks = Math.addExact(currentStacks, 1);
                if (stacks > definition.maximumLevel()) {
                    throw new IllegalArgumentException("Buff 已达到最大叠加层数");
                }
                yield new NextStack(Math.max(1, currentLevel), stacks);
            }
            case REFRESH -> new NextStack(1, 1);
        };
    }

    public record NextStack(int level, int stacks) {
    }
}

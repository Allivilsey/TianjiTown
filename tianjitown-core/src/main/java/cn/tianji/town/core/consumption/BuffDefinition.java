package cn.tianji.town.core.consumption;

import cn.tianji.town.core.town.MemberRole;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

public record BuffDefinition(String key, String displayName, EffectKind effectKind,
                             String effectKey, String effectOperation, BigDecimal basePrice,
                             BigDecimal priceMultiplier, Duration duration,
                             int maximumLevel, BuffStackingRule stackingRule,
                             double amountPerLevel, Set<String> allowedWorlds,
                             Set<MemberRole> purchasingRoles) {
    public BuffDefinition {
        key = requireKey(key);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Buff 显示名称不能为空");
        }
        if (effectKind == null || stackingRule == null) {
            throw new IllegalArgumentException("Buff 类型和叠加规则不能为空");
        }
        if (effectKey == null || effectKey.isBlank()) {
            throw new IllegalArgumentException("Buff 效果键不能为空");
        }
        if (effectOperation == null || effectOperation.isBlank()) {
            throw new IllegalArgumentException("Buff 效果运算不能为空");
        }
        if (basePrice == null || basePrice.signum() <= 0) {
            throw new IllegalArgumentException("Buff 基础价格必须大于 0");
        }
        if (priceMultiplier == null || priceMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Buff 价格倍率不能小于 1");
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Buff 持续时间必须大于 0");
        }
        if (maximumLevel < 1 || maximumLevel > 255) {
            throw new IllegalArgumentException("Buff 最大等级必须在 1~255 之间");
        }
        if (!Double.isFinite(amountPerLevel) || amountPerLevel == 0) {
            throw new IllegalArgumentException("Buff 每级效果值必须为非零有限数");
        }
        allowedWorlds = allowedWorlds == null ? Set.of() : allowedWorlds.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(
                        java.util.stream.Collectors.toUnmodifiableSet());
        purchasingRoles = purchasingRoles == null ? Set.of() : Set.copyOf(purchasingRoles);
        if (purchasingRoles.isEmpty()) {
            throw new IllegalArgumentException("Buff 至少需要允许一个购买角色");
        }
    }

    public boolean allowsWorld(String worldName) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public boolean allowsRole(MemberRole role) {
        return purchasingRoles.contains(role);
    }

    private static String requireKey(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Buff key 必须为 1~64 位小写字母、数字、下划线或连字符");
        }
        return value;
    }

    public enum EffectKind {
        POTION,
        ATTRIBUTE
    }
}

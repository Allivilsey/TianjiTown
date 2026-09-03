package org.allivlisey.tianjitown.core.consumption;

import org.allivlisey.tianjitown.core.town.MemberRole;

import java.math.BigDecimal;

public record BuffDefinition(String key, String displayName, EffectKind effectKind,
                             String effectKey, String effectOperation, BigDecimal basePrice,
                             int maximumLevel, BuffStackingRule stackingRule,
                             double amountPerLevel) {
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
        if (maximumLevel < 1 || maximumLevel > 255) {
            throw new IllegalArgumentException("Buff 最大等级必须在 1~255 之间");
        }
        if (!Double.isFinite(amountPerLevel) || amountPerLevel <= 0) {
            throw new IllegalArgumentException("Buff 每级效果值必须为正的有限数");
        }
    }

    public boolean allowsRole(MemberRole role) {
        return role != null && role.isLeader();
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

package cn.tianji.town.core.consumption;

import cn.tianji.town.core.town.MemberRole;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record ResourceDefinition(String key, String displayName, String materialKey,
                                 BigDecimal unitPrice, int maximumPerOrder,
                                 int dailyLimit, List<Integer> quantityOptions,
                                 Set<MemberRole> purchasingRoles) {
    public ResourceDefinition {
        if (key == null || !key.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("资源 key 必须为 1~64 位小写字母、数字、下划线或连字符");
        }
        if (displayName == null || displayName.isBlank() || materialKey == null
                || materialKey.isBlank()) {
            throw new IllegalArgumentException("资源显示名称和材料键不能为空");
        }
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("资源单价必须大于 0");
        }
        if (maximumPerOrder < 1 || dailyLimit < maximumPerOrder) {
            throw new IllegalArgumentException("资源每次上限必须大于 0，且每日上限不能小于每次上限");
        }
        quantityOptions = quantityOptions == null ? List.of() : quantityOptions.stream()
                .distinct().sorted().toList();
        if (quantityOptions.isEmpty() || quantityOptions.stream()
                .anyMatch(value -> value < 1 || value > maximumPerOrder)) {
            throw new IllegalArgumentException("资源数量选项必须在每次购买上限内");
        }
        purchasingRoles = purchasingRoles == null ? Set.of() : Set.copyOf(purchasingRoles);
        if (purchasingRoles.isEmpty()) {
            throw new IllegalArgumentException("资源至少需要允许一个购买角色");
        }
    }

    public boolean allowsRole(MemberRole role) {
        return purchasingRoles.contains(role);
    }
}

package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.core.consumption.BuffPricing;
import cn.tianji.town.core.consumption.BuffStackingRule;
import cn.tianji.town.core.town.MemberRole;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

record BuffSettings(boolean buffShopEnabled, Map<String, BuffDefinition> buffs) {
    BuffSettings {
        buffs = Map.copyOf(buffs);
    }

    BuffDefinition requireBuff(String key) {
        BuffDefinition definition = buffs.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("未知 Buff: " + key);
        }
        return definition;
    }

    static BuffSettings load(ConfigurationSection config) {
        return load(config, 2);
    }

    static BuffSettings load(ConfigurationSection config, int moneyScale) {
        Objects.requireNonNull(config, "config");
        Map<String, BuffDefinition> buffs = loadBuffs(
                config.getConfigurationSection("phase4.buffs.catalog"), moneyScale);
        if (buffs.isEmpty()) {
            throw new IllegalArgumentException("phase4.buffs.catalog 至少需要一个 Buff");
        }
        if (buffs.size() > 36) {
            throw new IllegalArgumentException("phase4.buffs.catalog 最多支持 36 个 Buff");
        }
        return new BuffSettings(ConfigurationValues.bool(config,
                "phase4.buffs.shop-enabled", true), buffs);
    }

    private static Map<String, BuffDefinition> loadBuffs(ConfigurationSection catalog,
                                                         int moneyScale) {
        if (catalog == null) {
            return Map.of();
        }
        Map<String, BuffDefinition> result = new LinkedHashMap<>();
        for (String key : catalog.getKeys(false)) {
            ConfigurationSection section = requireSection(catalog, key);
            BuffDefinition definition = new BuffDefinition(key,
                    text(section, "display-name"),
                    enumValue(BuffDefinition.EffectKind.class,
                            text(section, "effect-kind"), key + ".effect-kind"),
                    text(section, "effect-key"),
                    ConfigurationValues.text(section, "operation", "AMPLIFIER"),
                    ConfigurationValues.decimalText(section, "base-price"),
                    ConfigurationValues.integer(section, "maximum-level"),
                    enumValue(BuffStackingRule.class, text(section, "stacking"),
                    key + ".stacking"),
                    ConfigurationValues.decimalNumber(section, "amount-per-level"),
                    roles(section, "purchasing-roles"));
            validatePriceRange(section, definition, moneyScale);
            if (result.putIfAbsent(key, definition) != null) {
                throw new IllegalArgumentException("重复 Buff key: " + key);
            }
        }
        return result;
    }

    private static Set<MemberRole> roles(ConfigurationSection section, String path) {
        List<String> values = ConfigurationValues.stringList(section, path);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(section.getCurrentPath() + "." + path
                    + " 至少需要一个角色");
        }
        return values.stream().map(value -> enumValue(MemberRole.class, value, path))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ConfigurationSection requireSection(ConfigurationSection parent, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            throw new IllegalArgumentException(parent.getCurrentPath() + "." + key
                    + " 必须为配置节");
        }
        return section;
    }

    private static String text(ConfigurationSection section, String path) {
        String value = ConfigurationValues.text(section, path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(section.getCurrentPath() + "." + path + " 不能为空");
        }
        return value.strip();
    }

    private static void validatePriceRange(ConfigurationSection section,
                                           BuffDefinition definition, int moneyScale) {
        int maximumPricedLevel = definition.stackingRule() == BuffStackingRule.LEVEL_UP
                ? definition.maximumLevel() : 1;
        try {
            for (BuffDurationOption duration : BuffDurationOption.values()) {
                BigDecimal price = definition.basePrice()
                        .multiply(BigDecimal.valueOf(duration.hours()))
                        .multiply(BigDecimal.valueOf(duration.discountBasisPoints(), 4))
                        .multiply(BigDecimal.valueOf(maximumPricedLevel));
                if (price.compareTo(BigDecimal.valueOf(Long.MAX_VALUE, moneyScale)) > 0) {
                    throw new ArithmeticException("金额超过 long 次级单位上限");
                }
                BuffPricing.price(definition, duration, maximumPricedLevel, moneyScale);
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(section.getCurrentPath()
                    + ".base-price 产生的价格超出次级货币单位范围", exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String path) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(path + " 的值不受支持: " + value, exception);
        }
    }
}

package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffStackingRule;
import cn.tianji.town.core.consumption.ResourceDefinition;
import cn.tianji.town.core.town.MemberRole;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

record PhaseFourSettings(boolean buffShopEnabled, boolean resourceShopEnabled,
                         Map<String, BuffDefinition> buffs,
                         Map<String, ResourceDefinition> resources) {
    PhaseFourSettings {
        buffs = Map.copyOf(buffs);
        resources = Map.copyOf(resources);
    }

    BuffDefinition requireBuff(String key) {
        BuffDefinition definition = buffs.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("未知 Buff: " + key);
        }
        return definition;
    }

    ResourceDefinition requireResource(String key) {
        ResourceDefinition definition = resources.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("未知资源商品: " + key);
        }
        return definition;
    }

    static PhaseFourSettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        Map<String, BuffDefinition> buffs = loadBuffs(config.getConfigurationSection(
                "phase4.buffs.catalog"));
        Map<String, ResourceDefinition> resources = loadResources(config.getConfigurationSection(
                "phase4.resources.catalog"));
        if (buffs.isEmpty()) {
            throw new IllegalArgumentException("phase4.buffs.catalog 至少需要一个 Buff");
        }
        if (buffs.size() > 36) {
            throw new IllegalArgumentException("phase4.buffs.catalog 最多支持 36 个 Buff");
        }
        if (resources.isEmpty()) {
            throw new IllegalArgumentException("phase4.resources.catalog 至少需要一个商品");
        }
        if (resources.size() > 9) {
            throw new IllegalArgumentException("phase4.resources.catalog 最多支持 9 个商品");
        }
        return new PhaseFourSettings(config.getBoolean("phase4.buffs.shop-enabled", true),
                config.getBoolean("phase4.resources.shop-enabled", true), buffs, resources);
    }

    private static Map<String, BuffDefinition> loadBuffs(ConfigurationSection catalog) {
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
                    section.getString("operation", "AMPLIFIER"),
                    decimal(section, "base-price"),
                    decimal(section, "price-multiplier"),
                    Duration.ofMinutes(section.getLong("duration-minutes")),
                    section.getInt("maximum-level"),
                    enumValue(BuffStackingRule.class, text(section, "stacking"),
                            key + ".stacking"),
                    section.getDouble("amount-per-level"),
                    Set.copyOf(section.getStringList("allowed-worlds")),
                    roles(section, "purchasing-roles"));
            if (result.putIfAbsent(key, definition) != null) {
                throw new IllegalArgumentException("重复 Buff key: " + key);
            }
        }
        return result;
    }

    private static Map<String, ResourceDefinition> loadResources(ConfigurationSection catalog) {
        if (catalog == null) {
            return Map.of();
        }
        Map<String, ResourceDefinition> result = new LinkedHashMap<>();
        for (String key : catalog.getKeys(false)) {
            ConfigurationSection section = requireSection(catalog, key);
            String materialKey = text(section, "material");
            Material material = Material.matchMaterial(materialKey);
            if (material == null) {
                throw new IllegalArgumentException("资源 " + key + " 的 material 不是有效物品: "
                        + materialKey);
            }
            ResourceDefinition definition = new ResourceDefinition(key,
                    text(section, "display-name"), material.getKey().toString(),
                    decimal(section, "unit-price"), section.getInt("maximum-per-order"),
                    section.getInt("daily-limit"), section.getIntegerList("quantity-options"),
                    roles(section, "purchasing-roles"));
            if (definition.quantityOptions().size() > 6) {
                throw new IllegalArgumentException("资源 " + key + " 最多支持 6 个数量选项");
            }
            result.put(key, definition);
        }
        return result;
    }

    private static Set<MemberRole> roles(ConfigurationSection section, String path) {
        List<String> values = section.getStringList(path);
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
        String value = section.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(section.getCurrentPath() + "." + path + " 不能为空");
        }
        return value.strip();
    }

    private static BigDecimal decimal(ConfigurationSection section, String path) {
        String value = section.getString(path);
        try {
            return new BigDecimal(value);
        } catch (NullPointerException | NumberFormatException exception) {
            throw new IllegalArgumentException(section.getCurrentPath() + "." + path
                    + " 必须为十进制数", exception);
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

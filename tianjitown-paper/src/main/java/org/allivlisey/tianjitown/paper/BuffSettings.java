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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

record BuffSettings(boolean buffShopEnabled, Map<String, BuffDefinition> buffs,
                    BiFunction<String, Map<String, ?>, String> messageResolver,
                    Predicate<String> messagePresent) {
    private static final int MAXIMUM_BUFF_COUNT = 36;
    private static final String UNKNOWN_BUFF = "validation.buff.unknown";
    private static final String LABEL_REQUIRED = "validation.buff.label-required";
    private static final String LABEL_PREFIX = "dialog.buff.labels.";
    private static final String CATALOG_REQUIRED = "validation.buff.catalog-required";
    private static final String CATALOG_LIMIT = "validation.buff.catalog-limit";
    private static final String DUPLICATE_KEY = "validation.buff.duplicate-key";
    private static final String PURCHASING_ROLES_REQUIRED =
            "validation.buff.purchasing-roles-required";
    private static final String SECTION_REQUIRED = "validation.buff.section-required";
    private static final String VALUE_REQUIRED = "validation.buff.value-required";
    private static final String PRICE_OVERFLOW = "validation.buff.price-overflow";
    private static final String PRICE_RANGE = "validation.buff.price-range";
    private static final String ENUM_UNSUPPORTED = "validation.buff.enum-unsupported";

    BuffSettings {
        buffs = Map.copyOf(buffs);
        messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
        messagePresent = Objects.requireNonNull(messagePresent, "messagePresent");
    }

    BuffSettings(boolean buffShopEnabled, Map<String, BuffDefinition> buffs) {
        this(buffShopEnabled, buffs, BuffSettings::fallbackMessage, key -> true);
    }

    BuffSettings(boolean buffShopEnabled, Map<String, BuffDefinition> buffs,
                 BiFunction<String, Map<String, ?>, String> messageResolver) {
        this(buffShopEnabled, buffs, messageResolver, key -> true);
    }

    BuffDefinition requireBuff(String key) {
        BuffDefinition definition = buffs.get(key);
        if (definition == null) {
            throw new IllegalArgumentException(resolveMessage(UNKNOWN_BUFF,
                    Map.of("key", safeText(key))));
        }
        return definition;
    }

    static BuffSettings load(ConfigurationSection config) {
        return load(config, 2, BuffSettings::fallbackMessage);
    }

    static BuffSettings load(ConfigurationSection config, int moneyScale) {
        return load(config, moneyScale, BuffSettings::fallbackMessage);
    }

    static BuffSettings load(ConfigurationSection config,
                             BiFunction<String, Map<String, ?>, String> messageResolver) {
        return load(config, 2, messageResolver);
    }

    static BuffSettings load(ConfigurationSection config, int moneyScale,
                             BiFunction<String, Map<String, ?>, String> messageResolver) {
        return load(config, moneyScale, messageResolver, key -> {
            try {
                String value = messageResolver.apply(key, Map.of());
                if (value != null && !value.isBlank()) {
                    return value;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Keep the resolver-only overload usable for tests and bootstrap fallbacks.
            }
            return key;
        });
    }

    static BuffSettings load(ConfigurationSection config, int moneyScale,
                             PluginMessages messages) {
        Objects.requireNonNull(messages, "messages");
        return load(config, moneyScale, messages::plainText, messages::requiredPlainText,
                messages::hasMessage);
    }

    static BuffSettings load(ConfigurationSection config, int moneyScale,
                             BiFunction<String, Map<String, ?>, String> messageResolver,
                             Function<String, String> requiredMessageResolver) {
        return load(config, moneyScale, messageResolver, requiredMessageResolver, key -> true);
    }

    private static BuffSettings load(ConfigurationSection config, int moneyScale,
                                     BiFunction<String, Map<String, ?>, String> messageResolver,
                                     Function<String, String> requiredMessageResolver,
                                     Predicate<String> messagePresent) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messageResolver, "messageResolver");
        Objects.requireNonNull(requiredMessageResolver, "requiredMessageResolver");
        Objects.requireNonNull(messagePresent, "messagePresent");
        Map<String, BuffDefinition> buffs = loadBuffs(
                config.getConfigurationSection("buffs.catalog"), moneyScale, messageResolver,
                requiredMessageResolver);
        if (buffs.isEmpty()) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, CATALOG_REQUIRED,
                    Map.of()));
        }
        if (buffs.size() > MAXIMUM_BUFF_COUNT) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, CATALOG_LIMIT,
                    Map.of("maximum", MAXIMUM_BUFF_COUNT)));
        }
        return new BuffSettings(ConfigurationValues.bool(config,
                "buffs.shop-enabled", true, messageResolver), buffs, messageResolver,
                messagePresent);
    }

    private static Map<String, BuffDefinition> loadBuffs(ConfigurationSection catalog,
                                                         int moneyScale,
                                                         BiFunction<String, Map<String, ?>, String>
                                                                 messageResolver,
                                                         Function<String, String>
                                                                 requiredMessageResolver) {
        if (catalog == null) {
            return Map.of();
        }
        Map<String, BuffDefinition> result = new LinkedHashMap<>();
        for (String key : catalog.getKeys(false)) {
            ConfigurationSection section = requireSection(catalog, key, messageResolver);
            BuffDefinition definition = new BuffDefinition(key,
                    requiredLabel(key, messageResolver, requiredMessageResolver),
                    enumValue(BuffDefinition.EffectKind.class,
                            text(section, "effect-kind", messageResolver),
                            section.getCurrentPath() + ".effect-kind", messageResolver),
                    text(section, "effect-key", messageResolver),
                    ConfigurationValues.text(section, "operation", "AMPLIFIER", messageResolver),
                    ConfigurationValues.decimalText(section, "base-price", messageResolver),
                    ConfigurationValues.integer(section, "maximum-level", messageResolver),
                    enumValue(BuffStackingRule.class, text(section, "stacking", messageResolver),
                    section.getCurrentPath() + ".stacking", messageResolver),
                    ConfigurationValues.decimalNumber(section, "amount-per-level", messageResolver),
                    roles(section, "purchasing-roles", messageResolver));
            validatePriceRange(section, definition, moneyScale, messageResolver);
            if (result.putIfAbsent(key, definition) != null) {
                throw new IllegalArgumentException(resolveMessage(messageResolver, DUPLICATE_KEY,
                        Map.of("key", safeText(key))));
            }
        }
        return result;
    }

    private static String requiredLabel(String buffKey,
                                        BiFunction<String, Map<String, ?>, String> messageResolver,
                                        Function<String, String> requiredMessageResolver) {
        try {
            String value = requiredMessageResolver.apply(labelMessageKey(buffKey));
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Convert resolver-specific failures into the normal configuration diagnostic below.
        }
        throw new IllegalArgumentException(resolveMessage(messageResolver, LABEL_REQUIRED,
                Map.of("key", safeText(buffKey))));
    }

    private static Set<MemberRole> roles(ConfigurationSection section, String path,
                                        BiFunction<String, Map<String, ?>, String>
                                                messageResolver) {
        List<String> values = ConfigurationValues.stringList(section, path, messageResolver);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    PURCHASING_ROLES_REQUIRED, Map.of("path",
                            safeText(section.getCurrentPath() + "." + path))));
        }
        return values.stream().map(value -> enumValue(MemberRole.class, value,
                        section.getCurrentPath() + "." + path, messageResolver))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ConfigurationSection requireSection(ConfigurationSection parent, String key,
                                                       BiFunction<String, Map<String, ?>, String>
                                                               messageResolver) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, SECTION_REQUIRED,
                    Map.of("path", safeText(parent.getCurrentPath() + "." + key))));
        }
        return section;
    }

    private static String text(ConfigurationSection section, String path,
                               BiFunction<String, Map<String, ?>, String> messageResolver) {
        String value = ConfigurationValues.text(section, path, messageResolver);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, VALUE_REQUIRED,
                    Map.of("path", safeText(section.getCurrentPath() + "." + path))));
        }
        return value.strip();
    }

    private static void validatePriceRange(ConfigurationSection section,
                                           BuffDefinition definition, int moneyScale,
                                           BiFunction<String, Map<String, ?>, String>
                                                   messageResolver) {
        int maximumPricedLevel = definition.stackingRule() == BuffStackingRule.LEVEL_UP
                ? definition.maximumLevel() : 1;
        try {
            for (BuffDurationOption duration : BuffDurationOption.values()) {
                BigDecimal price = definition.basePrice()
                        .multiply(BigDecimal.valueOf(duration.hours()))
                        .multiply(BigDecimal.valueOf(duration.discountBasisPoints(), 4))
                        .multiply(BigDecimal.valueOf(maximumPricedLevel));
                if (price.compareTo(BigDecimal.valueOf(Long.MAX_VALUE, moneyScale)) > 0) {
                    throw new ArithmeticException(resolveMessage(messageResolver, PRICE_OVERFLOW,
                            Map.of()));
                }
                BuffPricing.price(definition, duration, maximumPricedLevel, moneyScale);
            }
            BuffPricing.weeklyPrice(definition, 4,
                    Math.min(5, definition.maximumLevel()), moneyScale);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, PRICE_RANGE,
                    Map.of("path", safeText(section.getCurrentPath() + ".base-price"))),
                    exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String path,
                                                   BiFunction<String, Map<String, ?>, String>
                                                           messageResolver) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, ENUM_UNSUPPORTED,
                    Map.of("path", safeText(path), "value", safeText(value))), exception);
        }
    }

    private String resolveMessage(String key, Map<String, ?> placeholders) {
        return resolveMessage(messageResolver, key, placeholders);
    }

    private static String resolveMessage(BiFunction<String, Map<String, ?>, String> resolver,
                                         String key, Map<String, ?> placeholders) {
        try {
            String resolved = resolver.apply(key, placeholders);
            return resolved == null || resolved.isBlank() ? key : resolved;
        } catch (RuntimeException | LinkageError exception) {
            return key;
        }
    }

    String label(String key) {
        BuffDefinition definition = requireBuff(key);
        String messageKey = labelMessageKey(definition.key());
        try {
            if (messagePresent.test(messageKey)) {
                String value = messageResolver.apply(messageKey, Map.of());
                if (value != null && !value.isBlank()) {
                    return value.strip();
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Keep the last validated label available if a later reload is malformed.
        }
        return definition.displayName();
    }

    static String labelMessageKey(String buffKey) {
        return LABEL_PREFIX + buffKey;
    }

    static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }
}

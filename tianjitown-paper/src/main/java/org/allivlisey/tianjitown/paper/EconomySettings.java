package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

record EconomySettings(boolean taxEnabled, boolean consumptionEnabled, String settlementAccount,
                          int fallbackScale, int maximumTaxBps, BigDecimal expansionCost,
                          int maximumUnits, BigDecimal weeklySubsidyLimit,
                          BigDecimal twelveHourSubsidyLimit) {
    private static final int MINIMUM_MONEY_SCALE = 0;
    private static final int MAXIMUM_MONEY_SCALE = 8;
    private static final String SETTLEMENT_ACCOUNT_REQUIRED =
            "validation.common.value-required";
    private static final String MONEY_SCALE_RANGE = "validation.economy.money-scale-range";
    private static final String EXPANSION_COST_POSITIVE =
            "validation.economy.expansion-cost-positive";
    private static final String WEEKLY_SUBSIDY_LIMIT_NEGATIVE =
            "validation.common.non-negative";
    private static final String TWELVE_HOUR_SUBSIDY_LIMIT_NEGATIVE =
            "validation.common.non-negative";
    private static final String TWELVE_HOUR_LIMIT_EXCEEDS_WEEKLY =
            "validation.economy.twelve-hour-limit-exceeds-weekly";
    private static final String EXPANSION_COST_OVERFLOW =
            "validation.economy.expansion-cost-overflow";
    private static final String EXPANSION_COST_RANGE =
            "validation.economy.expansion-cost-range";

    boolean allowsTaxRate(int basisPoints) {
        return basisPoints >= 500 && basisPoints <= maximumTaxBps
                && basisPoints % 100 == 0;
    }

    long weeklySubsidyLimitMinor(int scale) {
        return MoneyAmount.rounded(weeklySubsidyLimit, scale, RoundingMode.FLOOR).minorUnits();
    }

    long twelveHourSubsidyLimitMinor(int scale) {
        return MoneyAmount.rounded(twelveHourSubsidyLimit, scale, RoundingMode.FLOOR).minorUnits();
    }

    static EconomySettings load(ConfigurationSection config) {
        return load(config, ConfigurationValues::fallbackMessage);
    }

    static EconomySettings load(ConfigurationSection config,
                                BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messageResolver, "messageResolver");
        String account = ConfigurationValues.text(config, "economy.settlement-account", "tax",
                messageResolver);
        int scale = ConfigurationValues.integer(config, "economy.money-scale", 2,
                messageResolver);
        int maximumTaxBps = 2500;
        BigDecimal expansionCost = ConfigurationValues.decimalText(config,
                "economy.expansion.fixed-cost", "3000.00", messageResolver);
        BigDecimal weeklySubsidyLimit = ConfigurationValues.decimalText(config,
                "economy.tax.subsidy.weekly-limit", "50000.00", messageResolver);
        BigDecimal twelveHourSubsidyLimit = ConfigurationValues.decimalText(config,
                "economy.tax.subsidy.twelve-hour-limit", "5000.00", messageResolver);
        int maximumUnits = org.allivlisey.tianjitown.core.land.TerritoryRules.MAXIMUM_UNITS;
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    SETTLEMENT_ACCOUNT_REQUIRED,
                    Map.of("path", "economy.settlement-account")));
        }
        if (scale < MINIMUM_MONEY_SCALE || scale > MAXIMUM_MONEY_SCALE) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, MONEY_SCALE_RANGE,
                    Map.of("path", "economy.money-scale", "minimum", MINIMUM_MONEY_SCALE,
                            "maximum", MAXIMUM_MONEY_SCALE)));
        }
        if (expansionCost.signum() <= 0) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    EXPANSION_COST_POSITIVE,
                    Map.of("path", "economy.expansion.fixed-cost")));
        }
        if (weeklySubsidyLimit.signum() < 0) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    WEEKLY_SUBSIDY_LIMIT_NEGATIVE,
                    Map.of("path", "economy.tax.subsidy.weekly-limit")));
        }
        if (twelveHourSubsidyLimit.signum() < 0) {
            throw new IllegalArgumentException(
                    resolveMessage(messageResolver, TWELVE_HOUR_SUBSIDY_LIMIT_NEGATIVE,
                            Map.of("path", "economy.tax.subsidy.twelve-hour-limit")));
        }
        if (twelveHourSubsidyLimit.compareTo(weeklySubsidyLimit) > 0) {
            throw new IllegalArgumentException(
                    resolveMessage(messageResolver, TWELVE_HOUR_LIMIT_EXCEEDS_WEEKLY,
                            Map.of("path", "economy.tax.subsidy.twelve-hour-limit")));
        }
        try {
            if (expansionCost.compareTo(BigDecimal.valueOf(Long.MAX_VALUE, scale)) > 0) {
                throw new ArithmeticException(resolveMessage(messageResolver,
                        EXPANSION_COST_OVERFLOW, Map.of()));
            }
            MoneyAmount.rounded(expansionCost, scale, RoundingMode.CEILING);
            MoneyAmount.rounded(weeklySubsidyLimit, scale, RoundingMode.FLOOR);
            MoneyAmount.rounded(twelveHourSubsidyLimit, scale, RoundingMode.FLOOR);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    EXPANSION_COST_RANGE, Map.of("path", "economy.expansion")), exception);
        }
        return new EconomySettings(ConfigurationValues.bool(config, "economy.tax.enabled", true,
                        messageResolver),
                ConfigurationValues.bool(config, "economy.consumption.enabled", true,
                        messageResolver),
                account.strip(), scale, maximumTaxBps, expansionCost, maximumUnits,
                weeklySubsidyLimit, twelveHourSubsidyLimit);
    }

    private static String resolveMessage(BiFunction<String, Map<String, ?>, String> messageResolver,
                                         String key, Map<String, ?> placeholders) {
        try {
            String resolved = messageResolver.apply(key, placeholders);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Configuration parsing must still expose a stable diagnostic if messages fail.
        }
        return ConfigurationValues.fallbackMessage(key, placeholders);
    }
}

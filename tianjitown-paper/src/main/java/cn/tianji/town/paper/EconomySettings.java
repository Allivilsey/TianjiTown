package cn.tianji.town.paper;

import cn.tianji.town.core.economy.MoneyAmount;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

record EconomySettings(boolean taxEnabled, boolean consumptionEnabled, String settlementAccount,
                          int fallbackScale, int maximumTaxBps, BigDecimal expansionCost,
                          int maximumUnits, BigDecimal weeklySubsidyLimit,
                          BigDecimal twelveHourSubsidyLimit) {
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
        Objects.requireNonNull(config, "config");
        String account = ConfigurationValues.text(config, "economy.settlement-account", "tax");
        int scale = ConfigurationValues.integer(config, "economy.money-scale", 2);
        int maximumTaxBps = 2500;
        BigDecimal expansionCost = ConfigurationValues.decimalText(config,
                "economy.expansion.fixed-cost", "3000.00");
        BigDecimal weeklySubsidyLimit = ConfigurationValues.decimalText(config,
                "economy.tax.subsidy.weekly-limit", "50000.00");
        BigDecimal twelveHourSubsidyLimit = ConfigurationValues.decimalText(config,
                "economy.tax.subsidy.twelve-hour-limit", "5000.00");
        int maximumUnits = cn.tianji.town.core.land.TerritoryRules.MAXIMUM_UNITS;
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("economy.settlement-account 不能为空");
        }
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("economy.money-scale 必须在 0~8 之间");
        }
        if (expansionCost.signum() <= 0) {
            throw new IllegalArgumentException("economy.expansion.fixed-cost 必须大于 0");
        }
        if (weeklySubsidyLimit.signum() < 0) {
            throw new IllegalArgumentException("economy.tax.subsidy.weekly-limit 不能小于 0");
        }
        if (twelveHourSubsidyLimit.signum() < 0) {
            throw new IllegalArgumentException(
                    "economy.tax.subsidy.twelve-hour-limit 不能小于 0");
        }
        if (twelveHourSubsidyLimit.compareTo(weeklySubsidyLimit) > 0) {
            throw new IllegalArgumentException(
                    "economy.tax.subsidy.twelve-hour-limit 不能大于每周限额");
        }
        try {
            if (expansionCost.compareTo(BigDecimal.valueOf(Long.MAX_VALUE, scale)) > 0) {
                throw new ArithmeticException("金额超过 long 次级单位上限");
            }
            MoneyAmount.rounded(expansionCost, scale, RoundingMode.CEILING);
            MoneyAmount.rounded(weeklySubsidyLimit, scale, RoundingMode.FLOOR);
            MoneyAmount.rounded(twelveHourSubsidyLimit, scale, RoundingMode.FLOOR);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("economy.expansion 价格超出次级货币单位范围",
                    exception);
        }
        return new EconomySettings(ConfigurationValues.bool(config, "economy.tax.enabled", true),
                ConfigurationValues.bool(config, "economy.consumption.enabled", true),
                account.strip(), scale, maximumTaxBps, expansionCost, maximumUnits,
                weeklySubsidyLimit, twelveHourSubsidyLimit);
    }
}

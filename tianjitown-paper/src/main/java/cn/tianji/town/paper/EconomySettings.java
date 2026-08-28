package cn.tianji.town.paper;

import cn.tianji.town.core.economy.MoneyAmount;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

record EconomySettings(boolean taxEnabled, boolean consumptionEnabled, String settlementAccount,
                          int fallbackScale, int maximumTaxBps, BigDecimal expansionCost,
                          int maximumUnits) {
    boolean allowsTaxRate(int basisPoints) {
        return basisPoints >= 500 && basisPoints <= maximumTaxBps
                && basisPoints % 100 == 0;
    }

    static EconomySettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        String account = ConfigurationValues.text(config, "economy.settlement-account", "tax");
        int scale = ConfigurationValues.integer(config, "economy.money-scale", 2);
        int maximumTaxBps = 2500;
        BigDecimal expansionCost = ConfigurationValues.decimalText(config,
                "economy.expansion.fixed-cost", "3000.00");
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
        try {
            if (expansionCost.compareTo(BigDecimal.valueOf(Long.MAX_VALUE, scale)) > 0) {
                throw new ArithmeticException("金额超过 long 次级单位上限");
            }
            MoneyAmount.rounded(expansionCost, scale, RoundingMode.CEILING);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("economy.expansion 价格超出次级货币单位范围",
                    exception);
        }
        return new EconomySettings(ConfigurationValues.bool(config, "economy.tax.enabled", true),
                ConfigurationValues.bool(config, "economy.consumption.enabled", true),
                account.strip(), scale, maximumTaxBps, expansionCost, maximumUnits);
    }
}

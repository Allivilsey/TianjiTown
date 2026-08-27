package cn.tianji.town.paper;

import cn.tianji.town.core.economy.MoneyAmount;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

record EconomySettings(boolean taxEnabled, boolean consumptionEnabled, String settlementAccount,
                          int fallbackScale, int maximumTaxBps, BigDecimal expansionBaseCost,
                          BigDecimal expansionPerUnitIncrease, int maximumUnits) {
    boolean allowsTaxRate(int basisPoints) {
        return basisPoints >= 500 && basisPoints <= maximumTaxBps
                && basisPoints % 100 == 0;
    }

    static EconomySettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        String account = ConfigurationValues.text(config, "phase3.settlement-account", "tax");
        int scale = ConfigurationValues.integer(config, "phase3.money-scale", 2);
        int maximumTaxBps = 2500;
        BigDecimal base = ConfigurationValues.decimalText(config,
                "phase3.expansion.base-cost", "1000.00");
        BigDecimal increase = ConfigurationValues.decimalText(config,
                "phase3.expansion.per-unit-increase", "500.00");
        int maximumUnits = cn.tianji.town.core.land.TerritoryRules.MAXIMUM_UNITS;
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("phase3.settlement-account 不能为空");
        }
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("phase3.money-scale 必须在 0~8 之间");
        }
        if (base.signum() <= 0) {
            throw new IllegalArgumentException("phase3.expansion.base-cost 必须大于 0");
        }
        if (increase.signum() < 0) {
            throw new IllegalArgumentException("phase3.expansion.per-unit-increase 不能小于 0");
        }
        try {
            BigDecimal maximumPrice = base.add(increase.multiply(
                    BigDecimal.valueOf(maximumUnits - 1L)));
            if (maximumPrice.compareTo(BigDecimal.valueOf(Long.MAX_VALUE, scale)) > 0) {
                throw new ArithmeticException("金额超过 long 次级单位上限");
            }
            MoneyAmount.rounded(maximumPrice, scale, RoundingMode.CEILING);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("phase3.expansion 价格超出次级货币单位范围",
                    exception);
        }
        return new EconomySettings(ConfigurationValues.bool(config, "phase3.tax.enabled", true),
                ConfigurationValues.bool(config, "phase3.consumption.enabled", true),
                account.strip(), scale, maximumTaxBps, base, increase, maximumUnits);
    }
}

package cn.tianji.town.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.Objects;

record EconomySettings(boolean taxEnabled, boolean consumptionEnabled, String settlementAccount,
                          int fallbackScale, int maximumTaxBps, BigDecimal expansionBaseCost,
                          BigDecimal expansionPerUnitIncrease, int maximumUnits) {
    boolean allowsTaxRate(int basisPoints) {
        return basisPoints >= 500 && basisPoints <= maximumTaxBps
                && basisPoints % 500 == 0;
    }

    static EconomySettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        String account = config.getString("phase3.settlement-account", "tax");
        int scale = config.getInt("phase3.money-scale", 2);
        int maximumTaxBps = 2500;
        BigDecimal base = decimal(config, "phase3.expansion.base-cost", "1000.00");
        BigDecimal increase = decimal(config, "phase3.expansion.per-unit-increase", "500.00");
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
        return new EconomySettings(config.getBoolean("phase3.tax.enabled", true),
                config.getBoolean("phase3.consumption.enabled", true), account.strip(), scale,
                maximumTaxBps, base, increase, maximumUnits);
    }

    private static BigDecimal decimal(ConfigurationSection config, String path,
                                      String defaultValue) {
        String value = config.getString(path, defaultValue);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(path + " 必须为十进制数", exception);
        }
    }
}

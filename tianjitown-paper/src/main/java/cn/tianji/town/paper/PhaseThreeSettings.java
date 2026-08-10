package cn.tianji.town.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.Objects;

record PhaseThreeSettings(boolean taxEnabled, boolean consumptionEnabled, String settlementAccount,
                          int fallbackScale, int maximumTaxBps, BigDecimal expansionBaseCost,
                          BigDecimal expansionGrowthFactor, int maximumUnits) {
    boolean allowsTaxRate(int basisPoints) {
        return basisPoints >= 0 && basisPoints <= maximumTaxBps;
    }

    static PhaseThreeSettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        String account = config.getString("phase3.settlement-account", "tax");
        int scale = config.getInt("phase3.money-scale", 2);
        int maximumTaxBps = config.getInt("phase3.tax.maximum-basis-points", 2500);
        BigDecimal base = decimal(config, "phase3.expansion.base-cost", "1000.00");
        BigDecimal growth = decimal(config, "phase3.expansion.growth-factor", "1.50");
        int maximumUnits = config.getInt("phase3.expansion.maximum-units", 9);
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("phase3.settlement-account 不能为空");
        }
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("phase3.money-scale 必须在 0~8 之间");
        }
        if (maximumTaxBps < 0 || maximumTaxBps >= 10_000) {
            throw new IllegalArgumentException(
                    "phase3.tax.maximum-basis-points 必须在 0~9999 之间");
        }
        if (base.signum() <= 0) {
            throw new IllegalArgumentException("phase3.expansion.base-cost 必须大于 0");
        }
        if (growth.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("phase3.expansion.growth-factor 不能小于 1");
        }
        if (maximumUnits < 1 || maximumUnits > 9) {
            throw new IllegalArgumentException("phase3.expansion.maximum-units 必须在 1~9 之间");
        }
        return new PhaseThreeSettings(config.getBoolean("phase3.tax.enabled", true),
                config.getBoolean("phase3.consumption.enabled", true), account.strip(), scale,
                maximumTaxBps, base, growth, maximumUnits);
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

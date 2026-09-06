package org.allivlisey.tianjitown.paper.config;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiFunction;

public final class ApplicationSettings {
    private ApplicationSettings() {
    }

    public static long feeMinor(ConfigurationSection config, int scale,
                                BiFunction<String, Map<String, ?>, String> messageResolver) {
        String path = "town.application.fee";
        BigDecimal fee = ConfigurationValues.decimalText(config, path, "5000.00", messageResolver);
        try {
            long minor = fee.movePointRight(scale).longValueExact();
            if (minor > 0) {
                return minor;
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(messageResolver.apply(
                    "validation.application.fee-invalid", Map.of("path", path)), exception);
        }
        throw new IllegalArgumentException(messageResolver.apply(
                "validation.application.fee-invalid", Map.of("path", path)));
    }
}

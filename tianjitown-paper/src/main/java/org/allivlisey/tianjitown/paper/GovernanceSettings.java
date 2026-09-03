package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

record GovernanceSettings(Duration transferConfirmation, Duration activeMemberWindow,
                        Duration minimumMembership, Duration voteDuration) {
    private static final String TRANSFER_CONFIRMATION =
            "governance.transfer-confirmation-hours";
    private static final String ACTIVE_MEMBER_WINDOW = "governance.voting.active-member-days";
    private static final String MINIMUM_MEMBERSHIP = "governance.voting.minimum-membership-days";
    private static final String VOTE_DURATION = "governance.voting.duration-hours";
    private static final String DURATION_NON_NEGATIVE =
            "validation.governance.duration-non-negative";
    private static final String DURATION_POSITIVE = "validation.governance.duration-positive";

    static GovernanceSettings load(ConfigurationSection config) {
        return load(config, ConfigurationValues::fallbackMessage);
    }

    static GovernanceSettings load(ConfigurationSection config,
                                   BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(messageResolver, "messageResolver");
        return new GovernanceSettings(
                hours(TRANSFER_CONFIRMATION,
                        ConfigurationValues.longInteger(config, TRANSFER_CONFIRMATION, 24,
                                messageResolver), false, messageResolver),
                days(ACTIVE_MEMBER_WINDOW,
                        ConfigurationValues.longInteger(config, ACTIVE_MEMBER_WINDOW, 30,
                                messageResolver), true, messageResolver),
                days(MINIMUM_MEMBERSHIP,
                        ConfigurationValues.longInteger(config, MINIMUM_MEMBERSHIP, 0,
                                messageResolver), true, messageResolver),
                hours(VOTE_DURATION,
                        ConfigurationValues.longInteger(config, VOTE_DURATION, 72,
                                messageResolver), false, messageResolver));
    }

    private static Duration hours(String path, long value, boolean allowZero,
                                  BiFunction<String, Map<String, ?>, String> messageResolver) {
        return duration(path, value, allowZero, Duration::ofHours, messageResolver);
    }

    private static Duration days(String path, long value, boolean allowZero,
                                 BiFunction<String, Map<String, ?>, String> messageResolver) {
        return duration(path, value, allowZero, Duration::ofDays, messageResolver);
    }

    private static Duration duration(String path, long value, boolean allowZero,
                                     LongFunction<Duration> factory,
                                     BiFunction<String, Map<String, ?>, String> messageResolver) {
        if (value < 0 || (!allowZero && value == 0)) {
            throw invalid(path, allowZero, null, messageResolver);
        }
        try {
            Duration duration = factory.apply(value);
            Instant now = Instant.now();
            now.plus(duration).toEpochMilli();
            now.minus(duration).toEpochMilli();
            return duration;
        } catch (ArithmeticException | DateTimeException exception) {
            throw invalid(path, allowZero, exception, messageResolver);
        }
    }

    private static IllegalArgumentException invalid(String path, boolean allowZero,
                                                    RuntimeException cause,
                                                    BiFunction<String, Map<String, ?>, String>
                                                            messageResolver) {
        String key = allowZero ? DURATION_NON_NEGATIVE : DURATION_POSITIVE;
        return new IllegalArgumentException(resolveMessage(messageResolver, key,
                Map.of("path", path)), cause);
    }

    private static String resolveMessage(
            BiFunction<String, Map<String, ?>, String> messageResolver,
            String key, Map<String, ?> placeholders) {
        try {
            String message = messageResolver.apply(key, placeholders);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Configuration parsing must still expose a stable diagnostic if messages fail.
        }
        return ConfigurationValues.fallbackMessage(key, placeholders);
    }
}

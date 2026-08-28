package cn.tianji.town.paper;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongFunction;

record GovernanceSettings(Duration transferConfirmation, Duration activeMemberWindow,
                        Duration minimumMembership, Duration voteDuration) {
    private static final String TRANSFER_CONFIRMATION =
            "governance.transfer-confirmation-hours";
    private static final String ACTIVE_MEMBER_WINDOW = "governance.voting.active-member-days";
    private static final String MINIMUM_MEMBERSHIP = "governance.voting.minimum-membership-days";
    private static final String VOTE_DURATION = "governance.voting.duration-hours";

    static GovernanceSettings load(ConfigurationSection config) {
        Objects.requireNonNull(config, "config");
        return new GovernanceSettings(
                hours(TRANSFER_CONFIRMATION,
                        ConfigurationValues.longInteger(config, TRANSFER_CONFIRMATION, 24), false),
                days(ACTIVE_MEMBER_WINDOW,
                        ConfigurationValues.longInteger(config, ACTIVE_MEMBER_WINDOW, 30), true),
                days(MINIMUM_MEMBERSHIP,
                        ConfigurationValues.longInteger(config, MINIMUM_MEMBERSHIP, 0), true),
                hours(VOTE_DURATION,
                        ConfigurationValues.longInteger(config, VOTE_DURATION, 72), false));
    }

    private static Duration hours(String path, long value, boolean allowZero) {
        return duration(path, value, allowZero, Duration::ofHours);
    }

    private static Duration days(String path, long value, boolean allowZero) {
        return duration(path, value, allowZero, Duration::ofDays);
    }

    private static Duration duration(String path, long value, boolean allowZero,
                                     LongFunction<Duration> factory) {
        if (value < 0 || (!allowZero && value == 0)) {
            throw invalid(path, allowZero, null);
        }
        try {
            Duration duration = factory.apply(value);
            Instant now = Instant.now();
            now.plus(duration).toEpochMilli();
            now.minus(duration).toEpochMilli();
            return duration;
        } catch (ArithmeticException | DateTimeException exception) {
            throw invalid(path, allowZero, exception);
        }
    }

    private static IllegalArgumentException invalid(String path, boolean allowZero,
                                                    RuntimeException cause) {
        String range = allowZero ? "非负整数" : "正整数";
        return new IllegalArgumentException(path + " 必须为可安全计算的" + range, cause);
    }
}

package cn.tianji.town.core.consumption;

import java.time.Duration;
import java.util.Locale;

public enum BuffDurationOption {
    ONE_HOUR("一小时", 1, 10_000),
    ONE_DAY("一天", 24, 9_000),
    ONE_WEEK("一周", 24 * 7, 8_000),
    ONE_MONTH("一月", 24 * 30, 7_000);

    private final String displayName;
    private final int hours;
    private final int discountBasisPoints;

    BuffDurationOption(String displayName, int hours, int discountBasisPoints) {
        this.displayName = displayName;
        this.hours = hours;
        this.discountBasisPoints = discountBasisPoints;
    }

    public String displayName() {
        return displayName;
    }

    public int hours() {
        return hours;
    }

    public int discountBasisPoints() {
        return discountBasisPoints;
    }

    public Duration duration() {
        return Duration.ofHours(hours);
    }

    public static BuffDurationOption parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Buff 时长不能为空");
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Buff 时长必须是一小时、一天、一周或一月", exception);
        }
    }
}

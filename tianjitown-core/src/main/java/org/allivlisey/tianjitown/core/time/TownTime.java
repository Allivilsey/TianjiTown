package org.allivlisey.tianjitown.core.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared display boundary; persisted timestamps remain UTC instants. */
public final class TownTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZONE);
    private TownTime() {}
    public static String display(Object value) {
        return value instanceof Instant instant ? DISPLAY.format(instant) : String.valueOf(value);
    }
}

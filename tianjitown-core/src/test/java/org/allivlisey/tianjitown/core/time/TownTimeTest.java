package org.allivlisey.tianjitown.core.time;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class TownTimeTest {
    @Test void usesShanghaiEvenWhenHostIsUtc() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals("2026-09-05 20:16:55", TownTime.display(Instant.parse("2026-09-05T12:16:55.617Z")));
            assertEquals("2027-01-01 00:00:00", TownTime.display(Instant.parse("2026-12-31T16:00:00Z")));
            assertEquals("尚未提交", TownTime.display("尚未提交"));
        } finally { TimeZone.setDefault(original); }
    }
}

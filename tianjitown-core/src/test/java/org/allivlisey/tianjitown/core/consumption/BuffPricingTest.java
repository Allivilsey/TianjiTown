package org.allivlisey.tianjitown.core.consumption;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuffPricingTest {
    @Test void clampsTimeAndFloorsWithoutOverflow() {
        Instant start = Instant.ofEpochMilli(100), end = Instant.ofEpochMilli(1100);
        assertEquals(333, BuffPricing.remainingRefund(1000, start, end, Instant.ofEpochMilli(767)));
        assertEquals(1000, BuffPricing.remainingRefund(1000, start, end, Instant.EPOCH));
        assertEquals(0, BuffPricing.remainingRefund(1000, start, end, end));
        assertEquals(0, BuffPricing.remainingRefund(1000, start, end, end.plusSeconds(1)));
        assertEquals(0, BuffPricing.remainingRefund(0, start, end, start));
        assertEquals(Long.MAX_VALUE, BuffPricing.remainingRefund(Long.MAX_VALUE, start, end, start));
        assertThrows(IllegalArgumentException.class, () -> BuffPricing.remainingRefund(1, end, start, start));
    }
}

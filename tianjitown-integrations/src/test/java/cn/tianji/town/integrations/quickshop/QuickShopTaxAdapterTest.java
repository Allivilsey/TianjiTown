package cn.tianji.town.integrations.quickshop;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickShopTaxAdapterTest {
    @Test
    void filtersUnrelatedEventsThatShareTheSameHandlerList() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        var executor = QuickShopTaxAdapter.filteredExecutor(TaxEvent.class,
                ignored -> handled.incrementAndGet());

        executor.execute(null, new CalendarEvent());
        executor.execute(null, new TaxEvent());

        assertEquals(1, handled.get());
    }

    @Test
    void requiresQuickShopVersionStrictlyAboveSixThree() {
        assertFalse(QuickShopTaxAdapter.isNewerThanMinimum("6.3.0.0"));
        assertFalse(QuickShopTaxAdapter.isNewerThanMinimum("6.2.0.11"));
        assertFalse(QuickShopTaxAdapter.isNewerThanMinimum("build-7"));
        assertTrue(QuickShopTaxAdapter.isNewerThanMinimum("6.3.0.0-SNAPSHOT-12"));
        assertTrue(QuickShopTaxAdapter.isNewerThanMinimum("6.3.0.1"));
    }

    private abstract static class SharedQuickShopEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static final class TaxEvent extends SharedQuickShopEvent {
    }

    private static final class CalendarEvent extends SharedQuickShopEvent {
    }
}

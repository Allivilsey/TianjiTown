package cn.tianji.town.integrations.quickshop;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

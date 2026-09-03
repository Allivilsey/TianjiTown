package cn.tianji.town.integrations;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ThirdPartyEventExecutorTest {
    @Test
    void filtersUnrelatedEventsAndContainsLinkageErrors() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        var executor = ThirdPartyEventExecutor.filtered(ExpectedEvent.class, event -> {
            handled.incrementAndGet();
            throw new NoSuchMethodError("INJECTED");
        }, ignored -> failures.incrementAndGet());

        executor.execute(null, new OtherEvent());
        assertDoesNotThrow(() -> executor.execute(null, new ExpectedEvent()));

        assertEquals(1, handled.get());
        assertEquals(1, failures.get());
    }

    @Test
    void containsFailureHandlerErrors() {
        var executor = ThirdPartyEventExecutor.filtered(ExpectedEvent.class,
                event -> {
                    throw new NoSuchMethodError("INJECTED");
                }, failure -> {
                    throw new NoClassDefFoundError("LOGGER_INJECTED");
                });

        assertDoesNotThrow(() -> executor.execute(null, new ExpectedEvent()));
    }

    private abstract static class SharedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static final class ExpectedEvent extends SharedEvent {
    }

    private static final class OtherEvent extends SharedEvent {
    }
}

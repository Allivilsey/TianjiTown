package org.allivlisey.tianjitown.paper.ui;

import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCallbackServiceTest {
    private final Clock clock = mock(Clock.class);
    private final ChatCallbackService callbacks = new ChatCallbackService(clock);
    private final UUID recipient = UUID.randomUUID();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void chatLinkUsesVanillaCommandWithNoCustomPayload() {
        ClickEvent event = callbacks.create(recipient, () -> true, calls::incrementAndGet);
        assertEquals(ClickEvent.Action.RUN_COMMAND, event.action());
        assertTrue(((ClickEvent.Payload.Text) event.payload()).value()
                .startsWith("/tianjitown:tianjitown-callback "));
        assertDoesNotThrow(() -> UUID.fromString(token(event)));
        callbacks.execute(recipient, token(event));
        assertEquals(1, calls.get());
    }

    @Test
    void otherPlayersAndUnknownTokensCannotExecuteOrConsumeCallback() {
        String token = token(callbacks.create(recipient, () -> true, calls::incrementAndGet));
        for (int i = 0; i < 6; i++) callbacks.execute(UUID.randomUUID(), token);
        callbacks.execute(recipient, "unknown");
        assertEquals(0, calls.get());
        callbacks.execute(recipient, token);
        assertEquals(1, calls.get());
    }

    @Test
    void callbackCanOnlyBeUsedFiveTimes() {
        String token = token(callbacks.create(recipient, () -> true, calls::incrementAndGet));
        for (int i = 0; i < 6; i++) callbacks.execute(recipient, token);
        assertEquals(5, calls.get());
    }

    @Test
    void expiredCallbackCannotExecute() {
        String token = token(callbacks.create(recipient, () -> true, calls::incrementAndGet));
        when(clock.millis()).thenReturn(Duration.ofDays(7).toMillis());
        callbacks.execute(recipient, token);
        assertEquals(0, calls.get());
    }

    @Test
    void inactiveAndClearedCallbacksCannotExecute() {
        AtomicBoolean active = new AtomicBoolean(true);
        String token = token(callbacks.create(recipient, active::get, calls::incrementAndGet));
        active.set(false);
        callbacks.execute(recipient, token);
        active.set(true);
        callbacks.execute(recipient, token);
        String next = token(callbacks.create(recipient, active::get, calls::incrementAndGet));
        callbacks.clear();
        callbacks.execute(recipient, next);
        assertEquals(0, calls.get());
    }

    @Test
    void callbackRegistryEvictsOldestLinkAtCapacity() {
        String oldest = token(callbacks.create(recipient, () -> true, calls::incrementAndGet));
        for (int i = 0; i < 10_000; i++) callbacks.create(recipient, () -> true, () -> {});
        callbacks.execute(recipient, oldest);
        assertEquals(0, calls.get());
    }

    private static String token(ClickEvent event) {
        String command = ((ClickEvent.Payload.Text) event.payload()).value();
        return command.substring(command.lastIndexOf(' ') + 1);
    }
}

package org.allivlisey.tianjitown.paper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownDialogServiceTest {
    @Test
    void aNewSessionInvalidatesThePreviousTokenAndOnlyOneResponseConsumesIt() {
        TownDialogService.DialogSessions sessions = new TownDialogService.DialogSessions();
        UUID player = UUID.randomUUID();
        UUID first = sessions.open(player);
        UUID second = sessions.open(player);

        assertFalse(sessions.isCurrent(player, first));
        assertTrue(sessions.isCurrent(player, second));
        assertTrue(sessions.consume(player, second));
        assertFalse(sessions.consume(player, second));
        assertFalse(sessions.isCurrent(player, second));
    }

    @Test
    void clearInvalidatesCallbacksForThePlayer() {
        TownDialogService.DialogSessions sessions = new TownDialogService.DialogSessions();
        UUID player = UUID.randomUUID();
        UUID session = sessions.open(player);

        sessions.clear(player);

        assertFalse(sessions.isCurrent(player, session));
        assertFalse(sessions.consume(player, session));
    }

    @Test
    void closeReturnsCurrentViewersOnceAndRejectsNewSessions() {
        TownDialogService.DialogSessions sessions = new TownDialogService.DialogSessions();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        sessions.open(first);
        sessions.open(second);

        List<UUID> viewers = sessions.close();

        assertTrue(viewers.containsAll(List.of(first, second)));
        assertFalse(sessions.isActive());
        assertTrue(sessions.close().isEmpty());
        assertThrows(IllegalStateException.class, () -> sessions.open(UUID.randomUUID()));
    }
}

package org.allivlisey.tianjitown.paper.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertFalse(sessions.consume(player, first));
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
        UUID firstToken = sessions.open(first);
        UUID secondToken = sessions.open(second);

        List<UUID> viewers = sessions.close();

        assertEquals(2, viewers.size());
        assertEquals(java.util.Set.of(first, second), java.util.Set.copyOf(viewers));
        assertFalse(sessions.consume(first, firstToken));
        assertFalse(sessions.consume(second, secondToken));
        assertFalse(sessions.isActive());
        assertTrue(sessions.close().isEmpty());
        assertThrows(IllegalStateException.class, () -> sessions.open(UUID.randomUUID()));
    }

    @Test
    void anotherPlayersTokenCannotConsumeOrClearTheCurrentSession() {
        var sessions = new TownDialogService.DialogSessions();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID aliceToken = sessions.open(alice);
        UUID bobToken = sessions.open(bob);

        assertFalse(sessions.consume(alice, bobToken));
        sessions.clear(alice, bobToken);
        assertTrue(sessions.isCurrent(alice, aliceToken));
        sessions.clear(alice);
        assertTrue(sessions.consume(bob, bobToken));
        assertFalse(sessions.consume(alice, aliceToken));
    }

    @Test
    void staleCleanupCannotInvalidateAReplacementDialog() {
        var sessions = new TownDialogService.DialogSessions();
        UUID player = UUID.randomUUID();
        UUID old = sessions.open(player);
        UUID current = sessions.open(player);

        sessions.clear(player, old);

        assertTrue(sessions.isCurrent(player, current));
        assertFalse(sessions.consume(player, null));
        sessions.clear(player, current);
        assertFalse(sessions.consume(player, current));
    }
}

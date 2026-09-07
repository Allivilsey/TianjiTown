package org.allivlisey.tianjitown.paper.ui;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TownUiRouteCatalogTest {
    private final Player player = mock(Player.class);
    private final TownUiActionRouter.ModeGate gate = mock(TownUiActionRouter.ModeGate.class);
    private final TownUiActionRouter.UnknownActionOwner expired = mock(TownUiActionRouter.UnknownActionOwner.class);
    private final TownUiActionRouter.FeatureOwner membership = mock(TownUiActionRouter.FeatureOwner.class);
    private final TownUiActionRouter.ActionOwner home = mock(TownUiActionRouter.ActionOwner.class);

    @ParameterizedTest
    @ValueSource(strings = {"MEMBERS", "MEMBER_DETAILS"})
    void registeredFeatureReceivesTheOriginalPlayerActionAndTarget(String action) {
        var router = TownUiActionRouter.builder(gate, expired)
                .register("MAIN", home)
                .registerAll(membership, "MEMBERS", "MEMBER_DETAILS").build();
        router.route(player, action, "town:member");
        var order = inOrder(gate, membership);
        order.verify(gate).blocked(player, action);
        order.verify(membership).route(player, action, "town:member");
        verifyNoMoreInteractions(membership);
        verifyNoInteractions(home, expired);
    }

    @Test
    void targetlessActionIsDeliveredOnce() {
        var router = TownUiActionRouter.builder(gate, expired).register("MAIN", home).build();
        router.route(player, "MAIN", null);
        verify(home).handle(player, null);
        verifyNoMoreInteractions(home);
        verifyNoInteractions(expired);
    }

    @Test
    void unknownActionOpensExpiredPageForTheSamePlayer() {
        var router = TownUiActionRouter.builder(gate, expired).register("MAIN", home).build();
        router.route(player, "REMOVED_ACTION", "old-target");
        verify(expired).handle(player);
        verifyNoMoreInteractions(expired);
        verifyNoInteractions(home);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAIN", "UNKNOWN"})
    void maintenanceGateBlocksRegisteredAndUnknownActions(String action) {
        when(gate.blocked(player, action)).thenReturn(true);
        var router = TownUiActionRouter.builder(gate, expired).register("MAIN", home).build();
        router.route(player, action, null);
        verify(gate).blocked(player, action);
        verifyNoInteractions(home, expired);
    }

    @Test
    void duplicateRegistrationCannotReplaceTheOriginalOwner() {
        var builder = TownUiActionRouter.builder(gate, expired).register("MAIN", home);
        assertThrows(IllegalStateException.class,
                () -> builder.registerAll(membership, "MAIN"));
        builder.build().route(player, "MAIN", "target");
        verify(home).handle(player, "target");
        verifyNoInteractions(membership, expired);
    }

    @Test
    void builtRouterIsUnaffectedByLaterRegistrations() {
        var builder = TownUiActionRouter.builder(gate, expired).register("MAIN", home);
        var router = builder.build();
        builder.registerAll(membership, "MEMBERS");
        router.route(player, "MEMBERS", null);
        verify(expired).handle(player);
        verifyNoInteractions(membership, home);
    }
}

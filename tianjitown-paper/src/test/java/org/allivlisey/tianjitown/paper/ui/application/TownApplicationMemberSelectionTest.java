package org.allivlisey.tianjitown.paper.ui.application;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.ApplicationFormSession;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.FormPurpose;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownApplicationMemberSelectionTest {
    private final TownUiLegacyFacade facade = mock(TownUiLegacyFacade.class);
    private final TownUiPresentation presentation = mock(TownUiPresentation.class);
    private final TownApplicationFormUi forms = new TownApplicationFormUi(facade);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final ApplicationFormSession form = new ApplicationFormSession(UUID.randomUUID(),
            FormPurpose.APPLICATION, null, 0,
            new ApplicationText("", "", "", List.of()), List.of("Alex", "Steve"));
    private TownApplicationFormDialogs dialogs;

    @BeforeEach
    void setUp() {
        when(facade.presentation()).thenReturn(presentation);
        when(facade.applicationFormUi()).thenReturn(forms);
        when(player.getUniqueId()).thenReturn(playerId);
        when(presentation.dialogText(anyString())).thenAnswer(call -> call.getArgument(0));
        forms.putSession(playerId, form);
        dialogs = new TownApplicationFormDialogs(facade);
    }

    @Test
    void selectedPlayerCanBeReplacedWithoutSendingInvitations() {
        choose("Charlie", 0);

        assertSavedMembers(List.of("Charlie", "Steve"));
    }

    @Test
    void eitherSlotCanBeClearedWithoutSendingInvitations() {
        dialogs.clearInitialMember(player, form.id(), 0);
        assertSavedMembers(List.of("", "Steve"));
        dialogs.clearInitialMember(player, form.id(), 1);
        assertSavedMembers(List.of("", ""));
    }

    @Test
    void clearingAllowsTheTwoSelectedPlayersToSwapSlots() {
        dialogs.clearInitialMember(player, form.id(), 0);
        choose("Alex", 1);
        choose("Steve", 0);

        assertSavedMembers(List.of("Steve", "Alex"));
    }

    @Test
    void duplicateSelectionDoesNotOverwriteDraft() {
        choose("sTeVe", 0);

        assertSame(form, forms.session(playerId));
        verify(facade, never()).persistApplicationForm(any(), any(), anyInt(), any(Consumer.class));
    }

    @Test
    void expiredFormCannotClearCurrentSelection() {
        dialogs.clearInitialMember(player, UUID.randomUUID(), 0);

        assertSame(form, forms.session(playerId));
        verify(facade, never()).persistApplicationForm(any(), any(), anyInt(), any(Consumer.class));
    }

    @Test
    void clearRouteValidatesMemberSlot() {
        forms.route(player, "CLEAR_INITIAL_MEMBER", form.id() + ":1");
        verify(facade).clearInitialMember(player, form.id(), 1);
        forms.route(player, "CLEAR_INITIAL_MEMBER", form.id() + ":2");
        verify(facade).openStaleMenu(player);
        verify(facade, never()).clearInitialMember(player, form.id(), 2);
    }

    private void choose(String name, int index) {
        UUID candidateId = UUID.randomUUID();
        Player candidate = mock(Player.class);
        when(candidate.getUniqueId()).thenReturn(candidateId);
        when(candidate.getName()).thenReturn(name);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(candidateId)).thenReturn(candidate);
            dialogs.chooseInitialMember(player, form.id() + ":" + index + ":" + candidateId);
        }
    }

    private void assertSavedMembers(List<String> expected) {
        ApplicationFormSession updated = forms.session(playerId);
        assertEquals(expected, updated.initialMemberNames());
        assertEquals(form.id(), updated.id());
        assertEquals(form.text(), updated.text());
        verify(facade).persistApplicationForm(eq(player), eq(updated), eq(3), any(Consumer.class));
        verify(facade, never()).notifyInitialMembers(any());
        verify(facade, never()).saveApplicationForm(any(), any());
    }
}

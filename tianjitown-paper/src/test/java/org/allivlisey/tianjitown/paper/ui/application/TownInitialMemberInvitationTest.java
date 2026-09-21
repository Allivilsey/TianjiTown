package org.allivlisey.tianjitown.paper.ui.application;

import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownInitialMemberInvitationTest {
    private final TownUiLegacyFacade facade = mock(TownUiLegacyFacade.class);
    private final TownUiPresentation presentation = mock(TownUiPresentation.class);
    private final TownRuntime runtime = mock(TownRuntime.class);
    private final TownRepository repository = mock(TownRepository.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private TownInitialMemberDialogs dialogs;

    @BeforeEach
    void setUp() {
        when(facade.presentation()).thenReturn(presentation);
        when(facade.runtime()).thenReturn(runtime);
        when(runtime.repository()).thenReturn(repository);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(repository.listPendingInitialMemberApplications(playerId)).thenReturn(List.of());
        when(presentation.dialogText(anyString())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            Supplier<?> operation = call.getArgument(1);
            Consumer<Object> success = call.getArgument(2);
            success.accept(operation.get());
            return null;
        }).when(runtime).read(eq(player), any(), any());
        dialogs = new TownInitialMemberDialogs(facade);
    }

    @Test
    void staleOrOtherPlayersInvitationDoesNotExposeDetailsOrActions() {
        dialogs.openInvitation(player, applicationId);
        verify(repository).listPendingInitialMemberApplications(playerId);
        verify(presentation).openNotice(player, "invitation.title", "invitation.unavailable",
                "common.back", "PENDING_CENTER", null);
        verify(presentation, never()).openDialogPage(any(), any(), anyList(), anyList(), any(), any(), any());
    }

    @Test
    void automaticReminderRechecksPersistedInvitationInsteadOfUsingOldSnapshot() {
        ApplicationSnapshot stale = mock(ApplicationSnapshot.class);
        when(stale.id()).thenReturn(applicationId);
        dialogs.sendInitialMemberReminder(player, stale);
        verify(repository).listPendingInitialMemberApplications(playerId);
        verify(presentation).openNotice(player, "invitation.title", "invitation.unavailable",
                "common.back", "PENDING_CENTER", null);
    }

    @Test
    void disconnectedPlayerDoesNotReceiveLateDialog() {
        when(player.isOnline()).thenReturn(false);
        dialogs.openInvitation(player, applicationId);
        verifyNoInteractions(presentation);
    }

    @Test
    void invitationRouteOpensRecipientDetail() {
        new TownApplicationUi(facade).route(player, "INITIAL_MEMBER_INVITATION", applicationId.toString());
        verify(facade).openInitialMemberInvitation(player, applicationId);
    }
}

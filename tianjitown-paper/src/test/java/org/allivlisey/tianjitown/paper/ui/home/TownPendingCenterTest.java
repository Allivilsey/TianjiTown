package org.allivlisey.tianjitown.paper.ui.home;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownPendingCenterTest {
    private final TownUiLegacyFacade facade = mock(TownUiLegacyFacade.class);
    private final TownUiPresentation presentation = mock(TownUiPresentation.class);
    private final TownRuntime runtime = mock(TownRuntime.class);
    private final TownRepository repository = mock(TownRepository.class);
    private final GovernanceRepository governance = mock(GovernanceRepository.class);
    private final Player player = mock(Player.class);
    private TownHomeDialogs dialogs;

    @BeforeEach
    void setUp() {
        when(facade.presentation()).thenReturn(presentation);
        when(facade.runtime()).thenReturn(runtime);
        when(facade.plugin()).thenReturn(mock(TianjiTownPlugin.class));
        when(runtime.repository()).thenReturn(repository);
        when(runtime.governance()).thenReturn(governance);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(governance.dashboard(any())).thenReturn(Optional.empty());
        when(repository.dashboard(any())).thenReturn(new TownRepository.PlayerDashboard(null, null, List.of(), List.of()));
        when(repository.listPendingInitialMemberApplications(any())).thenReturn(List.of());
        when(presentation.dialogText(anyString())).thenAnswer(call -> call.getArgument(0));
        when(presentation.dialogText(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        when(facade.isCurrent(eq(player), any())).thenReturn(true);
        doAnswer(call -> {
            Supplier<?> operation = call.getArgument(1);
            Consumer<Object> success = call.getArgument(2);
            success.accept(operation.get());
            return null;
        }).when(runtime).read(eq(player), any(), any());
        dialogs = new TownHomeDialogs(facade);
    }

    @Test
    void playersWithoutTownCanOpenEmptyPendingCenter() {
        dialogs.openPendingCenter(player);
        verify(presentation).openMenu(eq(player), eq(54), eq("pending.title"),
                eq(new DialogRoute("MAIN", null)), anyList());
        verify(facade, never()).openMain(any());
    }

    @Test
    void mainShowsPendingEntryWithoutTownIncludingApplicants() {
        dialogs.openMain(player);
        verify(presentation).button(any(), anyString(), anyList(), eq("PENDING_CENTER"), isNull());
        clearInvocations(presentation);
        ApplicationSnapshot application = invitation();
        when(repository.dashboard(any())).thenReturn(new TownRepository.PlayerDashboard(null,
                application, List.of(), List.of()));
        dialogs.openMain(player);
        verify(presentation).button(any(), anyString(), anyList(), eq("PENDING_CENTER"), isNull());
    }

    @Test
    void invitationsAreCountedAndAllRemainReachableAcrossPages() {
        var invitations = IntStream.range(0, 10).mapToObj(index -> invitation()).toList();
        when(repository.listPendingInitialMemberApplications(any())).thenReturn(invitations);
        dialogs.openMain(player);
        verify(presentation).dialogText("main.pending-count", java.util.Map.of("count", 10));
        clearInvocations(presentation);
        dialogs.openPendingCenter(player, 0);
        verify(presentation, times(8)).button(any(), anyString(), anyList(), eq("INITIAL_MEMBER_INVITATION"), anyString());
        verify(presentation).button(any(), eq("common.next"), anyList(), eq("PENDING_CENTER"), eq("1"));
        clearInvocations(presentation);
        dialogs.openPendingCenter(player, 1);
        verify(presentation, times(2)).button(any(), anyString(), anyList(), eq("INITIAL_MEMBER_INVITATION"), anyString());
        verify(presentation).button(any(), eq("common.previous"), anyList(), eq("PENDING_CENTER"), eq("0"));
    }

    private ApplicationSnapshot invitation() {
        ApplicationSnapshot application = mock(ApplicationSnapshot.class);
        when(application.id()).thenReturn(UUID.randomUUID());
        when(application.applicantId()).thenReturn(UUID.randomUUID());
        when(application.text()).thenReturn(new ApplicationText("测试镇", "test", "介绍", List.of("规则")));
        when(facade.displayName(application.applicantId())).thenReturn("Applicant");
        return application;
    }
}

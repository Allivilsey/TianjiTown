package org.allivlisey.tianjitown.paper.ui.membership;

import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;
import org.allivlisey.tianjitown.storage.town.TownRepository.PlayerDashboard;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownJoinApplicationStaleTest {
    @Test
    void processedOrExpiredRequestShowsStaleNoticeInsteadOfThrowingInMainCallback() {
        TownUiLegacyFacade facade = mock(TownUiLegacyFacade.class);
        TownRuntime runtime = mock(TownRuntime.class);
        Player mayor = mock(Player.class);
        PlayerDashboard dashboard = mock(PlayerDashboard.class);
        when(facade.runtime()).thenReturn(runtime);
        when(dashboard.incomingJoinApplications()).thenReturn(List.of());
        doAnswer(invocation -> {
            Consumer<PlayerDashboard> success = invocation.getArgument(2);
            success.accept(dashboard);
            return null;
        }).when(runtime).read(eq(mayor), any(Supplier.class), any(Consumer.class));

        TownJoinApplicationDialogs dialogs = new TownJoinApplicationDialogs(facade);
        assertDoesNotThrow(() -> dialogs.openTownJoinApplication(mayor, UUID.randomUUID()));
        verify(facade).openStaleMenu(mayor);
    }
}

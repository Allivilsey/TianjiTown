package org.allivlisey.tianjitown.paper.ui.membership;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlayerChangeDeliveryTest {
    @TempDir Path directory;

    private TianjiTownPlugin plugin() {
        TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        when(plugin.messages()).thenReturn(new PluginMessages(directory.toFile()));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> { call.<Runnable>getArgument(0).run(); return true; });
        when(plugin.runMain(any())).thenAnswer(call -> { call.<Runnable>getArgument(0).run(); return true; });
        return plugin;
    }

    @Test void acknowledgementFailureRetriesWithoutSendingAgain() {
        UUID target = UUID.randomUUID();
        TownRepository repository = mock(TownRepository.class);
        var record = new TownRepository.PlayerChangeNotification(1, target, "测试镇", "MEMBER", "DEPUTY_MAYOR");
        when(repository.pendingPlayerChanges(target)).thenReturn(List.of(record), List.of());
        doThrow(new IllegalStateException("temporary database failure")).doNothing()
                .when(repository).acknowledgePlayerChanges(target, List.of(1L));
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(target)).thenReturn(player);
            PlayerChangeDelivery delivery = new PlayerChangeDelivery(plugin(), repository);
            delivery.deliver(target);
            delivery.deliver(target);
            delivery.deliver(target);
            verify(player, times(1)).sendMessage(contains("成员 变更为 副镇长"));
            verify(repository, times(2)).acknowledgePlayerChanges(target, List.of(1L));
        }
    }

    @Test void offlinePlayerKeepsPendingRecordUntilLogin() {
        UUID target = UUID.randomUUID();
        TownRepository repository = mock(TownRepository.class);
        when(repository.pendingPlayerChanges(target)).thenReturn(List.of(
                new TownRepository.PlayerChangeNotification(1, target, "测试镇", "DEPUTY_MAYOR", "MEMBER")));
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            PlayerChangeDelivery delivery = new PlayerChangeDelivery(plugin(), repository);
            delivery.deliver(target);
            verify(repository, never()).acknowledgePlayerChanges(any(), any());
            bukkit.when(() -> Bukkit.getPlayer(target)).thenReturn(player);
            delivery.deliver(target);
            verify(player).sendMessage(contains("副镇长 变更为 成员"));
            verify(repository).acknowledgePlayerChanges(target, List.of(1L));
        }
    }

    @Test void readFailureDoesNotSendSuccessOrLosePendingRecords() {
        UUID target = UUID.randomUUID();
        TownRepository repository = mock(TownRepository.class);
        when(repository.pendingPlayerChanges(target)).thenThrow(new IllegalStateException("unavailable"));
        PlayerChangeDelivery delivery = new PlayerChangeDelivery(plugin(), repository);
        delivery.deliver(target);
        verify(repository, never()).acknowledgePlayerChanges(any(), any());
    }
}

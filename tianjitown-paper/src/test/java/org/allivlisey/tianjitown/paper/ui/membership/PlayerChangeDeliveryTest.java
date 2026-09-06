package org.allivlisey.tianjitown.paper.ui.membership;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({
            "NONE, MEMBER, 你已加入“测试镇”小镇",
            "VISITOR, MEMBER, 你已加入“测试镇”小镇",
            "NONE, MAYOR, 你已成为“测试镇”小镇的镇长",
            "MEMBER, MAYOR, 你已成为“测试镇”小镇的镇长",
            "DEPUTY_MAYOR, MAYOR, 你已成为“测试镇”小镇的镇长",
            "MEMBER, DEPUTY_MAYOR, 你已成为“测试镇”小镇的副镇长",
            "MAYOR, MEMBER, 你现在是“测试镇”小镇的普通成员",
            "DEPUTY_MAYOR, MEMBER, 你现在是“测试镇”小镇的普通成员",
            "MEMBER, NONE, 你已离开“测试镇”小镇",
            "DEPUTY_MAYOR, NONE, 你已离开“测试镇”小镇",
            "MAYOR, NONE, 你已离开“测试镇”小镇",
            "NONE, VISITOR, 你已成为“测试镇”小镇的访客",
            "VISITOR, NONE, 你已不再是“测试镇”小镇的访客"
    })
    void deliversSpecificMessageForIdentityTransition(String oldRole, String newRole, String expected) {
        UUID target = UUID.randomUUID();
        TownRepository repository = mock(TownRepository.class);
        when(repository.pendingPlayerChanges(target)).thenReturn(List.of(
                new TownRepository.PlayerChangeNotification(1, target, "测试镇", oldRole, newRole)));
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(target)).thenReturn(player);
            new PlayerChangeDelivery(plugin(), repository).deliver(target);
            verify(player).sendMessage(contains(expected));
            verify(repository).acknowledgePlayerChanges(target, List.of(1L));
        }
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
            verify(player, times(1)).sendMessage(contains("你已成为“测试镇”小镇的副镇长"));
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
            verify(player).sendMessage(contains("你现在是“测试镇”小镇的普通成员"));
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

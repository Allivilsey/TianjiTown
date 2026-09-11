package org.allivlisey.tianjitown.paper.ui.membership;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private PluginMessages messages;

    private TianjiTownPlugin plugin() {
        TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        messages = spy(new PluginMessages(directory.toFile()));
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> { call.<Runnable>getArgument(0).run(); return true; });
        when(plugin.runMain(any())).thenAnswer(call -> { call.<Runnable>getArgument(0).run(); return true; });
        return plugin;
    }

    @ParameterizedTest
    @CsvSource({
            "NONE, MEMBER, member-joined",
            "VISITOR, MEMBER, member-joined",
            "NONE, MAYOR, became-mayor",
            "MEMBER, MAYOR, became-mayor",
            "DEPUTY_MAYOR, MAYOR, became-mayor",
            "MEMBER, DEPUTY_MAYOR, became-deputy-mayor",
            "MAYOR, MEMBER, became-member",
            "DEPUTY_MAYOR, MEMBER, became-member",
            "MEMBER, NONE, member-left",
            "DEPUTY_MAYOR, NONE, member-left",
            "MAYOR, NONE, member-left",
            "NONE, VISITOR, visitor-added",
            "VISITOR, NONE, visitor-removed"
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
            verify(messages).send(player, "chat.notification." + expected, Map.of("town", "测试镇"));
            verify(player).sendMessage(messages.text("chat.notification." + expected, Map.of("town", "测试镇")));
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
            verify(messages, times(1)).send(player, "chat.notification.became-deputy-mayor",
                    Map.of("town", "测试镇"));
            verify(player, times(1)).sendMessage(anyString());
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
            verify(messages).send(player, "chat.notification.became-member", Map.of("town", "测试镇"));
            verify(player).sendMessage(anyString());
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

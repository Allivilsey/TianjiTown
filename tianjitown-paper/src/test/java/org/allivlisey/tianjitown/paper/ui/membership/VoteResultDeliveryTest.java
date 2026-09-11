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
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VoteResultDeliveryTest {
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

    @Test void acknowledgementFailureRetriesWithoutSendingAgain() {
        UUID target = UUID.randomUUID();
        TownRepository repository = mock(TownRepository.class);
        var record = new TownRepository.VoteResultNotification(1, target, "测试镇", UUID.randomUUID(), "KICK_MEMBER", "PASSED", 2, 0, 2);
        when(repository.pendingVoteResults(target)).thenReturn(List.of(record), List.of());
        doThrow(new IllegalStateException("temporary database failure")).doNothing()
                .when(repository).acknowledgeVoteResults(target, List.of(1L));
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(target)).thenReturn(player);
            VoteResultDelivery delivery = new VoteResultDelivery(plugin(), repository);
            delivery.deliver(target);
            delivery.deliver(target);
            delivery.deliver(target);
            Map<String, ?> expected = Map.of(
                    "town", "测试镇", "id", record.voteId(),
                    "type", messages.plainText("dialog.votes.type-kick"),
                    "status", messages.plainText("dialog.votes.status-passed"),
                    "yes", 2, "no", 0, "required", 2);
            verify(messages, times(1)).send(player, "chat.notification.vote-result", expected);
            verify(player, times(1)).sendMessage(anyString());
            verify(repository, times(2)).acknowledgeVoteResults(target, List.of(1L));
        }
    }

    @Test void offlinePlayerKeepsPendingRecordUntilLogin() {
        UUID target = UUID.randomUUID();
        TownRepository repository = mock(TownRepository.class);
        var record = new TownRepository.VoteResultNotification(1, target, "测试镇", UUID.randomUUID(),
                "REPLACE_MAYOR", "REJECTED", 0, 2, 2);
        when(repository.pendingVoteResults(target)).thenReturn(List.of(record));
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            VoteResultDelivery delivery = new VoteResultDelivery(plugin(), repository);
            delivery.deliver(target);
            verify(repository, never()).acknowledgeVoteResults(any(), any());
            bukkit.when(() -> Bukkit.getPlayer(target)).thenReturn(player);
            delivery.deliver(target);
            Map<String, ?> expected = Map.of(
                    "town", "测试镇", "id", record.voteId(),
                    "type", messages.plainText("dialog.votes.type-replace-mayor"),
                    "status", messages.plainText("dialog.votes.status-rejected"),
                    "yes", 0, "no", 2, "required", 2);
            verify(messages).send(player, "chat.notification.vote-result", expected);
            verify(player).sendMessage(anyString());
            verify(repository).acknowledgeVoteResults(target, List.of(1L));
        }
    }

    @Test void readFailureDoesNotSendSuccessOrLosePendingRecords() {
        UUID target = UUID.randomUUID();
        TownRepository repository = mock(TownRepository.class);
        when(repository.pendingVoteResults(target)).thenThrow(new IllegalStateException("unavailable"));
        VoteResultDelivery delivery = new VoteResultDelivery(plugin(), repository);
        delivery.deliver(target);
        verify(repository, never()).acknowledgeVoteResults(any(), any());
    }
}

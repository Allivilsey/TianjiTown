package org.allivlisey.tianjitown.paper.buff;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.BuffSettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuffRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final TownRuntime host = mock(TownRuntime.class);
    private final CommerceRepository repository = mock(CommerceRepository.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final List<Action> reads = new ArrayList<>();
    private final List<Action> writes = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Runnable> main = new ArrayList<>();
    private final BuffSettings settings = new BuffSettings(true, Map.of("speed",
            new BuffDefinition("speed", "速度", BuffDefinition.EffectKind.ATTRIBUTE,
                    "minecraft:movement_speed", "ADD_SCALAR", BigDecimal.TEN, 1, 0.2)));

    BuffRuntimeTest() {
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        PluginMessages messages = mock(PluginMessages.class);
        when(plugin.messages()).thenReturn(messages);
        when(messages.plainText(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(host.consumptionEnabled()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenAnswer(call -> {
                    scheduled.add(call.getArgument(1));
                    return mock(BukkitTask.class);
                });
        doAnswer(call -> {
            reads.add(new Action(call.getArgument(1), call.getArgument(2), call.getArgument(3)));
            return null;
        }).when(host).readAction(any(), any(), any(), any());
        doAnswer(call -> {
            writes.add(new Action(call.getArgument(1), call.getArgument(2), call.getArgument(3)));
            return null;
        }).when(host).writeAction(any(), any(), any(), any());
    }

    @Test
    void staleReadsAndCallbacksAfterShutdownCannotApplyEffects() {
        try (var constructed = mockConstruction(BuffPlayerEffects.class)) {
            BuffRuntime runtime = new BuffRuntime(plugin, host, repository, settings);
            BuffPlayerEffects effects = constructed.constructed().getFirst();
            runtime.refreshPlayer(player);
            runtime.refreshPlayer(player);
            reads.getFirst().success().accept(List.of());
            verifyNoInteractions(effects);
            reads.getLast().success().accept(List.of());
            verify(effects).applyBuffs(player, List.of(), false);

            runtime.refreshPlayer(player);
            runtime.clearAll();
            reads.getLast().success().accept(List.of());
            verify(effects).clearAll();
            verifyNoMoreInteractions(effects);
        }
    }

    @Test
    void respawnExpiresRecordsBeforeReadingAndNormalizesHealthOnMainCallback() {
        try (var constructed = mockConstruction(BuffPlayerEffects.class)) {
            BuffRuntime runtime = new BuffRuntime(plugin, host, repository, settings);
            PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
            when(event.getPlayer()).thenReturn(player);
            runtime.onRespawn(event);
            assertTrue(writes.isEmpty());
            main.getFirst().run();
            assertTrue(reads.isEmpty());
            Action refresh = writes.getFirst();
            when(repository.activeBuffsForPlayer(eq(playerId), any())).thenReturn(List.of());
            Object buffs = refresh.operation().get();
            var order = inOrder(repository);
            order.verify(repository).expireBuffsForPlayer(eq(playerId), any());
            order.verify(repository).activeBuffsForPlayer(eq(playerId), any());
            BuffPlayerEffects effects = constructed.constructed().getFirst();
            verifyNoInteractions(effects);
            refresh.success().accept(buffs);
            verify(effects).applyBuffs(player, List.of(), true);
        }
    }

    @Test
    void failedPurchaseApplicationRefundsBeforeReportingFailure() {
        try (var constructed = mockConstruction(BuffPlayerEffects.class)) {
            BuffRuntime runtime = new BuffRuntime(plugin, host, repository, settings);
            BuffPlayerEffects effects = constructed.constructed().getFirst();
            RuntimeException applyFailure = new IllegalStateException("apply failed");
            doThrow(applyFailure).when(effects).applyBuffs(eq(player), anyList(), eq(false));
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            runtime.buyBuffAction(player, "speed", 1, 1,
                    ignored -> fail("failed application must not report purchase success"), failure::set);
            CommerceRepository.ActiveBuff buff = activeBuff();
            CommerceRepository.BuffPurchase purchase = new CommerceRepository.BuffPurchase(buff, 100);
            writes.getFirst().success().accept(purchase);
            assertEquals(1, scheduled.size(), "purchase must retain an expiration before verification");
            reads.getFirst().success().accept(List.of(buff));
            assertNull(failure.get(), "refund must finish before the failure callback");
            Action refund = writes.getLast();
            refund.operation().get();
            verify(repository).refundActiveBuff(buff.buffId(), null, "SYSTEM", "log.buff.refund-reason");
            refund.success().accept(purchase);
            assertSame(applyFailure, failure.get().getCause());
            assertEquals("chat.buff.application-failure-refunded", failure.get().getMessage());
        }
    }

    @Test
    void quitPreservesEffectsAndInvalidatesPendingWorkEvenAfterRejoin() {
        try (var constructed = mockConstruction(BuffPlayerEffects.class)) {
            BuffRuntime runtime = new BuffRuntime(plugin, host, repository, settings);
            BuffPlayerEffects effects = constructed.constructed().getFirst();
            runtime.refreshPlayer(player);
            reads.getFirst().success().accept(List.of(activeBuff()));
            runtime.refreshPlayer(player);
            Action stale = reads.getLast();
            PlayerQuitEvent event = mock(PlayerQuitEvent.class);
            when(event.getPlayer()).thenReturn(player);
            runtime.onQuit(event);
            verify(effects).forgetPlayer(player);
            clearInvocations(effects);
            runtime.refreshPlayer(player);
            stale.success().accept(List.of(activeBuff()));
            scheduled.forEach(Runnable::run);
            verifyNoInteractions(effects);
            assertTrue(writes.isEmpty());
        }
    }

    @Test
    void huskSyncJoinWaitsForCompletionThenExpiresAndReconciles() {
        try (var constructed = mockConstruction(BuffPlayerEffects.class);
             var hook = mockStatic(HuskSyncBuffHook.class)) {
            hook.when(() -> HuskSyncBuffHook.register(eq(plugin), any(), any())).thenReturn(true);
            BuffRuntime runtime = new BuffRuntime(plugin, host, repository, settings);
            runtime.registerHuskSyncHook();
            PlayerJoinEvent event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(player);
            runtime.onJoin(event);
            runtime.refreshPlayer(player);
            assertTrue(reads.isEmpty());
            assertTrue(writes.isEmpty());
            runtime.onSyncComplete(player);
            assertTrue(writes.isEmpty(), "sync event must hand off to the main thread");
            main.getLast().run();
            assertEquals(1, writes.size());
            writes.getFirst().operation().get();
            verify(repository).expireBuffsForPlayer(eq(playerId), any());
            verify(repository).activeBuffsForPlayer(eq(playerId), any());
            writes.getFirst().success().accept(List.of());
            var effects = constructed.constructed().getFirst();
            var order = inOrder(effects);
            order.verify(effects).forgetPlayer(player);
            order.verify(effects).applyBuffs(player, List.of(), false);
        }
    }

    @Test
    void ordinaryJoinStillReconcilesAndLateSyncForOfflinePlayerIsIgnored() {
        try (var constructed = mockConstruction(BuffPlayerEffects.class)) {
            BuffRuntime runtime = new BuffRuntime(plugin, host, repository, settings);
            PlayerJoinEvent event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(player);
            runtime.onJoin(event);
            main.getLast().run();
            assertEquals(1, writes.size());
            when(player.isOnline()).thenReturn(false);
            runtime.onSyncComplete(player);
            main.getLast().run();
            assertEquals(1, writes.size());
            verifyNoInteractions(constructed.constructed().getFirst());
        }
    }

    private CommerceRepository.ActiveBuff activeBuff() {
        CommerceRepository.ActiveBuff buff = mock(CommerceRepository.ActiveBuff.class);
        when(buff.buffId()).thenReturn(UUID.randomUUID());
        when(buff.expiresAt()).thenReturn(Instant.now().plusSeconds(60));
        return buff;
    }

    private record Action(Supplier<Object> operation, Consumer<Object> success,
                          Consumer<RuntimeException> failure) {}
}

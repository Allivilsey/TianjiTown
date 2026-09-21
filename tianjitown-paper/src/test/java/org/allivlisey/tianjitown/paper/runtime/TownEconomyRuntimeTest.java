package org.allivlisey.tianjitown.paper.runtime;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TownEconomyRuntimeTest {
    @Test void manualResolutionCannotRaceAQueuedExternalPayment() {
        var plugin = mock(TianjiTownPlugin.class);
        var finance = mock(EconomyRepository.class);
        var vault = mock(VaultSettlementService.class);
        var sender = mock(CommandSender.class);
        var messages = mock(PluginMessages.class);
        Queue<Runnable> workers = new ArrayDeque<>(), main = new ArrayDeque<>();
        when(plugin.messages()).thenReturn(messages);
        when(plugin.runAsync(any())).thenAnswer(c -> workers.add(c.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(c -> main.add(c.getArgument(0)));
        when(sender.getName()).thenReturn("Admin");
        UUID town = UUID.randomUUID();
        var operation = mock(EconomyRepository.EconomyOperation.class);
        when(operation.operationId()).thenReturn(UUID.randomUUID());
        when(finance.prepareOperation(eq(town), eq("ADMIN_ADJUSTMENT"), eq(100L),
                isNull(), eq("Admin"), anyString(), eq("audit"))).thenReturn(operation);
        var available = new AtomicBoolean(true);
        var runtime = new TownEconomyRuntime(plugin, finance, mock(EconomySettings.class), vault,
                available, new TownRuntimeTasks(plugin, available), mock(TownTaxRuntime.class), () -> true);

        runtime.adjustFunds(sender, town, 100, "audit");
        workers.remove().run(); // Prepared and published; the main-thread payment is still queued.
        runtime.resolveOperation(sender, operation, false, "manual", ignored -> fail("must reject"));
        workers.remove().run();

        verify(finance, never()).resolveOperation(any(), anyBoolean(), any(), any(), any());
        verifyNoInteractions(vault);
        main.remove(); // Do not execute the unrelated, previously queued payment preflight.
        main.remove().run();
        verify(messages).send(eq(sender), eq("chat.runtime.operation-failed"), argThat(values ->
                values.get("detail").toString().contains("仍在执行")));
        assertTrue(available.get());
    }

    @Test void donationKeepsTheTownFromTheConfirmationAndNeverDebitsWhenItsMembershipCheckFails() {
        var plugin = mock(TianjiTownPlugin.class);
        var finance = mock(EconomyRepository.class);
        var vault = mock(VaultSettlementService.class);
        var player = mock(org.bukkit.entity.Player.class);
        var server = mock(org.bukkit.Server.class);
        var messages = mock(PluginMessages.class);
        UUID playerId = UUID.randomUUID(), confirmedTown = UUID.randomUUID(), currentTown = UUID.randomUUID();
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        when(plugin.runAsync(any())).thenAnswer(c -> { ((Runnable) c.getArgument(0)).run(); return true; });
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Player");
        var account = mock(EconomyRepository.TownFinance.class);
        when(account.townId()).thenReturn(currentTown);
        when(finance.findFinanceByPlayer(playerId)).thenReturn(Optional.of(account));
        var conflict = new EconomyRepository.ConflictException("membership changed");
        when(finance.prepareOperation(eq(confirmedTown), eq("DONATION"), eq(100L),
                eq(playerId), eq("Player"), anyString(), any())).thenThrow(conflict);
        var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        AtomicBoolean available = new AtomicBoolean(true);
        var runtime = new TownEconomyRuntime(plugin, finance, mock(EconomySettings.class), vault,
                available, new TownRuntimeTasks(plugin, available), mock(TownTaxRuntime.class), () -> true);

        runtime.donateAction(player, confirmedTown, 100, ignored -> fail("must not succeed"), failure::set);

        assertSame(conflict, failure.get());
        verify(finance).prepareOperation(eq(confirmedTown), eq("DONATION"), eq(100L),
                eq(playerId), eq("Player"), anyString(), any());
        verifyNoInteractions(vault);
        assertTrue(available.get());
    }

    @Test void retriesConfirmedExternalPaymentWithoutRepeatingVaultDebit() {
        var plugin = mock(TianjiTownPlugin.class);
        var finance = mock(EconomyRepository.class);
        var vault = mock(VaultSettlementService.class);
        var sender = mock(CommandSender.class);
        var messages = mock(PluginMessages.class);
        Queue<Runnable> worker = new ArrayDeque<>(), main = new ArrayDeque<>(), delayed = new ArrayDeque<>();
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.runAsync(any())).thenAnswer(c -> worker.add(c.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(c -> main.add(c.getArgument(0)));
        when(plugin.runMainLater(any(), anyLong())).thenAnswer(c -> delayed.add(c.getArgument(0)));
        when(sender.getName()).thenReturn("Admin");
        var operation = mock(EconomyRepository.EconomyOperation.class);
        var mutation = mock(EconomyRepository.LedgerMutation.class);
        UUID id = UUID.randomUUID(), town = UUID.randomUUID();
        when(operation.operationId()).thenReturn(id);
        when(operation.amountMinor()).thenReturn(-700L);
        when(finance.prepareOperation(eq(town), eq("ADMIN_ADJUSTMENT"), eq(-700L),
                isNull(), eq("Admin"), anyString(), eq("audit"))).thenReturn(operation);
        when(vault.checkAvailability()).thenReturn(VaultSettlementService.Result.success("ready"));
        when(vault.adjustSettlement(-700)).thenReturn(VaultSettlementService.Result.success("paid"));
        when(finance.completeOperation(id))
                .thenThrow(new EconomyRepository.StorageUnavailableException("temporary failure", null))
                .thenReturn(mutation);
        when(vault.formatMinor(anyLong())).thenReturn("3.00");
        AtomicBoolean available = new AtomicBoolean(true);
        var runtime = new TownEconomyRuntime(plugin, finance, mock(EconomySettings.class), vault,
                available, new TownRuntimeTasks(plugin, available), mock(TownTaxRuntime.class), () -> true);
        runtime.adjustFunds(sender, town, -700, "audit");
        worker.remove().run(); // prepare
        main.remove().run(); // preflight
        worker.remove().run(); // mark before the external operation
        main.remove().run(); // external debit succeeds exactly once
        worker.remove().run(); // ledger fails
        assertFalse(available.get());
        main.remove().run(); // user is told finalization is pending
        verify(messages).send(sender, "chat.lifecycle.external-finalization-pending");
        delayed.remove().run();
        worker.remove().run(); // retry ledger only
        main.remove().run(); // success callback
        assertTrue(available.get());
        verify(finance, times(2)).completeOperation(id);
        verify(vault, times(1)).adjustSettlement(-700);
        verify(messages).send(eq(sender), eq("chat.runtime.funds-adjusted"), anyMap());
        assertTrue(worker.isEmpty());
        assertTrue(delayed.isEmpty());
    }
}

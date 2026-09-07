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

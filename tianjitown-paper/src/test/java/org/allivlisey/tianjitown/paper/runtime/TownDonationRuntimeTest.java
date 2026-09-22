package org.allivlisey.tianjitown.paper.runtime;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownDonationRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final EconomyRepository finance = mock(EconomyRepository.class);
    private final VaultPlayerEconomyService wallet = mock(VaultPlayerEconomyService.class);
    private final Player player = mock(Player.class);
    private final PluginMessages messages = mock(PluginMessages.class);
    private final Queue<Runnable> workers = new ArrayDeque<>(), main = new ArrayDeque<>(), delayed = new ArrayDeque<>();
    private final UUID town = UUID.randomUUID(), playerId = UUID.randomUUID(), operationId = UUID.randomUUID();
    private final EconomyRepository.EconomyOperation operation = mock(EconomyRepository.EconomyOperation.class);
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final TownEconomyRuntime runtime;

    TownDonationRuntimeTest() {
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(c -> workers.add(c.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(c -> main.add(c.getArgument(0)));
        when(plugin.runMainLater(any(), anyLong())).thenAnswer(c -> delayed.add(c.getArgument(0)));
        when(player.getName()).thenReturn("Member");
        when(player.getUniqueId()).thenReturn(playerId);
        var account = mock(EconomyRepository.TownFinance.class);
        when(account.townId()).thenReturn(town);
        when(finance.findFinanceByPlayer(playerId)).thenReturn(Optional.of(account));
        when(operation.operationId()).thenReturn(operationId);
        when(operation.amountMinor()).thenReturn(700L);
        when(finance.prepareOperation(eq(town), eq("DONATION"), eq(700L), eq(playerId), eq("Member"), anyString(), any()))
                .thenReturn(operation);
        when(wallet.checkAvailability()).thenReturn(VaultPlayerEconomyService.Result.success("ready"));
        runtime = new TownEconomyRuntime(plugin, finance, mock(EconomySettings.class), wallet,
                available, new TownRuntimeTasks(plugin, available), mock(TownTaxRuntime.class), () -> true);
    }

    @Test void successfulDebitWithFailedLedgerRetriesOnlyDatabase() {
        when(wallet.withdrawPlayer(player, 700)).thenReturn(VaultPlayerEconomyService.Result.success("paid"));
        var mutation = new EconomyRepository.LedgerMutation(UUID.randomUUID(), town, 700, 700, "donation:1");
        when(finance.completeOperation(operationId))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null)).thenReturn(mutation);
        List<EconomyRepository.LedgerMutation> completed = new ArrayList<>();
        runtime.donateAction(player, 700, completed::add, error -> fail(error));
        workers.remove().run(); // prepare
        main.remove().run();    // preflight
        workers.remove().run(); // persist attempt before calling Vault
        main.remove().run();    // debit player exactly once
        workers.remove().run(); // first ledger write fails
        assertFalse(available.get());
        main.remove().run();    // notify pending finalization
        delayed.remove().run();
        workers.remove().run(); // database retry
        main.remove().run();
        assertTrue(available.get());
        assertEquals(List.of(mutation), completed);
        verify(wallet).withdrawPlayer(player, 700);
        verify(wallet, never()).depositPlayer(any(), anyLong());
        verify(finance, times(2)).completeOperation(operationId);
        assertTrue(workers.isEmpty());
    }

    @Test void manualResolutionCannotRacePendingPlayerDebit() {
        runtime.donateAction(player, 700, ignored -> {}, error -> fail(error));
        workers.remove().run();
        runtime.resolveOperation(player, operation, false, "manual", ignored -> fail("must reject active operation"));
        workers.remove().run();
        verify(finance, never()).resolveOperation(any(), anyBoolean(), any(), anyString(), anyString());
        verify(wallet, never()).withdrawPlayer(any(), anyLong());
    }
}

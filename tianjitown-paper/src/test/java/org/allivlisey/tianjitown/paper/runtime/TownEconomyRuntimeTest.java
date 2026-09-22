package org.allivlisey.tianjitown.paper.runtime;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
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
    @Test void administratorAdjustmentOnlyWritesDatabase() {
        var plugin = mock(TianjiTownPlugin.class);
        var finance = mock(EconomyRepository.class);
        var wallet = mock(VaultPlayerEconomyService.class);
        var sender = mock(CommandSender.class);
        var messages = mock(PluginMessages.class);
        Queue<Runnable> workers = new ArrayDeque<>(), main = new ArrayDeque<>();
        when(plugin.messages()).thenReturn(messages);
        when(plugin.runAsync(any())).thenAnswer(c -> workers.add(c.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(c -> main.add(c.getArgument(0)));
        when(sender.getName()).thenReturn("Admin");
        UUID town = UUID.randomUUID();
        when(finance.adjustFunds(eq(town), eq(-700L), isNull(), eq("Admin"), anyString(), eq("audit")))
                .thenReturn(new EconomyRepository.LedgerMutation(UUID.randomUUID(), town, -700, 300, "key"));
        when(wallet.formatMinor(300)).thenReturn("3.00");
        var available = new AtomicBoolean(true);
        var runtime = new TownEconomyRuntime(plugin, finance, mock(EconomySettings.class), wallet,
                available, new TownRuntimeTasks(plugin, available), mock(TownTaxRuntime.class), () -> true);
        runtime.adjustFunds(sender, town, -700, "audit");
        workers.remove().run();
        verifyNoInteractions(wallet);
        main.remove().run();
        verify(wallet).formatMinor(300);
        verifyNoMoreInteractions(wallet);
        verify(finance, never()).prepareOperation(any(), anyString(), anyLong(), any(), anyString(), anyString(), anyString());
        verify(messages).send(eq(sender), eq("chat.runtime.funds-adjusted"), anyMap());
    }
}

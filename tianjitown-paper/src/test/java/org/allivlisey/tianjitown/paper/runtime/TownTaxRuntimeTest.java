package org.allivlisey.tianjitown.paper.runtime;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.allivlisey.tianjitown.integrations.jobs.JobsIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownTaxRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final EconomyRepository finance = mock(EconomyRepository.class);
    private final VaultSettlementService settlement = mock(VaultSettlementService.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final Queue<Runnable> worker = new ArrayDeque<>();
    private final Queue<Runnable> delayed = new ArrayDeque<>();
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final UUID townId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final TownTaxRuntime taxes;

    TownTaxRuntimeTest() {
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> worker.add(call.getArgument(0)));
        when(plugin.runMainLater(any(), anyLong()))
                .thenAnswer(call -> delayed.add(call.getArgument(0)));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Member");
        when(settlement.scale()).thenReturn(2);
        when(finance.loadMemberTaxPolicies()).thenReturn(List.of(
                new EconomyRepository.MemberTaxPolicy(townId, playerId, 500)));
        taxes = new TownTaxRuntime(plugin, finance, EconomySettings.load(config), settlement, available);
        taxes.refreshTaxPolicies();
    }

    @Test
    void retriesLedgerWriteWithoutRepeatingExternalSettlement() {
        when(settlement.adjustSettlement(1000)).thenReturn(VaultSettlementService.Result.success("paid"));
        when(finance.recordExternalIncomeTax(any()))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                .thenReturn(null);

        JobsIncomeTaxAdapter.TaxResult result = taxes.acceptJobsIncomeTax(
                new JobsIncomeTaxAdapter.Earning(player, 100.0));
        assertTrue(result.applied());
        assertEquals(95.0, result.netAmount());
        verify(finance, never()).recordExternalIncomeTax(any());

        worker.remove().run();
        assertFalse(available.get());
        assertEquals(1, delayed.size());
        delayed.remove().run();
        worker.remove().run();
        assertTrue(available.get());

        ArgumentCaptor<EconomyRepository.ExternalIncomeTax> posted =
                ArgumentCaptor.forClass(EconomyRepository.ExternalIncomeTax.class);
        verify(finance, times(2)).recordExternalIncomeTax(posted.capture());
        assertEquals(posted.getAllValues().getFirst(), posted.getAllValues().getLast());
        assertEquals(500, posted.getValue().taxMinor());
        assertEquals(townId, posted.getValue().townId());
        verify(settlement, times(1)).adjustSettlement(1000);
        assertTrue(worker.isEmpty());
        assertTrue(delayed.isEmpty());
    }

    @Test
    void disabledTaxAndFailedSettlementLeaveIncomeUnchanged() {
        config.set("economy.tax.enabled", false);
        assertNull(taxes.taxPolicy(playerId));
        assertFalse(taxes.acceptJobsIncomeTax(new JobsIncomeTaxAdapter.Earning(player, 100)).applied());
        verify(settlement, never()).adjustSettlement(anyLong());

        config.set("economy.tax.enabled", true);
        when(settlement.adjustSettlement(1000))
                .thenReturn(VaultSettlementService.Result.failure("unavailable", false, false));
        JobsIncomeTaxAdapter.TaxResult result = taxes.acceptJobsIncomeTax(
                new JobsIncomeTaxAdapter.Earning(player, 100));
        assertFalse(result.applied());
        assertEquals(100.0, result.netAmount());
        assertTrue(worker.isEmpty());
        verify(finance, never()).recordExternalIncomeTax(any());
    }
}

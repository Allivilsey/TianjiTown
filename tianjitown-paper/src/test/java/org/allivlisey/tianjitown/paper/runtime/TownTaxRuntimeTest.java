package org.allivlisey.tianjitown.paper.runtime;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.allivlisey.tianjitown.integrations.jobs.JobsIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.allivlisey.tianjitown.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownTaxRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final EconomyRepository finance = mock(EconomyRepository.class);
    private final VaultPlayerEconomyService wallet = mock(VaultPlayerEconomyService.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final Queue<Runnable> worker = new ArrayDeque<>();
    private final Queue<Runnable> main = new ArrayDeque<>();
    private final Queue<Runnable> delayed = new ArrayDeque<>();
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final UUID townId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final TownTaxRuntime taxes;

    TownTaxRuntimeTest() {
        config.set("economy.tax.subsidy.weekly-limit", "50000.00");
        config.set("economy.tax.subsidy.twelve-hour-limit", "5000.00");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        when(plugin.runAsync(any())).thenAnswer(call -> worker.add(call.getArgument(0)));
        when(plugin.runMainLater(any(), anyLong()))
                .thenAnswer(call -> delayed.add(call.getArgument(0)));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Member");
        when(wallet.scale()).thenReturn(2);
        when(finance.loadMemberTaxPolicies()).thenReturn(List.of(
                new EconomyRepository.MemberTaxPolicy(townId, playerId, 500)));
        when(finance.reserveTaxSubsidy(any(), anyString(), anyLong(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> new EconomyRepository.SubsidyReservation(UUID.randomUUID(),
                        call.getArgument(0), call.getArgument(1), call.getArgument(2), 500,
                        Instant.now(), Instant.now(), "RESERVED"));
        when(finance.prepareIncomeTaxCollection(any())).thenAnswer(call ->
                new EconomyRepository.IncomeTaxCollection(UUID.randomUUID(), call.getArgument(0), "PREPARED", null, 0));
        when(finance.claimIncomeTaxCollection(any())).thenAnswer(call -> {
            var previous = (EconomyRepository.IncomeTaxCollection) call.getArgument(0);
            return new EconomyRepository.IncomeTaxCollection(previous.operationId(), previous.tax(), "ATTEMPTED", null, 1);
        });
        when(finance.finishIncomeTaxCollection(any(), anyString(), anyString())).thenAnswer(call -> {
            var previous = (EconomyRepository.IncomeTaxCollection) call.getArgument(0);
            return new EconomyRepository.IncomeTaxCollection(previous.operationId(), previous.tax(),
                    call.getArgument(1), call.getArgument(2), 2);
        });
        taxes = new TownTaxRuntime(plugin, finance, EconomySettings.load(config), wallet, available);
        taxes.refreshTaxPolicies();
    }

    @Test
    void retriesLedgerWithoutRepeatingPlayerDebit() {
        when(wallet.withdrawPlayer(player, 500)).thenReturn(VaultPlayerEconomyService.Result.success("paid"));
        when(finance.recordExternalIncomeTax(any(), anyLong(), anyLong(), any(), any()))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null)).thenReturn(null);
        acceptIncome("JOBS");
        worker.remove().run();
        assertFalse(available.get());
        delayed.remove().run();
        worker.remove().run();
        assertTrue(available.get());
        verify(wallet).withdrawPlayer(player, 500);
        ArgumentCaptor<EconomyRepository.ExternalIncomeTax> tax = ArgumentCaptor.forClass(EconomyRepository.ExternalIncomeTax.class);
        ArgumentCaptor<Instant> time = ArgumentCaptor.forClass(Instant.class);
        verify(finance, times(2)).recordExternalIncomeTax(tax.capture(), eq(5000000L), eq(500000L), time.capture(), any());
        assertEquals(tax.getAllValues().getFirst(), tax.getAllValues().getLast());
        assertEquals(time.getAllValues().getFirst(), time.getAllValues().getLast());
        assertEquals(500, tax.getValue().taxMinor());
        verify(finance).markIncomeTaxRecorded(any());
        assertTrue(worker.isEmpty());
        assertTrue(main.isEmpty());
    }

    @Test
    void failedOrDisabledTaxNeverCreditsTown() {
        config.set("economy.tax.enabled", false);
        taxes.acceptJobsIncomeTax(new JobsIncomeTaxAdapter.Earning(player, 100));
        assertTrue(worker.isEmpty());
        config.set("economy.tax.enabled", true);
        when(wallet.withdrawPlayer(player, 500)).thenReturn(VaultPlayerEconomyService.Result.failure("no funds", false, false));
        acceptIncome("JOBS");
        assertTrue(worker.isEmpty());
        verify(finance, never()).recordExternalIncomeTax(any(), anyLong(), anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOBS", "GLOBALMARKETPLUS"})
    void incomeTaxAndSubsidyAreRecordedTogether(String source) {
        when(wallet.withdrawPlayer(player, 500)).thenReturn(VaultPlayerEconomyService.Result.success("paid"));
        acceptIncome(source);
        worker.remove().run();
        verify(finance).recordExternalIncomeTax(argThat(t -> t.source().equals(source) && t.taxMinor() == 500),
                eq(5000000L), eq(500000L), any(), any());
        verify(finance, never()).reserveTaxSubsidy(any(), anyString(), anyLong(), anyLong(), anyLong(), any(), any());
        verify(wallet).withdrawPlayer(player, 500);
        assertTrue(main.isEmpty());
    }

    @Test
    void quickShopRetryOnlyTouchesDatabaseAndKeepsBusinessKey() {
        when(finance.recordQuickShopTax(any(), anyLong(), anyLong(), any(), any()))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null)).thenReturn(null);
        taxes.acceptQuickShopTax(quickShopTax());
        worker.remove().run();
        assertFalse(available.get());
        delayed.remove().run();
        worker.remove().run();
        assertTrue(available.get());
        verify(finance, times(2)).recordQuickShopTax(argThat(t -> t.businessKey().equals("quickshop:test")),
                eq(5000000L), eq(500000L), any(), any());
        verify(wallet, never()).withdrawPlayer(any(), anyLong());
        verify(wallet, never()).depositPlayer(any(), anyLong());
        assertTrue(main.isEmpty());
    }

    private org.allivlisey.tianjitown.integrations.quickshop.QuickShopTaxAdapter.SuccessfulTax quickShopTax() {
        return new org.allivlisey.tianjitown.integrations.quickshop.QuickShopTaxAdapter.SuccessfulTax(
                townId, "quickshop:test", 1, "SELLING", playerId, "Member", UUID.randomUUID(),
                10_000, 500, 500, "world");
    }

    private void acceptIncome(String source) {
        if (source.equals("JOBS")) {
            var result = taxes.acceptJobsIncomeTax(new JobsIncomeTaxAdapter.Earning(player, 100));
            assertFalse(result.applied());
            assertEquals(100, result.netAmount());
        } else {
            taxes.acceptGlobalMarketPlusIncomeTax(
                    new GlobalMarketPlusIncomeTaxAdapter.Earning(
                            player, "Member", 100, "gmp:" + UUID.randomUUID()));
        }
        finishCollection();
    }

    private void finishCollection() {
        worker.remove().run(); // Persist context and claim the single debit attempt.
        main.remove().run();   // Vault debit only after the claim is durable.
        worker.remove().run(); // Persist the outcome before starting the subsidy pipeline.
    }

    @Test
    void archivedTownCannotBeReintroducedByStalePolicyRefresh() {
        assertNotNull(taxes.taxPolicy(playerId));
        taxes.townArchived(townId);
        // Repository response can have been read just before the archive committed.
        taxes.refreshTaxPolicies();
        assertNull(taxes.taxPolicy(playerId));
        taxes.acceptJobsIncomeTax(new JobsIncomeTaxAdapter.Earning(player, 100));
        assertTrue(worker.isEmpty());
        verify(finance, never()).prepareIncomeTaxCollection(any());
    }

}

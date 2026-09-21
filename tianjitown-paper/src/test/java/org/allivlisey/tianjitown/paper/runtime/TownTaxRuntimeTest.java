package org.allivlisey.tianjitown.paper.runtime;

import java.time.Instant;
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
    private final VaultSettlementService settlement = mock(VaultSettlementService.class);
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
        when(settlement.scale()).thenReturn(2);
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
        taxes = new TownTaxRuntime(plugin, finance, EconomySettings.load(config), settlement, available);
        taxes.refreshTaxPolicies();
    }

    @Test
    void retriesLedgerWriteWithoutRepeatingExternalSettlement() {
        when(settlement.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.success("paid"));
        when(settlement.adjustSettlement(500)).thenReturn(VaultSettlementService.Result.success("paid"));
        when(finance.recordExternalIncomeTax(any()))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                .thenReturn(null);

        JobsIncomeTaxAdapter.TaxResult result = taxes.acceptJobsIncomeTax(
                new JobsIncomeTaxAdapter.Earning(player, 100.0));
        assertFalse(result.applied());
        assertEquals(100.0, result.netAmount());
        finishCollection();
        verify(finance, never()).recordExternalIncomeTax(any());

        worker.remove().run(); // Reserve on worker.
        main.remove().run(); // Pay subsidy on main thread.
        worker.remove().run(); // Write ledger on worker.
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
        verify(settlement).transferFromPlayer(player, 500);
        verify(settlement).adjustSettlement(500);
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
        when(settlement.transferFromPlayer(player, 500))
                .thenReturn(VaultSettlementService.Result.failure("unavailable", false, false));
        JobsIncomeTaxAdapter.TaxResult result = taxes.acceptJobsIncomeTax(
                new JobsIncomeTaxAdapter.Earning(player, 100));
        assertFalse(result.applied());
        assertEquals(100.0, result.netAmount());
        finishCollection();
        assertTrue(worker.isEmpty());
        verify(finance, never()).recordExternalIncomeTax(any());
    }

    @ParameterizedTest
    @CsvSource({"JOBS,200", "JOBS,0", "GLOBALMARKETPLUS,200", "GLOBALMARKETPLUS,0"})
    void settlesOnlyGrantedSubsidyForBothSources(String source, long granted) {
        when(finance.reserveTaxSubsidy(any(), anyString(), anyLong(), anyLong(), anyLong(), any(), any()))
                .thenAnswer(call -> new EconomyRepository.SubsidyReservation(UUID.randomUUID(),
                        call.getArgument(0), call.getArgument(1), 500, granted,
                        Instant.now(), Instant.now(), "RESERVED"));
        when(settlement.adjustSettlement(anyLong())).thenReturn(VaultSettlementService.Result.success("paid"));
        when(settlement.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.success("paid"));
        acceptIncome(source);
        verify(finance, never()).recordExternalIncomeTax(any());
        worker.remove().run();
        verify(finance).reserveTaxSubsidy(eq(townId), anyString(), eq(500L),
                eq(5_000_000L), eq(500_000L), any(),
                eq(org.allivlisey.tianjitown.core.time.TownTime.ZONE));
        main.remove().run();
        worker.remove().run();
        verify(settlement).transferFromPlayer(player, 500);
        verify(settlement, times(granted == 0 ? 0 : 1)).adjustSettlement(granted);
        verify(settlement, times(granted > 0 ? 1 : 0)).adjustSettlement(anyLong());
        verify(finance).recordExternalIncomeTax(argThat(tax -> tax.source().equals(source)
                && tax.taxMinor() == 500));
        assertTrue(worker.isEmpty());
        assertTrue(main.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOBS", "GLOBALMARKETPLUS"})
    void failedSubsidyRecordsTaxAndLeavesRecoverableReservation(String source) {
        when(settlement.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.success("tax"));
        var reservation = new EconomyRepository.SubsidyReservation(UUID.randomUUID(), townId,
                "retry", 500, 200, Instant.now(), Instant.now(), "RESERVED");
        when(finance.reserveTaxSubsidy(any(), anyString(), anyLong(), anyLong(), anyLong(), any(), any()))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                .thenReturn(reservation);
        when(settlement.adjustSettlement(200))
                .thenReturn(VaultSettlementService.Result.failure("temporarily unavailable", false, false));
        acceptIncome(source);
        worker.remove().run();
        assertFalse(available.get());
        delayed.remove().run();
        worker.remove().run();
        main.remove().run();
        worker.remove().run();
        assertTrue(available.get());
        verify(settlement).transferFromPlayer(player, 500);
        verify(settlement).adjustSettlement(200);
        verify(finance).recordExternalIncomeTaxWithoutSubsidy(any(), eq("temporarily unavailable"));
        verify(finance, never()).cancelTaxSubsidy(anyString(), anyString());
        verify(finance, times(2)).reserveTaxSubsidy(any(), anyString(), anyLong(), anyLong(), anyLong(), any(), any());
        assertTrue(delayed.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ambiguous", "exception", "null"})
    void uncertainSubsidyStillRecordsTaxAndDatabaseRetryNeverRepeatsVault(String outcome) {
        when(settlement.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.success("tax"));
        if (outcome.equals("exception")) when(settlement.adjustSettlement(500))
                .thenThrow(new IllegalStateException("lost response"));
        else if (outcome.equals("ambiguous")) when(settlement.adjustSettlement(500))
                .thenReturn(VaultSettlementService.Result.failure("unknown result", false, true));
        when(finance.recordExternalIncomeTaxWithoutSubsidy(any(), anyString()))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                .thenReturn(null);
        acceptIncome("GLOBALMARKETPLUS");
        worker.remove().run();
        main.remove().run();
        worker.remove().run();
        assertFalse(available.get());
        delayed.remove().run();
        worker.remove().run();
        taxes.flushPendingTaxes();
        assertTrue(available.get());
        verify(settlement).adjustSettlement(500);
        verify(settlement).transferFromPlayer(player, 500);
        verify(finance, times(2)).recordExternalIncomeTaxWithoutSubsidy(any(), anyString());
        verify(finance, never()).recordExternalIncomeTax(any());
        verify(finance, never()).cancelTaxSubsidy(anyString(), anyString());
        assertTrue(delayed.isEmpty());
        assertTrue(main.isEmpty());
        assertTrue(worker.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quickShopTaxSurvivesReservationAndPaymentFailures(boolean reservationFails) {
        var reservation = new EconomyRepository.SubsidyReservation(UUID.randomUUID(), townId,
                "quickshop:test", 500, 500, Instant.now(), Instant.now(), "RESERVED");
        if (reservationFails) {
            when(finance.reserveTaxSubsidy(any(), anyString(), anyLong(), anyLong(), anyLong(), any(), any()))
                    .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                    .thenReturn(reservation);
        }
        when(settlement.adjustSettlement(500)).thenReturn(
                VaultSettlementService.Result.failure("definite failure", false, false));
        taxes.acceptQuickShopTax(quickShopTax());
        worker.remove().run();
        if (reservationFails) {
            assertFalse(available.get());
            assertEquals(1, delayed.size());
            delayed.remove().run();
            worker.remove().run();
        }
        main.remove().run();
        worker.remove().run();
        verify(finance).cancelTaxSubsidy("quickshop:test", "definite failure");
        verify(finance).recordQuickShopTaxWithoutSubsidy(argThat(tax -> tax.taxMinor() == 500),
                eq("definite failure"));
        assertTrue(available.get());
        assertTrue(worker.isEmpty());
        assertTrue(main.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "ambiguous", "exception", "null"})
    void quickShopLedgerRetryNeverRepeatsSubsidyPayment(String outcome) {
        switch (outcome) {
            case "success" -> when(settlement.adjustSettlement(500))
                    .thenReturn(VaultSettlementService.Result.success("paid"));
            case "ambiguous" -> when(settlement.adjustSettlement(500))
                    .thenReturn(VaultSettlementService.Result.failure("unknown", false, true));
            case "exception" -> when(settlement.adjustSettlement(500))
                    .thenThrow(new IllegalStateException("lost response"));
            default -> when(settlement.adjustSettlement(500)).thenReturn(null);
        }
        if (outcome.equals("success")) {
            when(finance.recordQuickShopTax(any()))
                    .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                    .thenReturn(null);
        } else {
            when(finance.recordQuickShopTaxWithoutSubsidy(any(), anyString()))
                    .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                    .thenReturn(null);
        }
        taxes.acceptQuickShopTax(quickShopTax());
        worker.remove().run();
        main.remove().run();
        worker.remove().run();
        assertEquals(1, delayed.size());
        delayed.remove().run();
        worker.remove().run();
        taxes.flushPendingTaxes();
        verify(settlement, times(1)).adjustSettlement(500);
        verify(finance, never()).cancelTaxSubsidy(anyString(), anyString());
        if (outcome.equals("success")) verify(finance, times(2)).recordQuickShopTax(any());
        else verify(finance, times(2)).recordQuickShopTaxWithoutSubsidy(any(), anyString());
        assertTrue(worker.isEmpty());
        assertTrue(main.isEmpty());
        assertTrue(delayed.isEmpty());
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

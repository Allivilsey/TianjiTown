package org.allivlisey.tianjitown.paper.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.IncomeTaxCollection;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownIncomeTaxCollectionRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final EconomyRepository finance = mock(EconomyRepository.class);
    private final VaultSettlementService vault = mock(VaultSettlementService.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final Queue<Runnable> workers = new ArrayDeque<>(), main = new ArrayDeque<>(), delayed = new ArrayDeque<>();
    private final AtomicBoolean available = new AtomicBoolean(true), townActive = new AtomicBoolean(true);
    private final List<IncomeTaxCollection> collected = new ArrayList<>();
    private final EconomyRepository.ExternalIncomeTax tax = new EconomyRepository.ExternalIncomeTax(
            UUID.randomUUID(), "jobs:durable", "JOBS", UUID.randomUUID(), "Member", 10000, 500, 500);
    private final IncomeTaxCollection prepared = state("PREPARED", 0);
    private final TownIncomeTaxCollectionRuntime runtime;

    TownIncomeTaxCollectionRuntimeTest() {
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> workers.add(call.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        when(plugin.runMainLater(any(), anyLong())).thenAnswer(call -> delayed.add(call.getArgument(0)));
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayer(tax.receiverId())).thenReturn(player);
        when(sender.getName()).thenReturn("Admin");
        when(finance.prepareIncomeTaxCollection(tax)).thenReturn(prepared);
        when(finance.claimIncomeTaxCollection(prepared)).thenReturn(withState(prepared, "ATTEMPTED", 1));
        when(finance.finishIncomeTaxCollection(any(), anyString(), anyString())).thenAnswer(call ->
                withState(call.getArgument(0), call.getArgument(1), 2));
        runtime = new TownIncomeTaxCollectionRuntime(plugin, finance, vault, available, collected::add,
                ignored -> townActive.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "partial", "unknown", "exception", "null"})
    void persistsAttemptBeforeDebitAndStoresEveryResult(String outcome) {
        switch (outcome) {
            case "success" -> when(vault.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.success("paid"));
            case "failure" -> when(vault.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.failure("failed", false, false));
            case "partial" -> when(vault.transferFromPlayer(player, 500)).thenReturn(new VaultSettlementService.Result(false, "refund owed", false, true, true));
            case "unknown" -> when(vault.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.failure("unknown", false, true));
            case "exception" -> when(vault.transferFromPlayer(player, 500)).thenThrow(new IllegalStateException("response lost"));
            default -> { }
        }
        runtime.collect(tax, player);
        verifyNoInteractions(vault);
        workers.remove().run();
        verify(finance).claimIncomeTaxCollection(prepared);
        verifyNoInteractions(vault);
        main.remove().run();
        assertTrue(collected.isEmpty());
        workers.remove().run();
        String expected = switch (outcome) {
            case "success" -> "SUCCEEDED";
            case "failure" -> "FAILED";
            case "partial" -> "REFUND_REQUIRED";
            default -> "AMBIGUOUS";
        };
        verify(finance).finishIncomeTaxCollection(any(), eq(expected), anyString());
        verify(vault).transferFromPlayer(player, 500);
        assertEquals(outcome.equals("success") ? 1 : 0, collected.size());
    }

    @Test
    void databaseRetryAndQueueRejectionNeverRepeatDebitOrForwardSubsidy() {
        when(vault.transferFromPlayer(player, 500)).thenReturn(VaultSettlementService.Result.success("paid"));
        doThrow(new EconomyRepository.StorageUnavailableException("offline", null))
                .doReturn(withState(prepared, "SUCCEEDED", 2)).when(finance)
                .finishIncomeTaxCollection(any(), anyString(), anyString());
        var rejected = new TownIncomeTaxCollectionRuntime(plugin, finance, vault, available, value -> {
            collected.add(value);
            throw new java.util.concurrent.RejectedExecutionException("item enqueued but scheduler stopped");
        }, ignored -> true);
        rejected.collect(tax, player);
        workers.remove().run();
        main.remove().run();
        workers.remove().run();
        assertFalse(available.get());
        delayed.remove().run();
        workers.remove().run();
        delayed.remove().run();
        workers.remove().run();
        assertEquals(1, collected.size());
        verify(vault).transferFromPlayer(player, 500);
        // Once downstream accepted the collection it may already be RECORDED. Never re-finish
        // the stale ATTEMPTED claim merely because its downstream scheduler initially rejected.
        verify(finance, times(2)).finishIncomeTaxCollection(any(), anyString(), anyString());
    }

    @Test
    void archiveBetweenClaimAndPaymentPreventsNewDebit() {
        runtime.collect(tax, player);
        workers.remove().run();
        townActive.set(false);
        main.remove().run();
        workers.remove().run();
        verifyNoInteractions(vault);
        verify(finance).finishIncomeTaxCollection(any(), eq("FAILED"), anyString());
        assertTrue(collected.isEmpty());
    }

    @Test
    void startupRecoversOnlyCapturedSuccessfulCollectionsWithoutPayingSubsidy() {
        IncomeTaxCollection previous = withState(prepared, "SUCCEEDED", 2);
        when(finance.pendingIncomeTaxCollections()).thenReturn(List.of(previous));
        runtime.prepareStartupRecovery();
        when(finance.pendingIncomeTaxCollections()).thenReturn(List.of(previous, state("SUCCEEDED", 5)));
        runtime.recoverStartupState();
        workers.remove().run();
        verify(finance).recordExternalIncomeTaxWithoutSubsidy(eq(tax), anyString());
        verify(finance).markIncomeTaxRecorded(previous.operationId());
        verify(finance, times(1)).pendingIncomeTaxCollections();
        verifyNoInteractions(vault);
        assertTrue(collected.isEmpty());
    }

    @Test
    void manualRefundRetriesDatabaseAcknowledgementWithoutSecondRefund() {
        IncomeTaxCollection expected = withState(prepared, "REFUND_REQUIRED", 2);
        IncomeTaxCollection attempted = withState(prepared, "REFUND_ATTEMPTED", 3);
        IncomeTaxCollection refunded = withState(prepared, "REFUNDED", 4);
        when(finance.claimIncomeTaxRefund(eq(expected), isNull(), eq("Admin"))).thenReturn(attempted);
        when(vault.refundDebitedPlayer(player, 500)).thenReturn(VaultSettlementService.Result.success("returned"));
        when(finance.finishIncomeTaxRefund(eq(attempted), eq("REFUNDED"), anyString()))
                .thenThrow(new EconomyRepository.StorageUnavailableException("offline", null)).thenReturn(refunded);
        List<IncomeTaxCollection> callback = new ArrayList<>();
        runtime.refund(sender, expected, callback::add);
        workers.remove().run();
        main.remove().run(); // Claimed result dispatch.
        main.remove().run(); // Refund Vault call.
        workers.remove().run();
        delayed.remove().run();
        workers.remove().run();
        main.remove().run();
        assertEquals(List.of(refunded), callback);
        verify(vault).refundDebitedPlayer(player, 500);
    }

    private IncomeTaxCollection state(String status, long version) {
        return new IncomeTaxCollection(UUID.randomUUID(), tax, status, "detail", version);
    }
    private IncomeTaxCollection withState(IncomeTaxCollection base, String status, long version) {
        return new IncomeTaxCollection(base.operationId(), tax, status, "detail", version);
    }
}

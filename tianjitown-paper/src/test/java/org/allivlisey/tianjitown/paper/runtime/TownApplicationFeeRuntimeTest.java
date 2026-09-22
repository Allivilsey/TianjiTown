package org.allivlisey.tianjitown.paper.runtime;

import java.util.*;
import java.util.logging.Logger;
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.*;
import org.allivlisey.tianjitown.storage.town.*;
import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownApplicationFeeRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final TownRepository repository = mock(TownRepository.class);
    private final VaultPlayerEconomyService wallet = mock(VaultPlayerEconomyService.class);
    private final Player admin = mock(Player.class);
    private final OfflinePlayer applicant = mock(OfflinePlayer.class);
    private final UUID id = UUID.randomUUID(), applicantId = UUID.randomUUID(), actorId = UUID.randomUUID();
    private final Queue<Runnable> work = new ArrayDeque<>(), main = new ArrayDeque<>();
    private final List<ProvisionResult> results = new ArrayList<>();
    private final ProvisionCoordinator coordinator = new ProvisionCoordinator();
    private final ApplicationSnapshot application = mock(ApplicationSnapshot.class);
    private final TownApplicationFeeRuntime runtime;

    TownApplicationFeeRuntimeTest() {
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> work.add(call.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayer(applicantId)).thenReturn(applicant);
        when(admin.getUniqueId()).thenReturn(actorId);
        when(admin.getName()).thenReturn("Admin");
        when(application.id()).thenReturn(id);
        when(application.version()).thenReturn(4L);
        when(repository.findApplication(id)).thenReturn(Optional.of(application));
        runtime = new TownApplicationFeeRuntime(plugin, repository, wallet, coordinator);
    }

    @Test void collectionRecordsPartialDebitBeforeReportingFailureAndNeverCreatesATown() {
        var claim = operation(State.COLLECTING, 0);
        when(repository.claimApplicationFeeCollection(id, 4, 5000, actorId, "Admin")).thenReturn(claim);
        when(wallet.withdrawPlayer(applicant, 5000))
                .thenReturn(new VaultPlayerEconomyService.Result(false, "refund failed", false, true, true));
        when(repository.completeApplicationFeeOperation(claim, Outcome.PLAYER_REFUND_REQUIRED, "refund failed", actorId, "Admin"))
                .thenReturn(operation(State.PLAYER_REFUND_PENDING, 1));
        Runnable continueProvision = mock(Runnable.class);
        runtime.collectWhileLocked(admin, application, 5000, actorId, "Admin", continueProvision, results::add);
        drain();
        var order = inOrder(repository, wallet);
        order.verify(repository).claimApplicationFeeCollection(id, 4, 5000, actorId, "Admin");
        order.verify(wallet).withdrawPlayer(applicant, 5000);
        order.verify(repository).completeApplicationFeeOperation(claim, Outcome.PLAYER_REFUND_REQUIRED, "refund failed", actorId, "Admin");
        verifyNoInteractions(continueProvision);
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());
    }

    @Test void successfulPaymentRetriesOnlyDatabasePersistence() {
        var claim = operation(State.COLLECTING, 0);
        when(repository.claimApplicationFeeCollection(id, 4, 5000, actorId, "Admin")).thenReturn(claim);
        when(wallet.withdrawPlayer(applicant, 5000)).thenReturn(VaultPlayerEconomyService.Result.success("paid"));
        when(repository.completeApplicationFeeOperation(claim, Outcome.SUCCESS, "paid", actorId, "Admin"))
                .thenThrow(new TownRepository.StorageUnavailableException("busy", new IllegalStateException()))
                .thenReturn(operation(State.ESCROWED, 1));
        Runnable continueProvision = mock(Runnable.class);
        runtime.collectWhileLocked(admin, application, 5000, actorId, "Admin", continueProvision, results::add);
        drain();
        verify(wallet, times(1)).withdrawPlayer(applicant, 5000);
        verify(repository, times(2)).completeApplicationFeeOperation(claim, Outcome.SUCCESS, "paid", actorId, "Admin");
        verify(continueProvision).run();
        assertTrue(results.isEmpty());
    }

    @Test void partialCollectionCompensationCreditsPlayerWithoutDebitingSettlementAgain() {
        var claim = operation(State.PLAYER_REFUNDING, 2);
        when(repository.claimApplicationFeeRefund(id, actorId, "Admin")).thenReturn(claim);
        when(wallet.refundDebitedPlayer(applicant, 5000)).thenReturn(VaultPlayerEconomyService.Result.success("refunded"));
        when(repository.completeApplicationFeeOperation(claim, Outcome.SUCCESS, "refunded", actorId, "Admin"))
                .thenReturn(operation(State.UNPAID, 3));
        runtime.refund(admin, id, results::add);
        drain();
        verify(wallet).refundDebitedPlayer(applicant, 5000);
        verify(wallet, never()).depositPlayer(any(), anyLong());
        assertEquals(ProvisionResult.Status.SUCCESS, results.getFirst().status());
        assertTrue(coordinator.tryBegin(id));
    }

    @Test void unexpectedRefundExceptionIsPersistedAsUnknownAndReleasesOnlyThisApplication() {
        var claim = operation(State.REFUNDING, 2);
        when(repository.claimApplicationFeeRefund(id, actorId, "Admin")).thenReturn(claim);
        when(wallet.depositPlayer(applicant, 5000)).thenThrow(new IllegalStateException("unknown debit"));
        when(repository.completeApplicationFeeOperation(eq(claim), eq(Outcome.UNKNOWN), anyString(), eq(actorId), eq("Admin")))
                .thenReturn(operation(State.REFUND_UNKNOWN, 3));
        runtime.refund(admin, id, results::add);
        assertTrue(coordinator.tryBegin(UUID.randomUUID()));
        drain();
        verify(repository).completeApplicationFeeOperation(eq(claim), eq(Outcome.UNKNOWN), anyString(), eq(actorId), eq("Admin"));
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());
        assertTrue(coordinator.tryBegin(id));
    }

    @Test void administratorCannotResolveWhileThisApplicationHasAnInFlightPayment() {
        assertTrue(coordinator.tryBegin(id));
        runtime.resolve(admin, id, 1, Resolution.COLLECTED, "核实", results::add);
        drain();
        assertEquals(ProvisionResult.Status.BUSY, results.getFirst().status());
        verifyNoInteractions(repository, wallet);
    }

    @Test void knownRefundFailureCanBeRetriedButConcurrentRequestCannotPayTwice() {
        var claim = operation(State.REFUNDING, 2);
        when(repository.claimApplicationFeeRefund(id, actorId, "Admin")).thenReturn(claim);
        when(wallet.depositPlayer(applicant, 5000)).thenReturn(VaultPlayerEconomyService.Result.failure("unavailable", false, false));
        when(repository.completeApplicationFeeOperation(claim, Outcome.FAILED, "unavailable", actorId, "Admin"))
                .thenReturn(operation(State.REFUND_PENDING, 3));
        runtime.refund(admin, id, results::add);
        runtime.refund(admin, id, results::add);
        drain();
        verify(wallet, times(1)).depositPlayer(applicant, 5000);
        assertEquals(ProvisionResult.Status.BUSY, results.getFirst().status());
        assertEquals(ProvisionResult.Status.FAILED, results.getLast().status());
    }

    private ApplicationFeeOperation operation(State state, long version) {
        return new ApplicationFeeOperation(id, applicantId, 5000, state, "detail", version);
    }

    private void drain() {
        int steps = 0;
        while (!work.isEmpty() || !main.isEmpty()) {
            assertTrue(++steps < 50);
            if (!work.isEmpty()) work.remove().run();
            if (!main.isEmpty()) main.remove().run();
        }
    }
}

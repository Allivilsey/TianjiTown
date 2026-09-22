package org.allivlisey.tianjitown.paper.runtime;

import java.util.*;
import java.util.logging.Logger;
import org.allivlisey.tianjitown.core.land.*;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.*;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.town.*;
import org.bukkit.Server;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.allivlisey.tianjitown.core.ports.LandProtectionService.*;
import static org.allivlisey.tianjitown.storage.town.TownRepository.RecoveryMode;

class TownProvisionRecoveryTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final TownRepository repository = mock(TownRepository.class);
    private final LandProtectionService land = mock(LandProtectionService.class);
    private final VaultPlayerEconomyService wallet = mock(VaultPlayerEconomyService.class);
    private final Player admin = mock(Player.class);
    private final UUID id = UUID.randomUUID(), actor = UUID.randomUUID(), applicant = UUID.randomUUID();
    private final InitialTerritory territory = new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0));
    private final ApplicationSnapshot recovered = mock(ApplicationSnapshot.class);
    private final OfflinePlayer offline = mock(OfflinePlayer.class);
    private final ProvisionCoordinator coordinator = new ProvisionCoordinator();
    private final Queue<Runnable> work = new ArrayDeque<>(), main = new ArrayDeque<>();
    private final List<ProvisionResult> results = new ArrayList<>();
    private final TownProvisionRecovery recovery;

    TownProvisionRecoveryTest() {
        var messages = mock(PluginMessages.class);
        when(plugin.messages()).thenReturn(messages);
        when(messages.plainText(anyString())).thenAnswer(call -> call.getArgument(0));
        when(messages.plainText(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> work.add(call.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        when(admin.getUniqueId()).thenReturn(actor);
        when(admin.getName()).thenReturn("Admin");
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayer(applicant)).thenReturn(offline);
        var town = mock(TownSnapshot.class);
        when(town.residenceName()).thenReturn("test");
        when(town.territory()).thenReturn(territory);
        when(repository.failedProvision(id)).thenReturn(new TownRepository.Provisioning(id, town, List.of(applicant)));
        when(land.inspect("test", territory, List.of(applicant)))
                .thenReturn(Inspection.healthyCode(ResultCode.PROJECTION_HEALTHY));
        when(land.remove("test", territory)).thenReturn(Result.successCode(ResultCode.PROJECTION_REMOVED));
        when(repository.recoverFailedProvision(eq(id), eq(actor), eq("Admin"), anyString(), any()))
                .thenReturn(recovered);
        when(recovered.id()).thenReturn(id);
        when(recovered.applicantId()).thenReturn(applicant);
        when(recovered.applicationFeeMinor()).thenReturn(500_000L);
        when(wallet.depositPlayer(offline, 500_000)).thenReturn(VaultPlayerEconomyService.Result.success("refunded"));
        when(repository.completeApplicationFeeRefund(id, actor, "Admin", "refunded")).thenReturn(recovered);
        when(repository.findApplication(id)).thenReturn(Optional.of(recovered));
        when(repository.claimApplicationFeeRefund(id, actor, "Admin"))
                .thenReturn(new ApplicationFeeOperation(id, applicant, 500_000, ApplicationFeeOperation.State.REFUNDING, "claim", 0));
        when(repository.completeApplicationFeeOperation(any(), any(), anyString(), eq(actor), eq("Admin")))
                .thenAnswer(call -> new ApplicationFeeOperation(id, applicant, 500_000,
                        call.getArgument(1) == ApplicationFeeOperation.Outcome.SUCCESS
                                ? ApplicationFeeOperation.State.REFUNDED : ApplicationFeeOperation.State.REFUND_UNKNOWN,
                        call.getArgument(2), 1));
        recovery = new TownProvisionRecovery(plugin, repository, land, wallet, coordinator);
    }

    @ParameterizedTest
    @EnumSource(RecoveryMode.class)
    void allThreeRecoveryButtonsHandleHealthyProjectionLeftByTeleportFailure(RecoveryMode mode) {
        recover(mode); drain();
        assertEquals(1, results.size());
        assertEquals(ProvisionResult.Status.SUCCESS, results.getFirst().status());
        var order = inOrder(land, repository, wallet);
        order.verify(land).remove("test", territory);
        order.verify(repository).recoverFailedProvision(eq(id), eq(actor), eq("Admin"), anyString(), eq(mode));
        if (mode == RecoveryMode.UNLOCK_FOR_CHANGES) {
            verifyNoInteractions(wallet);
        } else {
            order.verify(wallet).depositPlayer(offline, 500_000);
            order.verify(repository).completeApplicationFeeOperation(any(), eq(ApplicationFeeOperation.Outcome.SUCCESS),
                    eq("refunded"), eq(actor), eq("Admin"));
        }
        assertTrue(coordinator.tryBegin(id));
    }

    @ParameterizedTest
    @EnumSource(RecoveryMode.class)
    void cleanupFailureKeepsDatabaseAndMoneyUntouchedAndAllowsRetry(RecoveryMode mode) {
        when(land.remove("test", territory)).thenReturn(Result.failureCode(ResultCode.PROJECTION_STILL_PRESENT));
        recover(mode); drain();
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());
        verify(repository, never()).recoverFailedProvision(any(), any(), anyString(), anyString(), any());
        verifyNoInteractions(wallet);
        when(land.remove("test", territory)).thenReturn(Result.successCode(ResultCode.PROJECTION_REMOVED));
        recover(mode); drain();
        assertEquals(ProvisionResult.Status.SUCCESS, results.getLast().status());
    }

    @Test void missingProjectionCanBeRecoveredWithoutRemoval() {
        when(land.inspect("test", territory, List.of(applicant)))
                .thenReturn(Inspection.missingCode(ResultCode.PROJECTION_MISSING));
        recover(RecoveryMode.UNLOCK_FOR_CHANGES); drain();
        assertEquals(ProvisionResult.Status.SUCCESS, results.getFirst().status());
        verify(land, never()).remove(anyString(), any(InitialTerritory.class));
    }

    @Test void unrelatedPlayerResidenceIsNotRemoved() {
        when(land.inspect("test", territory, List.of(applicant)))
                .thenReturn(Inspection.invalidCode(ResultCode.PROJECTION_REMOVE_REJECTED));
        when(land.isControlledProjection("test")).thenReturn(false);
        recover(RecoveryMode.UNLOCK_FOR_CHANGES); drain();
        assertEquals(ProvisionResult.Status.SUCCESS, results.getFirst().status());
        verify(land, never()).remove(anyString(), any(InitialTerritory.class));
    }

    @Test void concurrentRecoveryDoesNotRemoveOrRefundTwice() {
        recover(RecoveryMode.CANCEL_AND_REFUND);
        recover(RecoveryMode.FORCE_CLEANUP);
        drain();
        assertEquals(ProvisionResult.Status.BUSY, results.getFirst().status());
        assertEquals(ProvisionResult.Status.SUCCESS, results.getLast().status());
        verify(land, times(1)).remove("test", territory);
        verify(wallet, times(1)).depositPlayer(offline, 500_000);
    }

    @Test void inFlightApprovalPreventsRecoveryFromTouchingLand() {
        assertTrue(coordinator.tryBegin(id));
        recover(RecoveryMode.FORCE_CLEANUP); drain();
        assertEquals(ProvisionResult.Status.BUSY, results.getFirst().status());
        verifyNoInteractions(land, repository, wallet);
        assertFalse(coordinator.tryBegin(id));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedSchedulingCompletesAndReleasesCoordinator(boolean mainRejected) {
        if (mainRejected) doReturn(false).when(plugin).runMain(any());
        else doReturn(false).when(plugin).runAsync(any());
        recover(RecoveryMode.UNLOCK_FOR_CHANGES); drain();
        assertEquals(1, results.size());
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());
        assertTrue(coordinator.tryBegin(id));
        verifyNoInteractions(land, wallet);
    }

    @Test void unexpectedRefundExceptionReleasesCoordinator() {
        when(wallet.depositPlayer(offline, 500_000)).thenThrow(new IllegalStateException("Vault failed"));
        recover(RecoveryMode.CANCEL_AND_REFUND); drain();
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());
        assertTrue(coordinator.tryBegin(id));
        verify(repository, never()).completeApplicationFeeRefund(any(), any(), anyString(), anyString());
    }

    @Test void cancelledPendingRefundBypassesFailedTownLookupAndRetriesPayment() {
        when(recovered.status()).thenReturn(org.allivlisey.tianjitown.core.application.ApplicationStatus.CANCELLED);
        when(recovered.applicationFeeStatus()).thenReturn(ApplicationSnapshot.FeeStatus.REFUND_PENDING);
        recover(RecoveryMode.CANCEL_AND_REFUND); drain();
        verify(repository, never()).failedProvision(any());
        verifyNoInteractions(land);
        verify(wallet).depositPlayer(offline, 500_000);
        assertEquals(ProvisionResult.Status.SUCCESS, results.getFirst().status());
    }

    private void recover(RecoveryMode mode) {
        recovery.recoverFailedApplication(admin, id, mode, results::add);
    }

    private void drain() {
        int steps = 0;
        while (!work.isEmpty() || !main.isEmpty()) {
            assertTrue(++steps < 30);
            if (!work.isEmpty()) work.remove().run();
            if (!main.isEmpty()) main.remove().run();
        }
    }
}

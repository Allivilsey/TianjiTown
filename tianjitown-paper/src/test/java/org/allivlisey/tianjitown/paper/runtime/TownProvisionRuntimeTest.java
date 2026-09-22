package org.allivlisey.tianjitown.paper.runtime;

import org.allivlisey.tianjitown.core.application.*;
import org.allivlisey.tianjitown.core.land.*;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.integrations.vault.VaultPlayerEconomyService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.land.*;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.storage.town.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownProvisionRuntimeTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final TownRepository repository = mock(TownRepository.class);
    private final LandProtectionService land = mock(LandProtectionService.class);
    private final SitePolicy sites = mock(SitePolicy.class);
    private final VaultPlayerEconomyService wallet = mock(VaultPlayerEconomyService.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final UUID appId = UUID.randomUUID(), townId = UUID.randomUUID(), applicant = UUID.randomUUID();
    private final Queue<Runnable> work = new ArrayDeque<>(), main = new ArrayDeque<>();
    private final List<ProvisionResult> results = new ArrayList<>();
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final InitialTerritory territory = new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0));
    private final TownSnapshot town = mock(TownSnapshot.class);
    private final Runnable refresh = mock(Runnable.class);
    private final TownProvisionRuntime runtime;
    private final ProvisionCoordinator coordinator = new ProvisionCoordinator();

    TownProvisionRuntimeTest() {
        var messages = mock(PluginMessages.class);
        when(plugin.messages()).thenReturn(messages);
        when(messages.plainText(anyString(), anyMap())).thenAnswer(call -> call.getArgument(0));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.runAsync(any())).thenAnswer(call -> work.add(call.getArgument(0)));
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        var offline = mock(OfflinePlayer.class);
        when(server.getOfflinePlayer(applicant)).thenReturn(offline);
        when(offline.getName()).thenReturn("Founder");
        when(town.id()).thenReturn(townId);
        when(town.residenceName()).thenReturn("amu");
        when(town.profile()).thenReturn(new ApplicationText("amu", "amu", "测试简介", List.of("友善交流")));
        when(town.territory()).thenReturn(territory);
        when(repository.findTown(townId)).thenReturn(Optional.of(town));
        when(repository.provisioningForProjection(appId)).thenReturn(new TownRepository.Provisioning(appId, town, List.of(applicant)));
        when(sites.validateReservationEnvironment(territory)).thenReturn(SitePolicy.Validation.success(territory));
        when(land.findNameCollision("amu")).thenReturn(LandProtectionService.Collision.none());
        when(wallet.withdrawPlayer(offline, 100)).thenReturn(VaultPlayerEconomyService.Result.success("paid"));
        when(repository.claimApplicationFeeCollection(eq(appId), anyLong(), eq(100L), any(), anyString()))
                .thenReturn(new ApplicationFeeOperation(appId, applicant, 100, ApplicationFeeOperation.State.COLLECTING, "claim", 0));
        when(repository.completeApplicationFeeOperation(any(), eq(ApplicationFeeOperation.Outcome.SUCCESS), anyString(), any(), anyString()))
                .thenReturn(new ApplicationFeeOperation(appId, applicant, 100, ApplicationFeeOperation.State.ESCROWED, "paid", 1));
        runtime = new TownProvisionRuntime(plugin, repository, land, sites, wallet, available,
                new HashSet<>(), new TownRuntimeTasks(plugin, available), refresh, () -> 100, coordinator);
    }

    private ApplicationSnapshot application(ApplicationStatus status, boolean reserved) {
        return new ApplicationSnapshot(appId, applicant, new ApplicationText("amu", "amu", "测试简介", List.of("友善交流")), status,
                reserved ? territory : null, reserved ? Instant.now().plusSeconds(3600) : null,
                reserved ? null : townId, null, null, List.of(), reserved ? 0 : 100,
                reserved ? ApplicationSnapshot.FeeStatus.UNPAID : ApplicationSnapshot.FeeStatus.ESCROWED,
                reserved ? 1 : 2, Instant.now(), Instant.now(), Instant.now());
    }

    private void drain() {
        int steps = 0;
        while (!work.isEmpty() || !main.isEmpty()) {
            assertTrue(++steps < 30);
            if (!work.isEmpty()) work.remove().run();
            if (!main.isEmpty()) main.remove().run();
        }
    }

    private void approve() {
        runtime.provision(sender, appId, UUID.randomUUID(), "Admin", "管理员调整", "approve:" + appId, results::add);
    }

    @ParameterizedTest
    @EnumSource(value = TownStatus.class, names = {"ACTIVE", "ARCHIVED"})
    void completedApplicationNeverReadsReleasedReservationOrChargesAgain(TownStatus status) {
        when(repository.findApplication(appId)).thenReturn(Optional.of(application(ApplicationStatus.ACTIVE, false)));
        when(town.status()).thenReturn(status);
        approve(); drain();
        assertEquals(1, results.size());
        assertEquals(status == TownStatus.ACTIVE ? ProvisionResult.Status.SUCCESS : ProvisionResult.Status.FAILED,
                results.getFirst().status());
        if (status == TownStatus.ACTIVE) assertFalse(results.getFirst().notifyDecision());
        verifyNoInteractions(sites, wallet, land);
        verify(repository, never()).beginProvision(any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test void concurrentAndAlreadyProvisioningApprovalsReturnBusyWithoutCharging() {
        when(repository.findApplication(appId)).thenReturn(Optional.of(application(ApplicationStatus.APPROVED_PROVISIONING, false)));
        when(town.status()).thenReturn(TownStatus.PROVISIONING);
        approve(); approve(); drain();
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.status() == ProvisionResult.Status.BUSY));
        verifyNoInteractions(sites, wallet, land);
    }

    @Test void retryUsesTownTerritoryAndDoesNotCharge() {
        var failed = application(ApplicationStatus.PROVISION_FAILED, false);
        when(repository.findApplication(appId)).thenReturn(Optional.of(failed));
        when(town.status()).thenReturn(TownStatus.PROVISIONING);
        when(repository.beginProvision(any(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new TownRepository.Provisioning(appId, town, List.of(applicant)));
        when(land.create("amu", territory, List.of(applicant))).thenReturn(
                LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROJECTION_CREATE_REJECTED));
        when(repository.finishProvision(eq(appId), eq(false), any())).thenReturn(failed);
        approve(); drain();
        verify(sites, times(2)).validateReservationEnvironment(territory);
        verifyNoInteractions(wallet);
        assertEquals(1, results.size());
    }

    @Test void normalFirstApprovalCommitsOnceAndPostCommitUiFailureDoesNotChangeOutcome() {
        var submitted = application(ApplicationStatus.SUBMITTED, true);
        var completed = application(ApplicationStatus.ACTIVE, false);
        when(repository.findApplication(appId)).thenReturn(Optional.of(submitted));
        when(town.status()).thenReturn(TownStatus.PROVISIONING);
        when(repository.beginProvision(any(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new TownRepository.Provisioning(appId, town, List.of(applicant)));
        var ok = LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY,
                Map.of("residence", "amu"));
        when(land.create("amu", territory, List.of(applicant))).thenReturn(ok);
        when(land.setMessages(anyString(), anyString(), anyString())).thenReturn(ok);
        var world = mock(World.class);
        when(plugin.getServer().getWorld(territory.center().worldId())).thenReturn(world);
        when(world.getUID()).thenReturn(territory.center().worldId());
        when(world.getName()).thenReturn("world");
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(-64);
        var block = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.isPassable()).thenReturn(true);
        when(block.getRelative(anyInt(), anyInt(), anyInt())).thenReturn(block);
        var solid = mock(Material.class);
        when(solid.isSolid()).thenReturn(true);
        when(block.getType()).thenReturn(solid);
        when(land.setTeleportPoint(anyString(), any(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat())).thenReturn(ok);
        when(repository.finishProvision(eq(appId), eq(true), anyString())).thenReturn(completed);
        doThrow(new IllegalStateException("post-commit cache failure")).when(refresh).run();
        runtime.provision(sender, appId, UUID.randomUUID(), "Admin", "管理员调整", "first", result -> {
            results.add(result); throw new IllegalStateException("UI unavailable");
        });
        drain();
        assertEquals(1, results.size());
        assertEquals(ProvisionResult.Status.SUCCESS, results.getFirst().status(), results.getFirst().toString());
        verify(wallet).withdrawPlayer(any(), eq(100L));
        verify(repository).finishProvision(eq(appId), eq(true), anyString());
        verify(repository, never()).finishProvision(eq(appId), eq(false), anyString());
        verify(plugin.messages(), never()).send(eq(sender), eq("chat.runtime.operation-failed"), anyMap());
        when(repository.findApplication(appId)).thenReturn(Optional.of(completed));
        when(town.status()).thenReturn(TownStatus.ACTIVE);
        approve(); drain();
        verify(wallet, times(1)).withdrawPlayer(any(), anyLong());
        assertFalse(results.getLast().notifyDecision());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "world", "height", "space", "set-failure", "set-exception", "cleanup-failure", "cleanup-exception"})
    void everyTeleportFailureAttemptsCleanupAndLeavesRecoverableFailedApplication(String failure) {
        var failed = application(ApplicationStatus.PROVISION_FAILED, false);
        when(repository.findApplication(appId)).thenReturn(Optional.of(failed));
        when(town.status()).thenReturn(TownStatus.PROVISIONING);
        when(repository.beginProvision(any(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new TownRepository.Provisioning(appId, town, List.of(applicant)));
        var ok = LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
        when(land.create("amu", territory, List.of(applicant))).thenReturn(ok);
        when(land.setMessages(anyString(), anyString(), anyString())).thenReturn(ok);
        when(land.remove("amu", territory)).thenReturn(ok);
        if (!failure.equals("world")) {
            var world = mock(World.class);
            when(plugin.getServer().getWorld(territory.center().worldId())).thenReturn(world);
            when(world.getUID()).thenReturn(territory.center().worldId());
            when(world.getName()).thenReturn("world");
            when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(failure.equals("height") ? 319 : 64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.getMinHeight()).thenReturn(-64);
            var block = mock(Block.class);
            when(world.getBlockAt(any(Location.class))).thenReturn(block);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            when(block.isPassable()).thenReturn(!failure.equals("space"));
            when(block.getRelative(anyInt(), anyInt(), anyInt())).thenReturn(block);
            var solid = mock(Material.class);
            when(solid.isSolid()).thenReturn(true);
            when(block.getType()).thenReturn(solid);
        }
        when(land.setTeleportPoint(anyString(), any(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenReturn(LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROVISION_OPERATION_FAILED));
        if (failure.equals("set-exception")) {
            when(land.setTeleportPoint(anyString(), any(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                    .thenThrow(new IllegalStateException("teleport API failed"));
        }
        if (failure.equals("cleanup-failure")) when(land.remove("amu", territory))
                .thenReturn(LandProtectionService.Result.failureCode(LandProtectionService.ResultCode.PROJECTION_STILL_PRESENT));
        if (failure.equals("cleanup-exception")) when(land.remove("amu", territory))
                .thenThrow(new IllegalStateException("cleanup API failed"));
        when(repository.finishProvision(eq(appId), eq(false), anyString())).thenReturn(failed);
        approve(); drain();
        verify(land).remove("amu", territory);
        verify(repository).finishProvision(eq(appId), eq(false), anyString());
        verifyNoInteractions(wallet);
        assertEquals(1, results.size());
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());
        assertTrue(coordinator.tryBegin(appId));
    }

    @Test void retrySucceedsAfterUnsafeLandingSpaceIsRepairedWithoutChargingAgain() {
        var failed = application(ApplicationStatus.PROVISION_FAILED, false);
        when(repository.findApplication(appId)).thenReturn(Optional.of(failed));
        when(town.status()).thenReturn(TownStatus.PROVISIONING);
        when(repository.beginProvision(any(), any(), any(), any(), any(), anyLong(), any()))
                .thenReturn(new TownRepository.Provisioning(appId, town, List.of(applicant)));
        var ok = LandProtectionService.Result.successCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY);
        when(land.findNameCollision("amu")).thenReturn(new LandProtectionService.Collision(true, "amu"));
        when(land.inspect("amu", territory, List.of(applicant)))
                .thenReturn(LandProtectionService.Inspection.healthyCode(LandProtectionService.ResultCode.PROJECTION_HEALTHY));
        when(land.create("amu", territory, List.of(applicant))).thenReturn(ok);
        when(land.setMessages(anyString(), anyString(), anyString())).thenReturn(ok);
        when(land.remove("amu", territory)).thenReturn(ok);
        var world = mock(World.class);
        when(plugin.getServer().getWorld(territory.center().worldId())).thenReturn(world);
        when(world.getUID()).thenReturn(territory.center().worldId());
        when(world.getName()).thenReturn("world");
        when(world.getHighestBlockYAt(anyInt(), anyInt())).thenReturn(64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(-64);
        var block = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.getRelative(anyInt(), anyInt(), anyInt())).thenReturn(block);
        var material = mock(Material.class);
        when(material.isSolid()).thenReturn(true);
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(false);
        when(repository.finishProvision(eq(appId), eq(false), anyString())).thenReturn(failed);
        approve(); drain();
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());

        when(block.isPassable()).thenReturn(true);
        when(land.setTeleportPoint(anyString(), any(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenReturn(ok);
        when(repository.finishProvision(eq(appId), eq(true), anyString()))
                .thenReturn(application(ApplicationStatus.ACTIVE, false));
        approve(); drain();
        assertEquals(ProvisionResult.Status.SUCCESS, results.getLast().status());
        verify(land, times(1)).remove("amu", territory);
        verify(land, times(1)).setTeleportPoint(eq("amu"), any(), eq("world"),
                eq(8.5), eq(65.0), eq(8.5), eq(0.0F), eq(0.0F));
        verifyNoInteractions(wallet);
    }

    @Test void recoveryInFlightPreventsApprovalFromTouchingDatabaseOrLand() {
        assertTrue(coordinator.tryBegin(appId));
        approve(); drain();
        assertEquals(ProvisionResult.Status.BUSY, results.getFirst().status());
        verifyNoInteractions(repository, sites, wallet, land);
        assertFalse(coordinator.tryBegin(appId));
    }

    @Test void archivedTownDetectedBeforeQueuedProjectionNeverTouchesResidence() {
        var failed = application(ApplicationStatus.PROVISION_FAILED, false);
        when(repository.findApplication(appId)).thenReturn(Optional.of(failed));
        when(town.status()).thenReturn(TownStatus.PROVISIONING);
        when(repository.provisioningForProjection(appId)).thenThrow(new TownRepository.ConflictException("小镇已归档"));
        approve(); drain();
        verify(land, never()).create(anyString(), any(), anyList());
        verify(land, never()).remove(anyString(), any(InitialTerritory.class));
        assertEquals(ProvisionResult.Status.FAILED, results.getFirst().status());
        assertTrue(coordinator.tryBegin(appId));
    }
}

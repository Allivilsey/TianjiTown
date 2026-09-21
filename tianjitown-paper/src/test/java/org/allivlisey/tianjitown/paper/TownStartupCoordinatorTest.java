package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.bonus.TownBonusRuntime;
import org.allivlisey.tianjitown.integrations.residence.ResidenceLandProtectionService;
import org.allivlisey.tianjitown.paper.runtime.GateStatus;
import org.allivlisey.tianjitown.paper.config.RuntimeConfigurationValidator.DatabaseSettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.ui.TownUiController;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.BukkitLampConfig;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownStartupCoordinatorTest {
    @TempDir Path directory;
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final BukkitScheduler bukkit = mock(BukkitScheduler.class);
    private TownStartupCoordinator startup;

    @BeforeEach
    void setup() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(bukkit);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        startup = new TownStartupCoordinator(plugin);
        when(plugin.messages()).thenAnswer(ignored -> startup.messages());
    }

    @AfterEach
    void shutdown() {
        startup.onDisable();
    }

    @Test
    void closesPoolWhoseActivationWasQueuedWhenPluginUnloaded() {
        long generation = startup.scheduler.start();
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        startup.messages = new PluginMessages(directory.toFile());
        try (var pools = mockConstruction(DatabaseGate.class, (pool, context) ->
                when(pool.verifyAndMigrate()).thenReturn(DatabaseGate.HealthResult.success("1.1")))) {
            startup.checkDatabase(List.of(), new DatabaseSettings(5000, 5000), generation);
            DatabaseGate candidate = pools.constructed().getFirst();
            ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
            verify(bukkit).runTask(eq(plugin), callback.capture());

            startup.onDisable();
            verify(candidate).close();
            startup.scheduler.start();
            callback.getValue().run();
            assertNull(startup.townRuntime());
            assertNull(startup.databaseGate);
            startup.onDisable();
            verify(candidate, times(1)).close();
        }
    }

    @Test
    void databaseVerificationFailureReleasesPoolBeforeUnload() {
        long generation = startup.scheduler.start();
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        startup.messages = new PluginMessages(directory.toFile());
        try (var pools = mockConstruction(DatabaseGate.class, (pool, context) ->
                when(pool.verifyAndMigrate()).thenThrow(new LinkageError("database library unavailable")))) {
            startup.checkDatabase(List.of(), new DatabaseSettings(5000, 5000), generation);
            DatabaseGate candidate = pools.constructed().getFirst();
            verify(candidate).close();
            assertEquals(GateStatus.State.LOCKED, startup.gateStatus().state());
            verifyNoInteractions(bukkit);
            startup.onDisable();
            verify(candidate, times(1)).close();
        }
    }

    @Test
    void rejectsAndClosesPoolFromPreviousEnableCycle() {
        long generation = startup.scheduler.start();
        startup.onDisable();
        long nextGeneration = startup.scheduler.start();
        DatabaseGate stale = mock(DatabaseGate.class);
        DatabaseGate current = mock(DatabaseGate.class);
        assertFalse(startup.trackDatabaseCandidate(stale, generation));
        verify(stale).close();
        assertTrue(startup.trackDatabaseCandidate(current, nextGeneration));
        verifyNoInteractions(current);
        startup.onDisable();
        verify(current).close();
    }

    @Test
    void degradedBusinessDiagnosticsActivateComponentsWithoutClosingDatabase() throws Exception {
        try (var registrars = mockConstruction(TownComponentRegistrar.class)) {
            startup = new TownStartupCoordinator(plugin);
            startup.messages = new PluginMessages(directory.toFile());
            long generation = startup.scheduler.start();
            DatabaseGate candidate = mock(DatabaseGate.class);
            assertTrue(startup.trackDatabaseCandidate(candidate, generation));
            TownRuntime runtime = mock(TownRuntime.class, RETURNS_DEEP_STUBS);

            completeActivation(candidate, generation, runtime,
                    new TownBonusRuntime.DiagnosticResult(false, Instant.now(), "待处理资金和扩张", null, true));

            verify(registrars.constructed().getFirst()).activateRuntimeComponents(eq(candidate),
                    eq(List.of()), eq("database ok"), eq(generation), eq(runtime), any(), any(),
                    any(), eq(Set.of()), eq(Set.of()));
            verify(candidate, never()).close();
            assertNotEquals(GateStatus.State.LOCKED, startup.gateStatus().state());
        }
    }

    @Test
    void failedIntegrityDiagnosticsCloseDatabaseBeforeAnyBusinessComponentStarts() throws Exception {
        try (var registrars = mockConstruction(TownComponentRegistrar.class)) {
            startup = new TownStartupCoordinator(plugin);
            startup.messages = new PluginMessages(directory.toFile());
            long generation = startup.scheduler.start();
            DatabaseGate candidate = mock(DatabaseGate.class);
            assertTrue(startup.trackDatabaseCandidate(candidate, generation));
            TownRuntime runtime = mock(TownRuntime.class, RETURNS_DEEP_STUBS);

            completeActivation(candidate, generation, runtime,
                    new TownBonusRuntime.DiagnosticResult(false, Instant.now(), "SQLite integrity failure", null, false));

            verifyNoInteractions(registrars.constructed().getFirst());
            verify(candidate).close();
            assertEquals(GateStatus.State.LOCKED, startup.gateStatus().state());
            assertNull(startup.townRuntime());
            verify(runtime, never()).recoverStartupState();
        }
    }

    private void completeActivation(DatabaseGate candidate, long generation, TownRuntime runtime,
                                    TownBonusRuntime.DiagnosticResult diagnostic) throws Exception {
        var method = TownStartupCoordinator.class.getDeclaredMethod("completeRuntimeActivation",
                DatabaseGate.class, List.class, String.class, long.class, TownRuntime.class,
                TownActions.class, TownUiController.class, ResidenceLandProtectionService.class,
                Set.class, Set.class, TownBonusRuntime.DiagnosticResult.class);
        method.setAccessible(true);
        method.invoke(startup, candidate, List.of(), "database ok", generation, runtime,
                mock(TownActions.class), mock(TownUiController.class),
                mock(ResidenceLandProtectionService.class), Set.of(), Set.of(), diagnostic);
    }

    @Test
    void continuesUnloadingAfterUiAndPoolCleanupFailures() {
        long generation = startup.scheduler.start();
        TownUiController ui = mock(TownUiController.class);
        TownRuntime runtime = mock(TownRuntime.class, RETURNS_DEEP_STUBS);
        DatabaseGate candidate = mock(DatabaseGate.class);
        DatabaseGate active = mock(DatabaseGate.class);
        startup.townUi = ui;
        startup.townRuntime = runtime;
        startup.databaseGate = active;
        assertTrue(startup.trackDatabaseCandidate(candidate, generation));
        doThrow(new IllegalStateException("UI unavailable")).when(ui).close();
        doThrow(new IllegalStateException("pool unavailable")).when(candidate).close();
        HandlerList handlers = new HandlerList();
        handlers.register(new RegisteredListener(mock(Listener.class), (listener, event) -> {},
                EventPriority.NORMAL, plugin, false));

        assertDoesNotThrow(startup::onDisable);
        assertEquals(0, handlers.getRegisteredListeners().length);
        verify(bukkit).cancelTasks(plugin);
        verify(runtime.territoryPreviews()).clearPreviews();
        verify(runtime.bonuses()).clearAll();
        verify(runtime.buffs()).clearAll();
        verify(active).close();
        assertNull(startup.databaseGate);
        assertNull(startup.townRuntime());
        assertNull(startup.townUi());
        assertFalse(startup.scheduler.runAsync(() -> fail("disabled worker")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reEnableReadsChangedConfigurationAndSurvivesCommandCleanupFailure() throws Exception {
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "database:\n  file: ''\n");
        doAnswer(ignored -> {
            when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(config.toFile()));
            return null;
        }).when(plugin).reloadConfig();
        Lamp.Builder<BukkitCommandActor> builder = mock(Lamp.Builder.class, RETURNS_SELF);
        Lamp<BukkitCommandActor> lamp = mock(Lamp.class);
        when(builder.build()).thenReturn(lamp);
        try (var factory = mockStatic(BukkitLamp.class)) {
            factory.when(() -> BukkitLamp.builder(any(BukkitLampConfig.class))).thenReturn(builder);
            startup.onEnable();
            assertEquals(GateStatus.State.LOCKED, startup.gateStatus().state());
            assertEquals("", plugin.getConfig().getString("database.file"));
            doThrow(new LinkageError("command library unavailable")).when(lamp).unregisterAllCommands();
            assertDoesNotThrow(startup::onDisable);

            Files.writeString(config, "database:\n  file: changed.db\n  connection-timeout-ms: -1\n");
            startup.onEnable();
            assertEquals("changed.db", plugin.getConfig().getString("database.file"));
            assertEquals(GateStatus.State.LOCKED, startup.gateStatus().state());
            verify(plugin, times(2)).reloadConfig();
            assertDoesNotThrow(startup::onDisable);
            verify(lamp, times(2)).unregisterAllCommands();
        }
    }
}

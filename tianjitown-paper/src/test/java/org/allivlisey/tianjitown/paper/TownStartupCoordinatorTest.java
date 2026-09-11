package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
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
                when(pool.verifyAndMigrate()).thenReturn(DatabaseGate.HealthResult.success("1.0")))) {
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

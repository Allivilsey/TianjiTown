package org.allivlisey.tianjitown.paper;
import org.allivlisey.tianjitown.paper.bonus.TownBonusRuntime;
import org.allivlisey.tianjitown.paper.command.TownAdminCommand;
import org.allivlisey.tianjitown.paper.command.TownAdminLamp;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.BukkitLampConfig;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import org.allivlisey.tianjitown.paper.command.TownAdminTabCompleter;
import org.allivlisey.tianjitown.paper.config.ConfigurationValues;
import org.allivlisey.tianjitown.paper.config.RuntimeConfigurationValidator;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.GateStatus;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.ui.TownUiController;

import org.allivlisey.tianjitown.core.ports.WorldBoundaryService;
import org.allivlisey.tianjitown.integrations.residence.ResidenceLandProtectionService;
import org.allivlisey.tianjitown.integrations.vault.VaultEconomyProbe;
import org.allivlisey.tianjitown.integrations.worldborder.WorldBorderBoundaryService;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

final class TownStartupCoordinator {
    private final TianjiTownPlugin plugin;
    final LifecycleTaskScheduler scheduler;
    private final TownComponentRegistrar registrar;

    TownStartupCoordinator(TianjiTownPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = new LifecycleTaskScheduler(plugin, this);
        this.registrar = new TownComponentRegistrar(plugin, this);
    }
    static final String BOOTSTRAP_GATE_DETAIL = "TT-PLUGIN-NOT-STARTED";
    static final String BOOTSTRAP_MESSAGES_NOT_LOADED = "TT-MESSAGES-NOT-LOADED";
    static final String STARTUP_CHECKING = "diagnostic.lifecycle.startup-checking";
    static final String CONFIGURATION_VALIDATION_PASSED =
            "diagnostic.lifecycle.configuration-validation-passed";
    static final String CONFIGURATION_VALIDATION_FAILED =
            "diagnostic.lifecycle.configuration-validation-failed";
    static final String BUSINESS_CONFIG_GATE_FAILED =
            "diagnostic.lifecycle.business-config-gate-failed";
    static final String SYNCHRONOUS_GATE_FAILED =
            "diagnostic.lifecycle.synchronous-gate-failed";
    static final String DEPENDENCY_MISSING =
            "diagnostic.lifecycle.dependency-missing";
    static final String DEPENDENCY_DISABLED =
            "diagnostic.lifecycle.dependency-disabled";
    static final String DEPENDENCY_PROBE_FAILURE =
            "diagnostic.lifecycle.dependency-probe-failure";
    static final String VAULT_ECONOMY_UNAVAILABLE =
            "diagnostic.lifecycle.vault-economy-unavailable";
    static final String DATABASE_GATE_FAILED =
            "diagnostic.lifecycle.database-gate-failed";
    static final String DATABASE_GATE_LOCKED =
            "diagnostic.lifecycle.database-gate-locked";
    static final String DATABASE_CONFIG_INVALID =
            "diagnostic.lifecycle.database-config-invalid";
    static final String DATABASE_CONFIG_GATE_FAILED =
            "diagnostic.lifecycle.database-config-gate-failed";
    static final String STARTUP_DIAGNOSTIC_PASSED =
            "diagnostic.lifecycle.startup-diagnostic-passed";
    static final String STARTUP_DIAGNOSTIC_FAILED =
            "diagnostic.lifecycle.startup-diagnostic-failed";
    static final String STARTUP_DIAGNOSTIC_GATE_FAILED =
            "diagnostic.lifecycle.startup-diagnostic-gate-failed";
    static final String RUNTIME_ACTIVATION_FAILURE =
            "diagnostic.lifecycle.runtime-activation-failure";
    static final String RUNTIME_GATE_FAILED =
            "diagnostic.lifecycle.runtime-gate-failed";
    static final String RUNTIME_INITIALIZATION_FAILURE =
            "diagnostic.lifecycle.runtime-initialization-failure";
    static final String WORLD_BORDER_READY =
            "diagnostic.lifecycle.world-border-ready";
    static final String DIALOG_UI_READY =
            "diagnostic.lifecycle.dialog-ui-ready";
    static final String RUNTIME_FEATURES_READY =
            "diagnostic.lifecycle.runtime-features-ready";
    static final String WORLD_BORDER_API_LOAD_FAILURE =
            "diagnostic.world-border.api-load-failure";
    static final String DATABASE_FILE_REQUIRED =
            "validation.runtime-configuration.database-file-required";
    static final String DATABASE_FILE_PATH_INVALID =
            "validation.runtime-configuration.database-file-path-invalid";
    static final String DATABASE_DIRECTORY_CREATE_FAILURE =
            "validation.runtime-configuration.database-directory-create-failure";
    static final String NEGATIVE_DELAY = "diagnostic.scheduler.negative-delay";
    static final String UI_CLOSE_FAILURE = "log.lifecycle.ui-close-failure";
    static final String ASYNC_SHUTDOWN_TIMEOUT = "log.lifecycle.async-shutdown-timeout";
    static final String BEACON_CLEANUP_FAILURE = "log.lifecycle.beacon-cleanup-failure";
    static final String BUFF_CLEANUP_FAILURE = "log.lifecycle.buff-cleanup-failure";
    static final String DATABASE_CLOSE_FAILURE = "log.lifecycle.database-close-failure";
    static final String RESIDENCE_NAMES_LOAD_FAILURE =
            "log.lifecycle.residence-names-load-failure";
    static final String RUNTIME_STARTED = "log.lifecycle.runtime-started";
    static final String RUNTIME_LOCKED = "log.lifecycle.runtime-locked";
    static final String ASYNC_TASK_FAILURE = "log.scheduler.async-task-failure";
    static final String MAIN_THREAD_CALLBACK_FAILURE =
            "log.scheduler.main-thread-callback-failure";
    static final String MAIN_THREAD_CALLBACK_SUBMIT_FAILURE =
            "log.scheduler.main-thread-callback-submit-failure";
    static final String PERIODIC_SQLITE_RECOVERY_FAILURE =
            "log.scheduler.periodic.sqlite-recovery-failure";
    static final String PERIODIC_RESIDENCE_RECONCILIATION_FAILURE =
            "log.scheduler.periodic.residence-reconciliation-failure";
    static final String PERIODIC_VOTE_SETTLEMENT_FAILURE =
            "log.scheduler.periodic.vote-settlement-failure";
    static final String PERIODIC_SETTLEMENT_RECONCILIATION_FAILURE =
            "log.scheduler.periodic.settlement-reconciliation-failure";
    static final String PERIODIC_TERRITORY_BONUS_INDEX_FAILURE =
            "log.scheduler.periodic.territory-bonus-index-refresh-failure";
    static final String PERIODIC_BEACON_EFFECT_FAILURE =
            "log.scheduler.periodic.beacon-effect-refresh-failure";
    static final String PERIODIC_REFUND_COUNTER_FAILURE =
            "log.scheduler.periodic.refund-counter-cleanup-failure";
    final AtomicReference<GateStatus> gateStatus = new AtomicReference<>(
            new GateStatus(GateStatus.State.CHECKING, List.of(BOOTSTRAP_GATE_DETAIL)));
    volatile DatabaseGate databaseGate;
    volatile TownRuntime townRuntime;
    volatile TownActions townActions;
    volatile TownUiController townUi;
    volatile TownAdminTabCompleter townAdminTabCompleter;
    volatile PluginMessages messages;
    private Lamp<BukkitCommandActor> commandLamp;

    public void onEnable() {
        long generation = scheduler.start();
        plugin.saveDefaultConfig();
        plugin.saveResource("messages.yml", false);
        messages = new PluginMessages(plugin.getDataFolder());
        gateStatus.set(new GateStatus(GateStatus.State.CHECKING,
                List.of(messages().plainText(STARTUP_CHECKING))));
        TownAdminTabCompleter completer = new TownAdminTabCompleter(plugin);
        townAdminTabCompleter = completer;
        // Existing completion reads Bukkit state and must remain on the server thread.
        commandLamp = TownAdminLamp.configure(BukkitLamp.builder(
                BukkitLampConfig.<BukkitCommandActor>builder(plugin)
                        .disableBrigadier().disableAsyncCompletion().build()), plugin, completer).build();
        new TownAdminCommand(plugin).register(commandLamp);

        List<String> synchronousChecks = new ArrayList<>();
        RuntimeConfigurationValidator.DatabaseSettings databaseSettings;
        try {
            databaseSettings = RuntimeConfigurationValidator.validate(plugin.getConfig(), messages());
            synchronousChecks.add(messages().plainText(CONFIGURATION_VALIDATION_PASSED));
        } catch (RuntimeException exception) {
            synchronousChecks.add(messages().plainText(CONFIGURATION_VALIDATION_FAILED,
                    Map.of("detail", safeMessage(exception))));
            lock(messages().plainText(BUSINESS_CONFIG_GATE_FAILED), synchronousChecks);
            return;
        }
        boolean dependenciesHealthy = checkDependencies(synchronousChecks);
        gateStatus.set(new GateStatus(GateStatus.State.CHECKING, synchronousChecks));

        if (!dependenciesHealthy) {
            lock(messages().plainText(SYNCHRONOUS_GATE_FAILED), synchronousChecks);
            return;
        }
        scheduler.runAsync(() -> checkDatabase(synchronousChecks, databaseSettings, generation));
    }

    public void onDisable() {
        scheduler.stopAccepting();
        if (commandLamp != null) {
            commandLamp.unregisterAllCommands();
            commandLamp = null;
        }
        TownUiController ui = townUi;
        if (ui != null) {
            try {
                ui.close();
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning(plainText(UI_CLOSE_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
        }
        TownAdminTabCompleter completer = townAdminTabCompleter;
        if (completer != null) {
            completer.stop();
        }
        scheduler.shutdown();
        TownRuntime runtime = townRuntime;
        cleanupRuntimeEffects(runtime, failure -> plugin.getLogger().warning(
                plainText(failure.key(), Map.of("detail", safeMessage(failure.cause())))));
        DatabaseGate gate = databaseGate;
        if (gate != null) {
            try {
                gate.close();
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plainText(DATABASE_CLOSE_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            } finally {
                databaseGate = null;
            }
        }
        townRuntime = null;
        townActions = null;
        townUi = null;
        townAdminTabCompleter = null;
        messages = null;
    }

    public GateStatus gateStatus() {
        return gateStatus.get();
    }

    public TownRuntime townRuntime() {
        return townRuntime;
    }

    public TownUiController townUi() {
        return townUi;
    }

    public TownActions townActions() {
        return townActions;
    }

    public PluginMessages messages() {
        PluginMessages current = messages;
        if (current == null) {
            throw new IllegalStateException(BOOTSTRAP_MESSAGES_NOT_LOADED);
        }
        return current;
    }

    public void reloadMessages() {
        messages().reload();
    }

    String plainText(String key) {
        return plainText(key, Map.of());
    }

    String plainText(String key, Map<String, ?> placeholders) {
        PluginMessages current = messages;
        if (current == null) {
            return "TT-MESSAGES-UNAVAILABLE: " + key;
        }
        return current.plainText(key, placeholders);
    }

    private boolean checkDependencies(List<String> details) {
        boolean healthy = true;
        details.add("INFO Minecraft " + plugin.getServer().getMinecraftVersion()
                + " / Java " + Runtime.version().feature());
        for (String name : List.of("Residence", "Vault", "XConomy", "QuickShop-Hikari",
                "Jobs", "GlobalMarketPlus", "WorldBorder")) {
            try {
                Plugin dependency = plugin.getServer().getPluginManager().getPlugin(name);
                if (dependency == null) {
                    details.add(messages().plainText(DEPENDENCY_MISSING,
                            Map.of("dependency", safeText(name))));
                    healthy = false;
                } else if (!dependency.isEnabled()) {
                    details.add(messages().plainText(DEPENDENCY_DISABLED,
                            Map.of("dependency", safeText(name),
                                    "version", safeText(dependency.getPluginMeta().getVersion()))));
                    healthy = false;
                } else {
                    details.add("OK " + name + " " + dependency.getPluginMeta().getVersion());
                }
            } catch (RuntimeException | LinkageError exception) {
                details.add(messages().plainText(DEPENDENCY_PROBE_FAILURE,
                        Map.of("dependency", safeText(name),
                                "detail", safeText(safeMessage(exception)))));
                healthy = false;
            }
        }
        boolean economyHealthy = false;
        if (plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            VaultEconomyProbe.Result economy = new VaultEconomyProbe(plugin.getServer(),
                    messages()::plainText).verify();
            details.add((economy.healthy() ? "OK " : "FAIL ") + "Vault Economy provider="
                    + economy.provider() + " (" + economy.message() + ")");
            economyHealthy = economy.healthy();
        } else {
            details.add(messages().plainText(VAULT_ECONOMY_UNAVAILABLE));
        }
        return healthy && economyHealthy;
    }

    private void checkDatabase(List<String> previousChecks,
                               RuntimeConfigurationValidator.DatabaseSettings settings,
                               long generation) {
        List<String> details = new ArrayList<>(previousChecks);
        try {
            DatabaseConfig config = new DatabaseConfig(
                    resolveDatabaseUrl(),
                    Duration.ofMillis(settings.connectionTimeoutMillis()),
                    Duration.ofMillis(settings.busyTimeoutMillis()));
            DatabaseGate candidate = new DatabaseGate(config);
            DatabaseGate.HealthResult result = candidate.verifyAndMigrate();
            if (!result.healthy()) {
                candidate.close();
                if (!scheduler.isCurrentLifecycle(generation)) {
                    return;
                }
                details.add(messages().plainText(DATABASE_GATE_FAILED,
                        Map.of("detail", safeText(result.detail()))));
                lock(messages().plainText(DATABASE_GATE_LOCKED), details);
                return;
            }
            if (!scheduler.isCurrentLifecycle(generation)) {
                candidate.close();
                return;
            }
            if (!scheduler.runMain(() -> activateRuntime(candidate, details, result.detail(), generation))) {
                candidate.close();
            }
        } catch (RuntimeException | LinkageError exception) {
            if (!scheduler.isCurrentLifecycle(generation)) {
                return;
            }
            details.add(messages().plainText(DATABASE_CONFIG_INVALID,
                    Map.of("detail", safeText(safeMessage(exception)))));
            lock(messages().plainText(DATABASE_CONFIG_GATE_FAILED), details);
        }
    }

    private String resolveDatabaseUrl() {
        String configured = ConfigurationValues.text(plugin.getConfig(), "database.file",
                "tianjitown.db", messages()::plainText);
        if (configured.isBlank()) {
            throw new IllegalArgumentException(messages().plainText(DATABASE_FILE_REQUIRED));
        }
        Path databaseFile = Path.of(configured);
        if (!databaseFile.isAbsolute()) {
            databaseFile = plugin.getDataFolder().toPath().resolve(databaseFile);
        }
        databaseFile = databaseFile.toAbsolutePath().normalize();
        Path databaseDirectory = databaseFile.getParent();
        if (databaseDirectory == null) {
            throw new IllegalArgumentException(messages().plainText(DATABASE_FILE_PATH_INVALID));
        }
        try {
            Files.createDirectories(databaseDirectory);
        } catch (IOException exception) {
            throw new IllegalArgumentException(messages().plainText(
                    DATABASE_DIRECTORY_CREATE_FAILURE,
                    Map.of("path", safeText(databaseDirectory))), exception);
        }
        return "jdbc:sqlite:" + databaseFile;
    }

    private void activateRuntime(DatabaseGate candidate, List<String> previousDetails,
                                 String databaseDetail, long generation) {
        try {
            activateRuntimeChecked(candidate, previousDetails, databaseDetail, generation);
        } catch (RuntimeException | LinkageError exception) {
            cleanupFailedRuntimeActivation(candidate, townRuntime, exception);
            List<String> details = new ArrayList<>(previousDetails);
            details.add(messages().plainText(RUNTIME_ACTIVATION_FAILURE,
                    Map.of("detail", safeText(safeMessage(exception)))));
            lock(messages().plainText(RUNTIME_GATE_FAILED), details);
        }
    }

    private void activateRuntimeChecked(DatabaseGate candidate, List<String> previousDetails,
                                        String databaseDetail, long generation) {
        if (!scheduler.isCurrentLifecycle(generation)) {
            candidate.close();
            return;
        }
        Set<String> managedResidenceNames = ConcurrentHashMap.newKeySet();
        Set<String> activeResidenceNames = ConcurrentHashMap.newKeySet();
        TownRuntime runtime;
        TownActions actions;
        TownUiController ui;
        ResidenceLandProtectionService residenceProtection =
                new ResidenceLandProtectionService(plugin.getServer(), managedResidenceNames);
        try {
            runtime = new TownRuntime(plugin, candidate,
                    residenceProtection,
                    worldBoundaryService(), activeResidenceNames);
            org.allivlisey.tianjitown.integrations.vault.VaultSettlementService.Result settlement =
                    runtime.settlement().ensureAccount();
            if (!settlement.success()) {
                throw new IllegalStateException(settlement.message());
            }
            actions = new TownActions(plugin, runtime);
            ui = new TownUiController(plugin, runtime, actions);
        } catch (RuntimeException | LinkageError exception) {
            candidate.close();
            List<String> details = new ArrayList<>(previousDetails);
            details.add(messages().plainText(RUNTIME_INITIALIZATION_FAILURE,
                    Map.of("detail", safeText(safeMessage(exception)))));
            lock(messages().plainText(RUNTIME_GATE_FAILED), details);
            return;
        }
        // 在异步启动诊断期间也要由 onDisable 统一回收数据源；此时尚未注册业务组件。
        databaseGate = candidate;
        try {
            runtime.bonuses().diagnoseAtStartup(diagnostic -> completeRuntimeActivation(
                    candidate, previousDetails, databaseDetail, generation, runtime, actions, ui,
                    residenceProtection, managedResidenceNames, activeResidenceNames, diagnostic));
        } catch (RuntimeException | LinkageError exception) {
            closeDatabaseCandidate(candidate);
            List<String> details = new ArrayList<>(previousDetails);
            details.add(messages().plainText(STARTUP_DIAGNOSTIC_FAILED,
                    Map.of("detail", safeText(safeMessage(exception)))));
            lock(messages().plainText(STARTUP_DIAGNOSTIC_GATE_FAILED), details);
        }
    }

    private void completeRuntimeActivation(DatabaseGate candidate, List<String> previousDetails,
                                           String databaseDetail, long generation,
                                           TownRuntime runtime, TownActions actions,
                                           TownUiController ui,
                                           ResidenceLandProtectionService residenceProtection,
                                           Set<String> managedResidenceNames,
                                           Set<String> activeResidenceNames,
                                           TownBonusRuntime.DiagnosticResult diagnostic) {
        try {
            if (!scheduler.isCurrentLifecycle(generation)) {
                closeDatabaseCandidate(candidate);
                return;
            }
            if (!java.util.Objects.requireNonNull(diagnostic, "diagnostic").healthy()) {
                closeDatabaseCandidate(candidate);
                List<String> details = new ArrayList<>(previousDetails);
                details.add(messages().plainText(STARTUP_DIAGNOSTIC_FAILED,
                        Map.of("detail", safeText(diagnostic.detail()))));
                lock(messages().plainText(STARTUP_DIAGNOSTIC_GATE_FAILED), details);
                return;
            }
            registrar.activateRuntimeComponents(candidate, previousDetails, databaseDetail, generation,
                    runtime, actions, ui, residenceProtection, managedResidenceNames,
                    activeResidenceNames);
        } catch (RuntimeException | LinkageError exception) {
            cleanupFailedRuntimeActivation(candidate, runtime, exception);
            List<String> details = new ArrayList<>(previousDetails);
            details.add(messages().plainText(RUNTIME_ACTIVATION_FAILURE,
                    Map.of("detail", safeText(safeMessage(exception)))));
            lock(messages().plainText(RUNTIME_GATE_FAILED), details);
        }
    }

    private void cleanupFailedRuntimeActivation(DatabaseGate candidate, TownRuntime runtime,
                                                 Throwable failure) {
        HandlerList.unregisterAll(plugin);
        plugin.getServer().getScheduler().cancelTasks(plugin);
        cleanupRuntimeEffects(runtime, cleanup -> failure.addSuppressed(cleanup.cause()));
        TownUiController ui = townUi;
        if (ui != null) {
            try {
                ui.close();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        townRuntime = null;
        townActions = null;
        townUi = null;
        TownAdminTabCompleter completer = townAdminTabCompleter;
        if (completer != null) {
            completer.stop();
        }
        try {
            closeDatabaseCandidate(candidate);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        databaseGate = null;
    }

    private void cleanupRuntimeEffects(TownRuntime runtime,
            java.util.function.Consumer<CleanupFailure> failures) {
        if (runtime == null) {
            return;
        }
        cleanupEffect(() -> runtime.territoryPreviews().clearPreviews(),
                "log.site.preview-cancel-failed", failures);
        cleanupEffect(() -> runtime.bonuses().clearAll(), BEACON_CLEANUP_FAILURE, failures);
        cleanupEffect(() -> runtime.buffs().clearAll(), BUFF_CLEANUP_FAILURE, failures);
    }

    private void cleanupEffect(Runnable action, String key,
            java.util.function.Consumer<CleanupFailure> failures) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            failures.accept(new CleanupFailure(key, failure));
        }
    }

    private record CleanupFailure(String key, Throwable cause) {}

    private void closeDatabaseCandidate(DatabaseGate candidate) {
        if (databaseGate == candidate) {
            databaseGate = null;
        }
        candidate.close();
    }

    private WorldBoundaryService worldBoundaryService() {
        try {
            Plugin worldBorder = java.util.Objects.requireNonNull(
                    plugin.getServer().getPluginManager().getPlugin("WorldBorder"), "WorldBorder");
            return new WorldBorderBoundaryService(plugin.getServer(), worldBorder,
                    messages()::plainText);
        } catch (LinkageError error) {
            throw new IllegalStateException(messages().plainText(WORLD_BORDER_API_LOAD_FAILURE),
                    error);
        }
    }

    private void lock(String reason, List<String> details) {
        List<String> copy = new ArrayList<>(details);
        copy.add("LOCKED " + reason);
        gateStatus.set(new GateStatus(GateStatus.State.LOCKED, copy));
        plugin.getLogger().severe(plainText(RUNTIME_LOCKED, Map.of("reason", safeText(reason))));
    }

    static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    static String safeText(Object value) {
        return org.allivlisey.tianjitown.core.time.TownTime.display(value).replace('&', '＆').replace('§', '�');
    }
}

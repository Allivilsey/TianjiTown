package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.ports.WorldBoundaryService;
import org.allivlisey.tianjitown.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.jobs.JobsIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.residence.ResidenceCommandGuard;
import org.allivlisey.tianjitown.integrations.residence.ResidenceDeletionGuard;
import org.allivlisey.tianjitown.integrations.residence.ResidenceLandProtectionService;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopTaxAdapter;
import org.allivlisey.tianjitown.integrations.vault.VaultEconomyProbe;
import org.allivlisey.tianjitown.integrations.worldborder.WorldBorderBoundaryService;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TianjiTownPlugin extends JavaPlugin {
    private static final int CONFIG_SCHEMA = 10;
    private static final String BOOTSTRAP_GATE_DETAIL = "TT-PLUGIN-NOT-STARTED";
    private static final String BOOTSTRAP_MESSAGES_NOT_LOADED = "TT-MESSAGES-NOT-LOADED";
    private static final String STARTUP_CHECKING = "diagnostic.lifecycle.startup-checking";
    private static final String ADMIN_COMMAND_MISSING =
            "diagnostic.lifecycle.admin-command-missing";
    private static final String CONFIG_SCHEMA_GATE_FAILED =
            "diagnostic.lifecycle.config-schema-gate-failed";
    private static final String CONFIGURATION_VALIDATION_PASSED =
            "diagnostic.lifecycle.configuration-validation-passed";
    private static final String CONFIGURATION_VALIDATION_FAILED =
            "diagnostic.lifecycle.configuration-validation-failed";
    private static final String BUSINESS_CONFIG_GATE_FAILED =
            "diagnostic.lifecycle.business-config-gate-failed";
    private static final String SYNCHRONOUS_GATE_FAILED =
            "diagnostic.lifecycle.synchronous-gate-failed";
    private static final String DEPENDENCY_MISSING =
            "diagnostic.lifecycle.dependency-missing";
    private static final String DEPENDENCY_DISABLED =
            "diagnostic.lifecycle.dependency-disabled";
    private static final String DEPENDENCY_PROBE_FAILURE =
            "diagnostic.lifecycle.dependency-probe-failure";
    private static final String VAULT_ECONOMY_UNAVAILABLE =
            "diagnostic.lifecycle.vault-economy-unavailable";
    private static final String DATABASE_GATE_FAILED =
            "diagnostic.lifecycle.database-gate-failed";
    private static final String DATABASE_GATE_LOCKED =
            "diagnostic.lifecycle.database-gate-locked";
    private static final String DATABASE_CONFIG_INVALID =
            "diagnostic.lifecycle.database-config-invalid";
    private static final String DATABASE_CONFIG_GATE_FAILED =
            "diagnostic.lifecycle.database-config-gate-failed";
    private static final String STARTUP_DIAGNOSTIC_PASSED =
            "diagnostic.lifecycle.startup-diagnostic-passed";
    private static final String STARTUP_DIAGNOSTIC_FAILED =
            "diagnostic.lifecycle.startup-diagnostic-failed";
    private static final String STARTUP_DIAGNOSTIC_GATE_FAILED =
            "diagnostic.lifecycle.startup-diagnostic-gate-failed";
    private static final String CONFIG_SCHEMA_TOO_NEW =
            "diagnostic.lifecycle.config-schema-too-new";
    private static final String CONFIG_SCHEMA_UPGRADE_REQUIRED =
            "diagnostic.lifecycle.config-schema-upgrade-required";
    private static final String RUNTIME_ACTIVATION_FAILURE =
            "diagnostic.lifecycle.runtime-activation-failure";
    private static final String RUNTIME_GATE_FAILED =
            "diagnostic.lifecycle.runtime-gate-failed";
    private static final String RUNTIME_INITIALIZATION_FAILURE =
            "diagnostic.lifecycle.runtime-initialization-failure";
    private static final String WORLD_BORDER_READY =
            "diagnostic.lifecycle.world-border-ready";
    private static final String DIALOG_UI_READY =
            "diagnostic.lifecycle.dialog-ui-ready";
    private static final String RUNTIME_FEATURES_READY =
            "diagnostic.lifecycle.runtime-features-ready";
    private static final String WORLD_BORDER_API_LOAD_FAILURE =
            "diagnostic.world-border.api-load-failure";
    private static final String DATABASE_FILE_REQUIRED =
            "validation.runtime-configuration.database-file-required";
    private static final String DATABASE_FILE_PATH_INVALID =
            "validation.runtime-configuration.database-file-path-invalid";
    private static final String DATABASE_DIRECTORY_CREATE_FAILURE =
            "validation.runtime-configuration.database-directory-create-failure";
    private static final String NEGATIVE_DELAY = "diagnostic.scheduler.negative-delay";
    private static final String UI_CLOSE_FAILURE = "log.lifecycle.ui-close-failure";
    private static final String ASYNC_SHUTDOWN_TIMEOUT = "log.lifecycle.async-shutdown-timeout";
    private static final String BEACON_CLEANUP_FAILURE = "log.lifecycle.beacon-cleanup-failure";
    private static final String BUFF_CLEANUP_FAILURE = "log.lifecycle.buff-cleanup-failure";
    private static final String DATABASE_CLOSE_FAILURE = "log.lifecycle.database-close-failure";
    private static final String RESIDENCE_NAMES_LOAD_FAILURE =
            "log.lifecycle.residence-names-load-failure";
    private static final String RUNTIME_STARTED = "log.lifecycle.runtime-started";
    private static final String RUNTIME_LOCKED = "log.lifecycle.runtime-locked";
    private static final String ASYNC_TASK_FAILURE = "log.scheduler.async-task-failure";
    private static final String MAIN_THREAD_CALLBACK_FAILURE =
            "log.scheduler.main-thread-callback-failure";
    private static final String MAIN_THREAD_CALLBACK_SUBMIT_FAILURE =
            "log.scheduler.main-thread-callback-submit-failure";
    private static final String PERIODIC_SQLITE_RECOVERY_FAILURE =
            "log.scheduler.periodic.sqlite-recovery-failure";
    private static final String PERIODIC_RESIDENCE_RECONCILIATION_FAILURE =
            "log.scheduler.periodic.residence-reconciliation-failure";
    private static final String PERIODIC_VOTE_SETTLEMENT_FAILURE =
            "log.scheduler.periodic.vote-settlement-failure";
    private static final String PERIODIC_SETTLEMENT_RECONCILIATION_FAILURE =
            "log.scheduler.periodic.settlement-reconciliation-failure";
    private static final String PERIODIC_TERRITORY_BONUS_INDEX_FAILURE =
            "log.scheduler.periodic.territory-bonus-index-refresh-failure";
    private static final String PERIODIC_BEACON_EFFECT_FAILURE =
            "log.scheduler.periodic.beacon-effect-refresh-failure";
    private static final String PERIODIC_REFUND_COUNTER_FAILURE =
            "log.scheduler.periodic.refund-counter-cleanup-failure";
    private final AtomicReference<GateStatus> gateStatus = new AtomicReference<>(
            new GateStatus(GateStatus.State.CHECKING, List.of(BOOTSTRAP_GATE_DETAIL)));
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AsyncTaskTracker asyncTasks = new AsyncTaskTracker();
    private final ThreadLocal<Long> asyncGeneration = new ThreadLocal<>();
    private final Set<String> periodicFailures = ConcurrentHashMap.newKeySet();
    private volatile DatabaseGate databaseGate;
    private volatile ExecutorService asyncExecutor;
    private volatile TownRuntime townRuntime;
    private volatile TownActions townActions;
    private volatile TownUiController townUi;
    private volatile TownAdminTabCompleter townAdminTabCompleter;
    private volatile PluginMessages messages;

    @Override
    public void onEnable() {
        long generation = lifecycleGeneration.incrementAndGet();
        periodicFailures.clear();
        saveDefaultConfig();
        saveResource("messages.yml", false);
        messages = new PluginMessages(getDataFolder());
        gateStatus.set(new GateStatus(GateStatus.State.CHECKING,
                List.of(messages().plainText(STARTUP_CHECKING))));
        asyncTasks.startAccepting();
        asyncExecutor = Executors.newFixedThreadPool(4,
                Thread.ofPlatform().daemon(true).name("TianjiTown-Async-", 0).factory());
        org.bukkit.command.PluginCommand adminCommand = java.util.Objects.requireNonNull(
                getCommand("townadmin"), messages().plainText(ADMIN_COMMAND_MISSING));
        TownAdminTabCompleter completer = new TownAdminTabCompleter(this);
        townAdminTabCompleter = completer;
        adminCommand.setExecutor(new TownAdminCommand(this));
        adminCommand.setTabCompleter(completer);

        List<String> synchronousChecks = new ArrayList<>();
        if (!prepareConfigSchema(synchronousChecks)) {
            lock(messages().plainText(CONFIG_SCHEMA_GATE_FAILED), synchronousChecks);
            return;
        }
        RuntimeConfigurationValidator.DatabaseSettings databaseSettings;
        try {
            databaseSettings = RuntimeConfigurationValidator.validate(getConfig(), messages());
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
        runAsync(() -> checkDatabase(synchronousChecks, databaseSettings, generation));
    }

    @Override
    public void onDisable() {
        lifecycleGeneration.incrementAndGet();
        asyncTasks.stopAccepting();
        TownUiController ui = townUi;
        if (ui != null) {
            try {
                ui.close();
            } catch (RuntimeException | LinkageError exception) {
                getLogger().warning(plainText(UI_CLOSE_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
        }
        TownAdminTabCompleter completer = townAdminTabCompleter;
        if (completer != null) {
            completer.stop();
        }
        ExecutorService executor = asyncExecutor;
        if (executor != null) {
            executor.shutdown();
        }
        if (!asyncTasks.awaitQuiescence(Duration.ofSeconds(30))) {
            getLogger().severe(plainText(ASYNC_SHUTDOWN_TIMEOUT,
                    Map.of("active", asyncTasks.active())));
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        asyncExecutor = null;
        getServer().getScheduler().cancelTasks(this);
        TownRuntime runtime = townRuntime;
        if (runtime != null) {
            runtime.sitePolicy().clearPreviews();
            try {
                runtime.bonuses().clearAll();
            } catch (RuntimeException | LinkageError exception) {
                getLogger().warning(plainText(BEACON_CLEANUP_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
            try {
                runtime.buffs().clearAll();
            } catch (RuntimeException | LinkageError exception) {
                getLogger().warning(plainText(BUFF_CLEANUP_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
        }
        DatabaseGate gate = databaseGate;
        if (gate != null) {
            try {
                gate.close();
            } catch (RuntimeException exception) {
                getLogger().warning(plainText(DATABASE_CLOSE_FAILURE,
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

    TownRuntime townRuntime() {
        return townRuntime;
    }

    TownUiController townUi() {
        return townUi;
    }

    TownActions townActions() {
        return townActions;
    }

    PluginMessages messages() {
        PluginMessages current = messages;
        if (current == null) {
            throw new IllegalStateException(BOOTSTRAP_MESSAGES_NOT_LOADED);
        }
        return current;
    }

    void reloadMessages() {
        messages().reload();
    }

    private String plainText(String key) {
        return plainText(key, Map.of());
    }

    private String plainText(String key, Map<String, ?> placeholders) {
        PluginMessages current = messages;
        if (current == null) {
            return "TT-MESSAGES-UNAVAILABLE: " + key;
        }
        return current.plainText(key, placeholders);
    }

    boolean runAsync(Runnable task) {
        java.util.Objects.requireNonNull(task, "task");
        ExecutorService executor = asyncExecutor;
        long generation = lifecycleGeneration.get();
        if (executor == null || !isCurrentLifecycle(generation)) {
            return false;
        }
        try {
            executor.execute(() -> {
                if (!asyncTasks.begin()) {
                    return;
                }
                asyncGeneration.set(generation);
                try {
                    if (isCurrentLifecycle(generation)) {
                        task.run();
                    }
                } catch (RuntimeException | LinkageError exception) {
                    if (isCurrentLifecycle(generation)) {
                        getLogger().severe(plainText(ASYNC_TASK_FAILURE,
                                Map.of("detail", safeMessage(exception))));
                    }
                } finally {
                    asyncGeneration.remove();
                    asyncTasks.complete();
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            if (!executor.isShutdown()) {
                throw exception;
            }
            return false;
        }
    }

    boolean runMain(Runnable task) {
        return scheduleMain(task, 0L);
    }

    boolean runMainLater(Runnable task, long delayTicks) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException(plainText(NEGATIVE_DELAY));
        }
        return scheduleMain(task, delayTicks);
    }

    private boolean scheduleMain(Runnable task, long delayTicks) {
        java.util.Objects.requireNonNull(task, "task");
        Long inheritedGeneration = asyncGeneration.get();
        long generation = inheritedGeneration == null
                ? lifecycleGeneration.get() : inheritedGeneration;
        if (!isCurrentLifecycle(generation)) {
            return false;
        }
        Runnable guarded = () -> {
            if (!isCurrentLifecycle(generation)) {
                return;
            }
            try {
                task.run();
            } catch (RuntimeException | LinkageError exception) {
                getLogger().severe(plainText(MAIN_THREAD_CALLBACK_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
        };
        try {
            if (delayTicks == 0L) {
                getServer().getScheduler().runTask(this, guarded);
            } else {
                getServer().getScheduler().runTaskLater(this, guarded, delayTicks);
            }
            return true;
        } catch (RuntimeException exception) {
            if (isCurrentLifecycle(generation)) {
                getLogger().warning(plainText(MAIN_THREAD_CALLBACK_SUBMIT_FAILURE,
                        Map.of("detail", safeMessage(exception))));
            }
            return false;
        }
    }

    private boolean checkDependencies(List<String> details) {
        boolean healthy = true;
        details.add("INFO Minecraft " + getServer().getMinecraftVersion()
                + " / Java " + Runtime.version().feature());
        for (String name : List.of("Residence", "Vault", "XConomy", "QuickShop-Hikari",
                "Jobs", "GlobalMarketPlus", "WorldBorder")) {
            try {
                Plugin dependency = getServer().getPluginManager().getPlugin(name);
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
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            VaultEconomyProbe.Result economy = new VaultEconomyProbe(getServer(),
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
                if (!isCurrentLifecycle(generation)) {
                    return;
                }
                details.add(messages().plainText(DATABASE_GATE_FAILED,
                        Map.of("detail", safeText(result.detail()))));
                lock(messages().plainText(DATABASE_GATE_LOCKED), details);
                return;
            }
            if (!isCurrentLifecycle(generation)) {
                candidate.close();
                return;
            }
            if (!runMain(() -> activateRuntime(candidate, details, result.detail(), generation))) {
                candidate.close();
            }
        } catch (RuntimeException | LinkageError exception) {
            if (!isCurrentLifecycle(generation)) {
                return;
            }
            details.add(messages().plainText(DATABASE_CONFIG_INVALID,
                    Map.of("detail", safeText(safeMessage(exception)))));
            lock(messages().plainText(DATABASE_CONFIG_GATE_FAILED), details);
        }
    }

    private boolean prepareConfigSchema(List<String> details) {
        int configured = getConfig().getInt("schema-version", -1);
        if (configured == CONFIG_SCHEMA) {
            details.add("OK config schema=" + CONFIG_SCHEMA);
            return true;
        }
        if (configured > CONFIG_SCHEMA) {
            details.add(messages().plainText(CONFIG_SCHEMA_TOO_NEW,
                    Map.of("schema", configured, "supported", CONFIG_SCHEMA)));
        } else {
            details.add(messages().plainText(CONFIG_SCHEMA_UPGRADE_REQUIRED,
                    Map.of("schema", configured, "supported", CONFIG_SCHEMA)));
        }
        return false;
    }

    private String resolveDatabaseUrl() {
        String configured = ConfigurationValues.text(getConfig(), "database.file",
                "tianjitown.db", messages()::plainText);
        if (configured.isBlank()) {
            throw new IllegalArgumentException(messages().plainText(DATABASE_FILE_REQUIRED));
        }
        Path databaseFile = Path.of(configured);
        if (!databaseFile.isAbsolute()) {
            databaseFile = getDataFolder().toPath().resolve(databaseFile);
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
            HandlerList.unregisterAll(this);
            getServer().getScheduler().cancelTasks(this);
            TownRuntime runtime = townRuntime;
            if (runtime != null) {
                runtime.sitePolicy().clearPreviews();
                try {
                    runtime.bonuses().clearAll();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                try {
                    runtime.buffs().clearAll();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            townRuntime = null;
            townActions = null;
            townUi = null;
            TownAdminTabCompleter completer = townAdminTabCompleter;
            if (completer != null) {
                completer.stop();
            }
            candidate.close();
            List<String> details = new ArrayList<>(previousDetails);
            details.add(messages().plainText(RUNTIME_ACTIVATION_FAILURE,
                    Map.of("detail", safeText(safeMessage(exception)))));
            lock(messages().plainText(RUNTIME_GATE_FAILED), details);
        }
    }

    private void activateRuntimeChecked(DatabaseGate candidate, List<String> previousDetails,
                                        String databaseDetail, long generation) {
        if (!isCurrentLifecycle(generation)) {
            candidate.close();
            return;
        }
        Set<String> managedResidenceNames = ConcurrentHashMap.newKeySet();
        Set<String> activeResidenceNames = ConcurrentHashMap.newKeySet();
        TownRuntime runtime;
        TownActions actions;
        TownUiController ui;
        ResidenceLandProtectionService residenceProtection =
                new ResidenceLandProtectionService(getServer(), managedResidenceNames);
        try {
            runtime = new TownRuntime(this, candidate,
                    residenceProtection,
                    worldBoundaryService(), activeResidenceNames);
            org.allivlisey.tianjitown.integrations.vault.VaultSettlementService.Result settlement =
                    runtime.settlement().ensureAccount();
            if (!settlement.success()) {
                throw new IllegalStateException(settlement.message());
            }
            actions = new TownActions(this, runtime);
            ui = new TownUiController(this, runtime, actions);
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
            if (!isCurrentLifecycle(generation)) {
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
            activateRuntimeComponents(candidate, previousDetails, databaseDetail, generation,
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

    private void activateRuntimeComponents(DatabaseGate candidate, List<String> previousDetails,
                                           String databaseDetail, long generation,
                                           TownRuntime runtime, TownActions actions,
                                           TownUiController ui,
                                           ResidenceLandProtectionService residenceProtection,
                                           Set<String> managedResidenceNames,
                                           Set<String> activeResidenceNames) {
        databaseGate = candidate;
        Plugin quickShop = getServer().getPluginManager().getPlugin("QuickShop-Hikari");
        QuickShopTaxAdapter.Capability quickShopCapability = new QuickShopTaxAdapter(this,
                java.util.Objects.requireNonNull(quickShop, "QuickShop-Hikari"),
                runtime::quickShopTaxEnabled, runtime::taxPolicy, runtime::acceptQuickShopTax,
                runtime.settlement().accountName(), runtime.settlement().accountId(),
                runtime.settlement().scale(), messages()::plainText).register();
        runtime.setQuickShopTaxAvailable(quickShopCapability.available());
        Plugin jobs = java.util.Objects.requireNonNull(
                getServer().getPluginManager().getPlugin("Jobs"), "Jobs");
        JobsIncomeTaxAdapter.Capability jobsCapability = new JobsIncomeTaxAdapter(this, jobs,
                runtime::taxEnabled, runtime::acceptJobsIncomeTax,
                messages()::plainText).register();
        Plugin globalMarketPlus = java.util.Objects.requireNonNull(
                getServer().getPluginManager().getPlugin("GlobalMarketPlus"),
                "GlobalMarketPlus");
        GlobalMarketPlusIncomeTaxAdapter.Capability globalMarketCapability =
                new GlobalMarketPlusIncomeTaxAdapter(this, globalMarketPlus,
                        runtime::taxEnabled,
                        runtime::acceptGlobalMarketPlusIncomeTax,
                        messages()::plainText).register();
        townRuntime = runtime;
        townActions = actions;
        townUi = ui;
        TownAdminTabCompleter completer = townAdminTabCompleter;
        if (completer != null) {
            completer.start(runtime);
        }
        getServer().getPluginManager().registerEvents(ui, this);
        getServer().getPluginManager().registerEvents(runtime.buffs(), this);
        getServer().getPluginManager().registerEvents(runtime.bonuses(), this);
        getServer().getPluginManager().registerEvents(
                new ResidenceCommandGuard(this, managedResidenceNames::contains,
                        activeResidenceNames::contains,
                        messages()::text, messages()::plainText), this);
        getServer().getPluginManager().registerEvents(new ResidenceDeletionGuard(this,
                managedResidenceNames::contains, residenceProtection::internalMutation,
                runtime::reconcileAll, messages()::text, messages()::plainText), this);
        runAsync(() -> {
            try {
                runtime.repository().listTowns(true).forEach(town -> {
                    managedResidenceNames.add(town.residenceName());
                    if (town.status() == org.allivlisey.tianjitown.core.town.TownStatus.ACTIVE) {
                        activeResidenceNames.add(town.residenceName());
                    }
                });
            } catch (RuntimeException exception) {
                getLogger().severe(plainText(RESIDENCE_NAMES_LOAD_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
        runtime.recoverStartupState();
        runtime.backfillKnownPlayerNames();
        runtime.buffs().refreshAllPlayers();
        runtime.bonuses().recoverTaggedBeacons();
        runtime.bonuses().refreshIndex();
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic(PERIODIC_SQLITE_RECOVERY_FAILURE, runtime::checkRecovery),
                20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic(PERIODIC_RESIDENCE_RECONCILIATION_FAILURE,
                        runtime::reconcileAll), 20L * 10,
                20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic(PERIODIC_VOTE_SETTLEMENT_FAILURE,
                        runtime::settleDueVotes), 20L * 30,
                20L * 60);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic(PERIODIC_SETTLEMENT_RECONCILIATION_FAILURE,
                        runtime::reconcileSettlement), 20L * 20,
                20L * 60 * Math.max(1,
                        getConfig().getLong("economy.reconciliation-interval-minutes", 5)));
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic(PERIODIC_TERRITORY_BONUS_INDEX_FAILURE,
                        runtime.bonuses()::refreshIndex),
                20L * 15, 20L * 30);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic(PERIODIC_BEACON_EFFECT_FAILURE,
                        runtime.bonuses()::refreshBeaconEffects),
                20L * 10, runtime.bonuses().settings().beacon().refreshIntervalTicks());
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic(PERIODIC_REFUND_COUNTER_FAILURE,
                        runtime.bonuses()::cleanupCounters),
                20L * 60, 20L * 60 * 60);
        List<String> details = new ArrayList<>(previousDetails);
        details.add("OK " + databaseDetail);
        details.add(messages().plainText(STARTUP_DIAGNOSTIC_PASSED));
        details.add((quickShopCapability.available() ? "OK " : "WARN ")
                + quickShopCapability.detail());
        details.add((jobsCapability.available() ? "OK " : "WARN ")
                + jobsCapability.detail());
        details.add((globalMarketCapability.available() ? "OK " : "WARN ")
                + globalMarketCapability.detail());
        details.add(messages().plainText(WORLD_BORDER_READY));
        details.add(messages().plainText(DIALOG_UI_READY));
        details.add(messages().plainText(RUNTIME_FEATURES_READY));
        gateStatus.set(new GateStatus(GateStatus.State.READY, details));
        getLogger().info(plainText(RUNTIME_STARTED));
    }

    private void cleanupFailedRuntimeActivation(DatabaseGate candidate, TownRuntime runtime,
                                                 Throwable failure) {
        HandlerList.unregisterAll(this);
        getServer().getScheduler().cancelTasks(this);
        if (runtime != null) {
            runtime.sitePolicy().clearPreviews();
            try {
                runtime.bonuses().clearAll();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                runtime.buffs().clearAll();
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

    private void closeDatabaseCandidate(DatabaseGate candidate) {
        if (databaseGate == candidate) {
            databaseGate = null;
        }
        candidate.close();
    }

    private boolean isCurrentLifecycle(long generation) {
        return isEnabled() && lifecycleGeneration.get() == generation;
    }

    private void runPeriodic(String messageKey, Runnable task) {
        try {
            task.run();
            periodicFailures.remove(messageKey);
        } catch (RuntimeException | LinkageError exception) {
            // 同一周期任务持续失败时只记录首次，避免依赖故障造成日志洪泛。
            if (periodicFailures.add(messageKey)) {
                getLogger().severe(plainText(messageKey,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        }
    }

    private WorldBoundaryService worldBoundaryService() {
        try {
            Plugin worldBorder = java.util.Objects.requireNonNull(
                    getServer().getPluginManager().getPlugin("WorldBorder"), "WorldBorder");
            return new WorldBorderBoundaryService(getServer(), worldBorder,
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
        getLogger().severe(plainText(RUNTIME_LOCKED, Map.of("reason", safeText(reason))));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }
}

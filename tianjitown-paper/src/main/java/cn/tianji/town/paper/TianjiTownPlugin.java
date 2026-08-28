package cn.tianji.town.paper;

import cn.tianji.town.core.ports.WorldBoundaryService;
import cn.tianji.town.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import cn.tianji.town.integrations.jobs.JobsIncomeTaxAdapter;
import cn.tianji.town.integrations.residence.ResidenceCommandGuard;
import cn.tianji.town.integrations.residence.ResidenceDeletionGuard;
import cn.tianji.town.integrations.residence.ResidenceLandProtectionService;
import cn.tianji.town.integrations.quickshop.QuickShopTaxAdapter;
import cn.tianji.town.integrations.vault.VaultEconomyProbe;
import cn.tianji.town.integrations.worldborder.WorldBorderBoundaryService;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TianjiTownPlugin extends JavaPlugin {
    private static final int CONFIG_SCHEMA = 10;
    private final AtomicReference<GateStatus> gateStatus = new AtomicReference<>(
            new GateStatus(GateStatus.State.CHECKING, List.of("尚未开始")));
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
        gateStatus.set(new GateStatus(GateStatus.State.CHECKING, List.of("正在执行启动门禁")));
        asyncTasks.startAccepting();
        asyncExecutor = Executors.newFixedThreadPool(4,
                Thread.ofPlatform().daemon(true).name("TianjiTown-Async-", 0).factory());
        saveDefaultConfig();
        saveResource("messages.yml", false);
        messages = new PluginMessages(getDataFolder());
        org.bukkit.command.PluginCommand adminCommand = java.util.Objects.requireNonNull(
                getCommand("townadmin"), "plugin.yml 缺少 townadmin");
        TownAdminTabCompleter completer = new TownAdminTabCompleter(this);
        townAdminTabCompleter = completer;
        adminCommand.setExecutor(new TownAdminCommand(this));
        adminCommand.setTabCompleter(completer);

        List<String> synchronousChecks = new ArrayList<>();
        if (!prepareConfigSchema(synchronousChecks)) {
            lock("配置 schema 门禁未通过", synchronousChecks);
            return;
        }
        RuntimeConfigurationValidator.DatabaseSettings databaseSettings;
        try {
            databaseSettings = RuntimeConfigurationValidator.validate(getConfig(),
                    world -> getServer().getWorld(world) != null);
            synchronousChecks.add("OK 配置类型、范围与世界引用校验通过");
        } catch (RuntimeException exception) {
            synchronousChecks.add("FAIL 配置校验: " + exception.getMessage());
            lock("业务配置门禁未通过", synchronousChecks);
            return;
        }
        boolean dependenciesHealthy = checkDependencies(synchronousChecks);
        gateStatus.set(new GateStatus(GateStatus.State.CHECKING, synchronousChecks));

        if (!dependenciesHealthy) {
            lock("同步门禁未通过", synchronousChecks);
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
                getLogger().warning("停服关闭玩家界面失败: " + safeMessage(exception));
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
            getLogger().severe("等待异步任务结束超时，仍有 " + asyncTasks.active()
                    + " 个任务；将继续关闭数据源。请检查阻塞的第三方 API。");
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
                getLogger().warning("停服清理信标效果失败: " + safeMessage(exception));
            }
            try {
                runtime.buffs().clearAll();
            } catch (RuntimeException | LinkageError exception) {
                getLogger().warning("停服清理公共 Buff 失败: " + safeMessage(exception));
            }
        }
        DatabaseGate gate = databaseGate;
        if (gate != null) {
            try {
                gate.close();
            } catch (RuntimeException exception) {
                getLogger().warning("关闭 SQLite 数据源失败: " + safeMessage(exception));
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
            throw new IllegalStateException("messages.yml 尚未加载");
        }
        return current;
    }

    void reloadMessages() {
        messages().reload();
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
                        getLogger().severe("异步任务异常，已在插件边界隔离: "
                                + safeMessage(exception));
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
            throw new IllegalArgumentException("delayTicks 不能为负数");
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
                getLogger().severe("主线程回调异常，已在插件边界隔离: "
                        + safeMessage(exception));
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
                getLogger().warning("主线程回调提交失败: " + safeMessage(exception));
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
                    details.add("FAIL " + name + " 未安装");
                    healthy = false;
                } else if (!dependency.isEnabled()) {
                    details.add("FAIL " + name + " " + dependency.getPluginMeta().getVersion()
                            + "（未启用）");
                    healthy = false;
                } else {
                    details.add("OK " + name + " " + dependency.getPluginMeta().getVersion());
                }
            } catch (RuntimeException | LinkageError exception) {
                details.add("FAIL " + name + " 依赖探测异常: " + safeMessage(exception));
                healthy = false;
            }
        }
        boolean economyHealthy = false;
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            VaultEconomyProbe.Result economy = new VaultEconomyProbe(getServer()).verify();
            details.add((economy.healthy() ? "OK " : "FAIL ") + "Vault Economy provider="
                    + economy.provider() + " (" + economy.message() + ")");
            economyHealthy = economy.healthy();
        } else {
            details.add("FAIL Vault Economy provider=不可用 (Vault 未启用，已跳过服务探测)");
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
                details.add("FAIL SQLite/Flyway: " + result.detail());
                lock("数据库门禁未通过", details);
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
            details.add("FAIL SQLite config: " + exception.getMessage());
            lock("数据库配置无效", details);
        }
    }

    private boolean prepareConfigSchema(List<String> details) {
        int configured = getConfig().getInt("schema-version", -1);
        if (configured == CONFIG_SCHEMA) {
            details.add("OK config schema=" + CONFIG_SCHEMA);
            return true;
        }
        if (configured > CONFIG_SCHEMA) {
            details.add("FAIL config schema=" + configured + " 高于本插件支持的 "
                    + CONFIG_SCHEMA + "，拒绝降级读取");
        } else {
            details.add("FAIL config schema=" + configured + " 不能直接安全升级到 "
                    + CONFIG_SCHEMA + "；请先按对应版本升级手册处理");
        }
        return false;
    }

    private String resolveDatabaseUrl() {
        String configured = ConfigurationValues.text(getConfig(), "database.file",
                "tianjitown.db");
        if (configured.isBlank()) {
            throw new IllegalArgumentException("database.file 不能为空");
        }
        Path databaseFile = Path.of(configured);
        if (!databaseFile.isAbsolute()) {
            databaseFile = getDataFolder().toPath().resolve(databaseFile);
        }
        databaseFile = databaseFile.toAbsolutePath().normalize();
        Path databaseDirectory = databaseFile.getParent();
        if (databaseDirectory == null) {
            throw new IllegalArgumentException("database.file 必须指向数据库文件");
        }
        try {
            Files.createDirectories(databaseDirectory);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法创建 SQLite 目录: " + databaseDirectory, exception);
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
            details.add("FAIL 业务运行时激活异常: " + safeMessage(exception));
            lock("业务运行时门禁未通过", details);
        }
    }

    private void activateRuntimeChecked(DatabaseGate candidate, List<String> previousDetails,
                                        String databaseDetail, long generation) {
        if (!isCurrentLifecycle(generation)) {
            candidate.close();
            return;
        }
        Set<String> managedResidenceNames = ConcurrentHashMap.newKeySet();
        TownRuntime runtime;
        TownActions actions;
        TownUiController ui;
        ResidenceLandProtectionService residenceProtection =
                new ResidenceLandProtectionService(getServer(), managedResidenceNames);
        try {
            runtime = new TownRuntime(this, candidate,
                    residenceProtection,
                    worldBoundaryService());
            cn.tianji.town.integrations.vault.VaultSettlementService.Result settlement =
                    runtime.settlement().ensureAccount();
            if (!settlement.success()) {
                throw new IllegalStateException(settlement.message());
            }
            actions = new TownActions(this, runtime);
            ui = new TownUiController(this, runtime, actions);
        } catch (RuntimeException | LinkageError exception) {
            candidate.close();
            List<String> details = new ArrayList<>(previousDetails);
            details.add("FAIL 业务配置、WorldBorder、玩家界面或清算账户: "
                    + exception.getMessage());
            lock("业务运行时门禁未通过", details);
            return;
        }
        databaseGate = candidate;
        Plugin quickShop = getServer().getPluginManager().getPlugin("QuickShop-Hikari");
        QuickShopTaxAdapter.Capability quickShopCapability = new QuickShopTaxAdapter(this,
                java.util.Objects.requireNonNull(quickShop, "QuickShop-Hikari"),
                runtime::quickShopTaxEnabled, runtime::taxPolicy, runtime::acceptQuickShopTax,
                runtime.settlement().accountName(), runtime.settlement().accountId(),
                runtime.settlement().scale()).register();
        runtime.setQuickShopTaxAvailable(quickShopCapability.available());
        Plugin jobs = java.util.Objects.requireNonNull(
                getServer().getPluginManager().getPlugin("Jobs"), "Jobs");
        JobsIncomeTaxAdapter.Capability jobsCapability = new JobsIncomeTaxAdapter(this, jobs,
                runtime::taxEnabled, runtime::acceptJobsIncomeTax).register();
        Plugin globalMarketPlus = java.util.Objects.requireNonNull(
                getServer().getPluginManager().getPlugin("GlobalMarketPlus"),
                "GlobalMarketPlus");
        GlobalMarketPlusIncomeTaxAdapter.Capability globalMarketCapability =
                new GlobalMarketPlusIncomeTaxAdapter(this, globalMarketPlus,
                        runtime::taxEnabled,
                        runtime::acceptGlobalMarketPlusIncomeTax).register();
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
                new ResidenceCommandGuard(this, managedResidenceNames::contains), this);
        getServer().getPluginManager().registerEvents(new ResidenceDeletionGuard(this,
                managedResidenceNames::contains, residenceProtection::internalMutation,
                runtime::reconcileAll), this);
        runAsync(() -> {
            try {
                runtime.repository().listTowns(true).stream().map(town -> town.residenceName())
                        .forEach(managedResidenceNames::add);
            } catch (RuntimeException exception) {
                getLogger().severe("读取系统 Residence 名称清单失败: " + exception.getMessage());
            }
        });
        runtime.recoverStartupState();
        runtime.buffs().refreshAllPlayers();
        runtime.bonuses().recoverTaggedBeacons();
        runtime.bonuses().refreshIndex();
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("SQLite 恢复检查", runtime::checkRecovery),
                20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("Residence 对账", runtime::reconcileAll), 20L * 10,
                20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("投票结算", runtime::settleDueVotes), 20L * 30,
                20L * 60);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("清算对账", runtime::reconcileSettlement), 20L * 20,
                20L * 60 * Math.max(1,
                        getConfig().getLong("economy.reconciliation-interval-minutes", 5)));
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("领地加成索引刷新", runtime.bonuses()::refreshIndex),
                20L * 15, 20L * 30);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("信标效果刷新", runtime.bonuses()::refreshBeaconEffects),
                20L * 10, runtime.bonuses().settings().beacon().refreshIntervalTicks());
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("返还计数清理", runtime.bonuses()::cleanupCounters),
                20L * 60, 20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this,
                () -> runPeriodic("定时诊断", runtime.bonuses()::diagnoseScheduled),
                20L * 60 * 5, 20L * 60
                        * runtime.bonuses().settings().operations().diagnosticsInterval().toMinutes());
        if (runtime.bonuses().settings().operations().backup().enabled()) {
            getServer().getScheduler().runTaskTimer(this,
                    () -> runPeriodic("定时备份", runtime.bonuses()::createScheduledBackup),
                    20L * 60,
                    20L * 60 * 60 * runtime.bonuses().settings().operations()
                            .backup().interval().toHours());
        }
        List<String> details = new ArrayList<>(previousDetails);
        details.add("OK " + databaseDetail);
        details.add((quickShopCapability.available() ? "OK " : "WARN ")
                + quickShopCapability.detail());
        details.add((jobsCapability.available() ? "OK " : "WARN ")
                + jobsCapability.detail());
        details.add((globalMarketCapability.available() ? "OK " : "WARN ")
                + globalMarketCapability.detail());
        details.add("OK WorldBorder 边界 API 已接入");
        details.add("OK 玩家界面=DIALOG");
        details.add("OK 建筑返还、信标增强、统一诊断与定时备份已启用");
        gateStatus.set(new GateStatus(GateStatus.State.READY, details));
        getLogger().info("业务运行时启动完成；玩家入口仅限服务台和小镇手册。");
    }

    private boolean isCurrentLifecycle(long generation) {
        return isEnabled() && lifecycleGeneration.get() == generation;
    }

    private void runPeriodic(String name, Runnable task) {
        try {
            task.run();
            periodicFailures.remove(name);
        } catch (RuntimeException | LinkageError exception) {
            // 同一周期任务持续失败时只记录首次，避免依赖故障造成日志洪泛。
            if (periodicFailures.add(name)) {
                getLogger().severe(name + "失败，后续周期仍会继续尝试: "
                        + safeMessage(exception));
            }
        }
    }

    private WorldBoundaryService worldBoundaryService() {
        try {
            Plugin worldBorder = java.util.Objects.requireNonNull(
                    getServer().getPluginManager().getPlugin("WorldBorder"), "WorldBorder");
            return new WorldBorderBoundaryService(getServer(), worldBorder);
        } catch (LinkageError error) {
            throw new IllegalStateException("WorldBorder API 无法加载", error);
        }
    }

    private void lock(String reason, List<String> details) {
        List<String> copy = new ArrayList<>(details);
        copy.add("LOCKED " + reason);
        gateStatus.set(new GateStatus(GateStatus.State.LOCKED, copy));
        getLogger().severe(reason + "；TianjiTown 所有写功能保持锁定。使用 /townadmin status 查看详情。");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }
}

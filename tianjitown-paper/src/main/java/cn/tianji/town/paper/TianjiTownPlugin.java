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
import org.bukkit.configuration.ConfigurationSection;
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
    private static final int CONFIG_SCHEMA = 7;
    private final AtomicReference<GateStatus> gateStatus = new AtomicReference<>(
            new GateStatus(GateStatus.State.CHECKING, List.of("尚未开始")));
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private final AsyncTaskTracker asyncTasks = new AsyncTaskTracker();
    private volatile DatabaseGate databaseGate;
    private volatile ExecutorService asyncExecutor;
    private volatile TownRuntime townRuntime;
    private volatile TownActions townActions;
    private volatile TownUiController townUi;
    private volatile TownAdminTabCompleter townAdminTabCompleter;

    @Override
    public void onEnable() {
        long generation = lifecycleGeneration.incrementAndGet();
        asyncTasks.startAccepting();
        asyncExecutor = Executors.newFixedThreadPool(4,
                Thread.ofPlatform().daemon(true).name("TianjiTown-Async-", 0).factory());
        saveDefaultConfig();
        org.bukkit.command.PluginCommand adminCommand = java.util.Objects.requireNonNull(
                getCommand("townadmin"), "plugin.yml 缺少 townadmin");
        TownAdminTabCompleter completer = new TownAdminTabCompleter(this);
        townAdminTabCompleter = completer;
        adminCommand.setExecutor(new TownAdminCommand(this));
        adminCommand.setTabCompleter(completer);
        org.bukkit.command.PluginCommand testCommand = java.util.Objects.requireNonNull(
                getCommand("testcommand"), "plugin.yml 缺少 testcommand");
        TestCommand testExecutor = new TestCommand(this);
        testCommand.setExecutor(testExecutor);
        testCommand.setTabCompleter(testExecutor);
        configureTestCommandVisibility();

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
            runtime.bonuses().clearAll();
            runtime.buffs().clearAll();
        }
        if (databaseGate != null) {
            databaseGate.close();
            databaseGate = null;
        }
        townRuntime = null;
        townActions = null;
        townUi = null;
        townAdminTabCompleter = null;
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

    void runAsync(Runnable task) {
        ExecutorService executor = asyncExecutor;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(() -> {
                if (!asyncTasks.begin()) {
                    return;
                }
                try {
                    if (isEnabled()) {
                        task.run();
                    }
                } finally {
                    asyncTasks.complete();
                }
            });
        } catch (RejectedExecutionException exception) {
            if (!executor.isShutdown()) {
                throw exception;
            }
        }
    }

    void configureTestCommandVisibility() {
        org.bukkit.command.PluginCommand command = getCommand("testcommand");
        if (command != null) {
            command.setPermission(getConfig().getBoolean("test-command.enabled", false)
                    ? null : TestCommand.PERMISSION);
        }
    }

    private boolean checkDependencies(List<String> details) {
        boolean healthy = true;
        details.add("INFO Minecraft " + getServer().getMinecraftVersion()
                + " / Java " + Runtime.version().feature());
        for (String name : List.of("Residence", "Vault", "XConomy", "QuickShop-Hikari",
                "Jobs", "GlobalMarketPlus", "WorldBorder")) {
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
            getServer().getScheduler().runTask(this, () -> activateRuntime(candidate, details,
                    result.detail(), generation));
        } catch (RuntimeException exception) {
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
        if (configured == CONFIG_SCHEMA - 1) {
            upgradeConfigFromSix();
            getConfig().set("schema-version", CONFIG_SCHEMA);
            saveConfig();
            details.add("OK config schema 已安全升级 " + configured + " -> " + CONFIG_SCHEMA);
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

    private void upgradeConfigFromSix() {
        getConfig().options().copyDefaults(true);
        getConfig().set("phase5.building-refund.chance", 0.25D);
        for (String path : List.of(
                "phase1.application.cooldown-minutes",
                "phase1.site.require-service-area",
                "phase1.site.service-areas",
                "phase3.tax.maximum-basis-points",
                "phase3.expansion.growth-factor",
                "phase3.expansion.maximum-units",
                "phase5.beacon.scan-interval-ticks")) {
            getConfig().set(path, null);
        }
        ConfigurationSection catalog = getConfig().getConfigurationSection("phase4.buffs.catalog");
        if (catalog != null) {
            for (String key : catalog.getKeys(false)) {
                getConfig().set("phase4.buffs.catalog." + key + ".price-multiplier", null);
                getConfig().set("phase4.buffs.catalog." + key + ".duration-minutes", null);
                getConfig().set("phase4.buffs.catalog." + key + ".allowed-worlds", null);
            }
        }
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
        } catch (RuntimeException exception) {
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
                new ResidenceCommandGuard(managedResidenceNames::contains), this);
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
        runtime.bonuses().recoverTaggedBeacons();
        runtime.bonuses().refreshIndex();
        getServer().getScheduler().runTaskTimer(this, runtime::checkRecovery, 20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, runtime::reconcileAll, 20L * 10,
                20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this, runtime::settleDueVotes, 20L * 30,
                20L * 60);
        getServer().getScheduler().runTaskTimer(this, runtime::reconcileSettlement, 20L * 20,
                20L * 60 * Math.max(1,
                        getConfig().getLong("phase3.reconciliation-interval-minutes", 5)));
        getServer().getScheduler().runTaskTimer(this, runtime.buffs()::cleanupExpired,
                20L * 30, 20L * 60);
        getServer().getScheduler().runTaskTimer(this, runtime.bonuses()::refreshIndex,
                20L * 15, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, runtime.bonuses()::refreshBeaconEffects,
                20L * 10, runtime.bonuses().settings().beacon().refreshIntervalTicks());
        getServer().getScheduler().runTaskTimer(this, runtime.bonuses()::cleanupCounters,
                20L * 60, 20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this, runtime.bonuses()::diagnoseScheduled,
                20L * 60 * 5, 20L * 60
                        * runtime.bonuses().settings().operations().diagnosticsInterval().toMinutes());
        if (runtime.bonuses().settings().operations().backup().enabled()) {
            getServer().getScheduler().runTaskTimer(this,
                    runtime.bonuses()::createScheduledBackup, 20L * 60,
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
        details.add("OK 玩家界面=" + TownUiMode.load(getConfig()));
        details.add("OK 建筑返还、信标增强、统一诊断与定时备份已启用");
        gateStatus.set(new GateStatus(GateStatus.State.READY, details));
        getLogger().info("业务运行时启动完成；玩家入口仅限服务台和小镇手册。");
    }

    private boolean isCurrentLifecycle(long generation) {
        return isEnabled() && lifecycleGeneration.get() == generation;
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
}

package cn.tianji.town.paper;

import cn.tianji.town.core.ports.RegionBoundaryService;
import cn.tianji.town.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import cn.tianji.town.integrations.jobs.JobsIncomeTaxAdapter;
import cn.tianji.town.integrations.residence.ResidenceCommandGuard;
import cn.tianji.town.integrations.residence.ResidenceLandProtectionService;
import cn.tianji.town.integrations.quickshop.QuickShopTaxAdapter;
import cn.tianji.town.integrations.vault.VaultEconomyProbe;
import cn.tianji.town.integrations.worldguard.WorldGuardRegionBoundaryService;
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
import java.util.concurrent.atomic.AtomicReference;

public final class TianjiTownPlugin extends JavaPlugin {
    private static final int CONFIG_SCHEMA = 6;
    private final AtomicReference<GateStatus> gateStatus = new AtomicReference<>(
            new GateStatus(GateStatus.State.CHECKING, List.of("尚未开始")));
    private volatile DatabaseGate databaseGate;
    private volatile PhaseOneRuntime phaseOneRuntime;
    private volatile TownUiController townUi;
    private volatile TownAdminTabCompleter townAdminTabCompleter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
        boolean dependenciesHealthy = checkDependencies(synchronousChecks);
        gateStatus.set(new GateStatus(GateStatus.State.CHECKING, synchronousChecks));

        if (!dependenciesHealthy) {
            lock("同步门禁未通过", synchronousChecks);
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> checkDatabase(synchronousChecks));
    }

    @Override
    public void onDisable() {
        PhaseOneRuntime runtime = phaseOneRuntime;
        if (runtime != null) {
            runtime.phaseFive().clearAll();
            runtime.phaseFour().clearAll();
        }
        if (databaseGate != null) {
            databaseGate.close();
            databaseGate = null;
        }
        phaseOneRuntime = null;
        townUi = null;
        townAdminTabCompleter = null;
    }

    public GateStatus gateStatus() {
        return gateStatus.get();
    }

    PhaseOneRuntime phaseOneRuntime() {
        return phaseOneRuntime;
    }

    TownUiController townUi() {
        return townUi;
    }

    private boolean checkDependencies(List<String> details) {
        boolean healthy = true;
        details.add("INFO Minecraft " + getServer().getMinecraftVersion()
                + " / Java " + Runtime.version().feature());
        for (String name : List.of("Residence", "Vault", "XConomy", "QuickShop-Hikari",
                "Jobs", "GlobalMarketPlus")) {
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
        Plugin worldGuard = getServer().getPluginManager().getPlugin("WorldGuard");
        details.add(worldGuard != null && worldGuard.isEnabled()
                ? "OK WorldGuard " + worldGuard.getPluginMeta().getVersion()
                + "（选址边界检测已启用）"
                : "INFO WorldGuard 未安装（跳过外部区域边界检测）");

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

    private void checkDatabase(List<String> previousChecks) {
        List<String> details = new ArrayList<>(previousChecks);
        try {
            DatabaseConfig config = new DatabaseConfig(
                    resolveDatabaseUrl(),
                    Duration.ofMillis(getConfig().getLong("database.connection-timeout-ms", 5000)),
                    Duration.ofMillis(getConfig().getLong("database.busy-timeout-ms", 5000)));
            DatabaseGate candidate = new DatabaseGate(config);
            DatabaseGate.HealthResult result = candidate.verifyAndMigrate();
            if (!result.healthy()) {
                candidate.close();
                details.add("FAIL SQLite/Flyway: " + result.detail());
                lock("数据库门禁未通过", details);
                return;
            }
            if (!isEnabled()) {
                candidate.close();
                return;
            }
            getServer().getScheduler().runTask(this, () -> activatePhaseOne(candidate, details,
                    result.detail()));
        } catch (RuntimeException exception) {
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
            upgradeConfigFromFive();
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
                    + CONFIG_SCHEMA + "；请先按对应阶段手册升级");
        }
        return false;
    }

    private void upgradeConfigFromFive() {
        getConfig().options().copyDefaults(true);

        // 清理第六版已移除或已更名的功能配置，避免旧字段继续误导服主。
        for (String path : List.of(
                "phase4.resources",
                "phase5.building-refund.daily-limit",
                "phase5.building-refund.counter-retention-days",
                "phase5.building-refund.materials",
                "phase5.beacon.range-multiplier",
                "phase5.beacon.maximum-range",
                "phase5.beacon.maximum-tier",
                "phase5.beacon.effect-level-bonus",
                "phase5.beacon.maximum-effect-level")) {
            getConfig().set(path, null);
        }

        ConfigurationSection catalog = getConfig().getConfigurationSection(
                "phase4.buffs.catalog");
        if (catalog == null) {
            return;
        }
        for (String key : catalog.getKeys(false)) {
            String path = "phase4.buffs.catalog." + key + ".purchasing-roles";
            List<String> roles = getConfig().getStringList(path);
            List<String> migrated = new ArrayList<>();
            for (String role : roles) {
                String mapped = role.equals("OFFICER") ? "DEPUTY_MAYOR" : role;
                if (!migrated.contains(mapped)) {
                    migrated.add(mapped);
                }
            }
            if (migrated.contains("MAYOR") && !migrated.contains("DEPUTY_MAYOR")) {
                migrated.add("DEPUTY_MAYOR");
            }
            getConfig().set(path, migrated);
        }
    }

    private String resolveDatabaseUrl() {
        String configured = getConfig().getString("database.file", "tianjitown.db");
        if (configured == null || configured.isBlank()) {
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

    private void activatePhaseOne(DatabaseGate candidate, List<String> previousDetails,
                                  String databaseDetail) {
        if (!isEnabled()) {
            candidate.close();
            return;
        }
        Set<String> managedResidenceNames = ConcurrentHashMap.newKeySet();
        PhaseOneRuntime runtime;
        try {
            runtime = new PhaseOneRuntime(this, candidate,
                    new ResidenceLandProtectionService(getServer(), managedResidenceNames),
                    regionBoundaryService());
            cn.tianji.town.integrations.vault.VaultSettlementService.Result settlement =
                    runtime.settlement().ensureAccount();
            if (!settlement.success()) {
                throw new IllegalStateException(settlement.message());
            }
        } catch (RuntimeException exception) {
            candidate.close();
            List<String> details = new ArrayList<>(previousDetails);
            details.add("FAIL 阶段3/4/5配置或清算账户: " + exception.getMessage());
            lock("阶段5运行时门禁未通过", details);
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
        TownUiController ui = new TownUiController(this, runtime);
        phaseOneRuntime = runtime;
        townUi = ui;
        TownAdminTabCompleter completer = townAdminTabCompleter;
        if (completer != null) {
            completer.start(runtime);
        }
        getServer().getPluginManager().registerEvents(ui, this);
        getServer().getPluginManager().registerEvents(runtime.phaseFour(), this);
        getServer().getPluginManager().registerEvents(runtime.phaseFive(), this);
        getServer().getPluginManager().registerEvents(
                new ResidenceCommandGuard(managedResidenceNames::contains), this);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                runtime.repository().listTowns(true).stream().map(town -> town.residenceName())
                        .forEach(managedResidenceNames::add);
            } catch (RuntimeException exception) {
                getLogger().severe("读取系统 Residence 名称清单失败: " + exception.getMessage());
            }
        });
        runtime.recoverStartupState();
        runtime.phaseFive().recoverTaggedBeacons();
        runtime.phaseFive().refreshIndex();
        getServer().getScheduler().runTaskTimer(this, runtime::checkRecovery, 20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, runtime::reconcileAll, 20L * 10,
                20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this, runtime::settleDueVotes, 20L * 30,
                20L * 60);
        getServer().getScheduler().runTaskTimer(this, runtime::reconcileSettlement, 20L * 20,
                20L * 60 * Math.max(1,
                        getConfig().getLong("phase3.reconciliation-interval-minutes", 5)));
        getServer().getScheduler().runTaskTimer(this, runtime.phaseFour()::cleanupExpired,
                20L * 30, 20L * 60);
        getServer().getScheduler().runTaskTimer(this, runtime.phaseFive()::refreshIndex,
                20L * 15, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, runtime.phaseFive()::scanBeacons,
                20L * 10, runtime.phaseFive().settings().beacon().scanIntervalTicks());
        getServer().getScheduler().runTaskTimer(this, runtime.phaseFive()::cleanupCounters,
                20L * 60, 20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this, runtime.phaseFive()::diagnoseScheduled,
                20L * 60 * 5, 20L * 60
                        * runtime.phaseFive().settings().operations().diagnosticsInterval().toMinutes());
        if (runtime.phaseFive().settings().operations().backup().enabled()) {
            getServer().getScheduler().runTaskTimer(this,
                    runtime.phaseFive()::createScheduledBackup, 20L * 60,
                    20L * 60 * 60 * runtime.phaseFive().settings().operations()
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
        details.add("OK 阶段5建筑返还、信标增强、统一诊断与定时备份已启用");
        gateStatus.set(new GateStatus(GateStatus.State.READY, details));
        getLogger().info("阶段5启动完成；玩家入口仅限服务台和小镇手册。");
    }

    private RegionBoundaryService regionBoundaryService() {
        if (!getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
            return (territory, bufferChunks) -> RegionBoundaryService.Collision.none();
        }
        try {
            return new WorldGuardRegionBoundaryService(getServer());
        } catch (LinkageError error) {
            getLogger().warning("WorldGuard API 无法加载，已停用外部区域边界检测: "
                    + error.getMessage());
            return (territory, bufferChunks) -> RegionBoundaryService.Collision.none();
        }
    }

    private void lock(String reason, List<String> details) {
        List<String> copy = new ArrayList<>(details);
        copy.add("LOCKED " + reason);
        gateStatus.set(new GateStatus(GateStatus.State.LOCKED, copy));
        getLogger().severe(reason + "；TianjiTown 所有写功能保持锁定。使用 /townadmin status 查看详情。");
    }
}

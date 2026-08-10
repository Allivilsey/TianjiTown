package cn.tianji.town.paper;

import cn.tianji.town.core.ports.RegionBoundaryService;
import cn.tianji.town.integrations.residence.ResidenceCommandGuard;
import cn.tianji.town.integrations.residence.ResidenceLandProtectionService;
import cn.tianji.town.integrations.vault.VaultEconomyProbe;
import cn.tianji.town.integrations.worldguard.WorldGuardRegionBoundaryService;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
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
    private final AtomicReference<GateStatus> gateStatus = new AtomicReference<>(
            new GateStatus(GateStatus.State.CHECKING, List.of("尚未开始")));
    private volatile DatabaseGate databaseGate;
    private volatile PhaseOneRuntime phaseOneRuntime;
    private volatile TownUiController townUi;
    private volatile TownAdminTabCompleter townAdminTabCompleter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        List<String> synchronousChecks = new ArrayList<>();
        boolean dependenciesHealthy = checkDependencies(synchronousChecks);
        gateStatus.set(new GateStatus(GateStatus.State.CHECKING, synchronousChecks));

        org.bukkit.command.PluginCommand adminCommand = java.util.Objects.requireNonNull(
                getCommand("townadmin"), "plugin.yml 缺少 townadmin");
        TownAdminTabCompleter completer = new TownAdminTabCompleter(this);
        townAdminTabCompleter = completer;
        adminCommand.setExecutor(new TownAdminCommand(this));
        adminCommand.setTabCompleter(completer);

        if (!dependenciesHealthy) {
            lock("同步门禁未通过", synchronousChecks);
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, () -> checkDatabase(synchronousChecks));
    }

    @Override
    public void onDisable() {
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
        for (String name : List.of("Residence", "Vault", "XConomy", "QuickShop-Hikari")) {
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
        databaseGate = candidate;
        Set<String> managedResidenceNames = ConcurrentHashMap.newKeySet();
        PhaseOneRuntime runtime = new PhaseOneRuntime(this, candidate,
                new ResidenceLandProtectionService(getServer(), managedResidenceNames),
                regionBoundaryService());
        TownUiController ui = new TownUiController(this, runtime);
        phaseOneRuntime = runtime;
        townUi = ui;
        TownAdminTabCompleter completer = townAdminTabCompleter;
        if (completer != null) {
            completer.start(runtime);
        }
        getServer().getPluginManager().registerEvents(ui, this);
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
        getServer().getScheduler().runTaskTimer(this, runtime::checkRecovery, 20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, runtime::reconcileAll, 20L * 10,
                20L * 60 * 60);
        getServer().getScheduler().runTaskTimer(this, runtime::settleDueVotes, 20L * 30,
                20L * 60);
        List<String> details = new ArrayList<>(previousDetails);
        details.add("OK " + databaseDetail);
        details.add("OK 阶段2成员治理、投票结算与 Residence 投影已启用");
        gateStatus.set(new GateStatus(GateStatus.State.READY, details));
        getLogger().info("阶段2启动完成；玩家入口仅限服务台和小镇手册。");
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

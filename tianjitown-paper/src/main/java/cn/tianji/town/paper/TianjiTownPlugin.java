package cn.tianji.town.paper;

import cn.tianji.town.integrations.DependencyVersions;
import cn.tianji.town.integrations.residence.ResidenceCommandGuard;
import cn.tianji.town.integrations.residence.ResidenceLandProtectionService;
import cn.tianji.town.integrations.vault.VaultEconomyProbe;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        boolean dependenciesHealthy = checkRuntimeAndDependencies(synchronousChecks);
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

    private boolean checkRuntimeAndDependencies(List<String> details) {
        boolean healthy = true;
        String expectedMinecraft = getConfig().getString("version-lock.minecraft", "");
        String actualMinecraft = getServer().getMinecraftVersion();
        if (!expectedMinecraft.equals(actualMinecraft)) {
            details.add("FAIL Minecraft: expected=" + expectedMinecraft + ", actual=" + actualMinecraft);
            healthy = false;
        } else {
            details.add("OK Minecraft " + actualMinecraft + "（仅使用 Paper API）");
        }

        int expectedJava = getConfig().getInt("version-lock.java-feature", 21);
        int actualJava = Runtime.version().feature();
        if (expectedJava != actualJava) {
            details.add("FAIL Java: expected=" + expectedJava + ", actual=" + actualJava);
            healthy = false;
        } else {
            details.add("OK Java " + actualJava);
        }

        ConfigurationSection section = getConfig().getConfigurationSection("version-lock.plugins");
        Map<String, String> expected = new LinkedHashMap<>();
        if (section != null) {
            section.getKeys(false).forEach(name -> expected.put(name, section.getString(name, "")));
        }
        Map<String, DependencyVersions.Check> checks = new DependencyVersions(getServer().getPluginManager())
                .verify(expected);
        for (Map.Entry<String, DependencyVersions.Check> entry : checks.entrySet()) {
            DependencyVersions.Check check = entry.getValue();
            details.add((check.healthy() ? "OK " : "FAIL ") + entry.getKey() + " expected="
                    + check.expected() + ", actual=" + check.actual() + " (" + check.message() + ")");
            healthy &= check.healthy();
        }

        VaultEconomyProbe.Result economy = new VaultEconomyProbe(getServer()).verify();
        details.add((economy.healthy() ? "OK " : "FAIL ") + "Vault Economy provider="
                + economy.provider() + " (" + economy.message() + ")");
        return healthy && economy.healthy();
    }

    private void checkDatabase(List<String> previousChecks) {
        List<String> details = new ArrayList<>(previousChecks);
        try {
            EnvironmentExpander expander = new EnvironmentExpander();
            DatabaseConfig config = new DatabaseConfig(
                    expander.expand(getConfig().getString("database.jdbc-url", "")),
                    expander.expand(getConfig().getString("database.username", "")),
                    expander.expand(getConfig().getString("database.password", "")),
                    getConfig().getInt("database.pool.maximum-size", 6),
                    getConfig().getInt("database.pool.minimum-idle", 1),
                    Duration.ofMillis(getConfig().getLong("database.pool.connection-timeout-ms", 5000)));
            DatabaseGate candidate = new DatabaseGate(config);
            DatabaseGate.HealthResult result = candidate.verifyAndMigrate();
            if (!result.healthy()) {
                candidate.close();
                details.add("FAIL MySQL/Flyway: " + result.detail());
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
            details.add("FAIL MySQL config: " + exception.getMessage());
            lock("数据库配置无效", details);
        }
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
                new ResidenceLandProtectionService(getServer(), managedResidenceNames));
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
        getServer().getScheduler().runTaskTimer(this, runtime::checkRecovery, 20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, runtime::reconcileAll, 20L * 10,
                20L * 60 * 60);
        List<String> details = new ArrayList<>(previousDetails);
        details.add("OK " + databaseDetail);
        details.add("OK 阶段1玩家 UI、审批事务与 Residence 投影已启用");
        gateStatus.set(new GateStatus(GateStatus.State.READY, details));
        getLogger().info("阶段1启动完成；玩家入口仅限服务台和小镇手册。");
    }

    private void lock(String reason, List<String> details) {
        List<String> copy = new ArrayList<>(details);
        copy.add("LOCKED " + reason);
        gateStatus.set(new GateStatus(GateStatus.State.LOCKED, copy));
        getLogger().severe(reason + "；TianjiTown 所有写功能保持锁定。使用 /townadmin status 查看详情。");
    }
}

package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.phase1.ApplicationSnapshot;
import cn.tianji.town.storage.phase1.PhaseOneRepository;
import cn.tianji.town.storage.phase1.TownSnapshot;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class PhaseOneRuntime {
    private final TianjiTownPlugin plugin;
    private final DatabaseGate database;
    private final PhaseOneRepository repository;
    private final LandProtectionService landProtection;
    private final SitePolicy sitePolicy;
    private final AtomicBoolean databaseAvailable = new AtomicBoolean(true);
    private final ProvisionCoordinator provisions = new ProvisionCoordinator();

    PhaseOneRuntime(TianjiTownPlugin plugin, DatabaseGate database,
                    LandProtectionService landProtection) {
        this.plugin = plugin;
        this.database = database;
        this.landProtection = landProtection;
        this.sitePolicy = new SitePolicy(plugin, landProtection);
        this.repository = new PhaseOneRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
    }

    PhaseOneRepository repository() {
        return repository;
    }

    LandProtectionService landProtection() {
        return landProtection;
    }

    SitePolicy sitePolicy() {
        return sitePolicy;
    }

    boolean databaseAvailable() {
        return databaseAvailable.get();
    }

    void checkRecovery() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean healthy = database.ping();
            boolean previous = databaseAvailable.getAndSet(healthy);
            if (healthy && !previous) {
                plugin.getLogger().info("MySQL 连接已恢复，阶段1写操作重新开放。");
            } else if (!healthy && previous) {
                plugin.getLogger().severe("MySQL 连接中断，阶段1写操作已锁定；Residence 保护保持不变。");
            }
        });
    }

    void reconcileAll() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<TownMembers> states = repository.listTowns(false).stream()
                        .map(town -> new TownMembers(town, repository.listMemberIds(town.id())))
                        .toList();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (TownMembers state : states) {
                        LandProtectionService.Result result = landProtection.reconcile(
                                state.town().residenceName(),
                                state.town().territory(), state.members(), true);
                        if (!result.success()) {
                            plugin.getLogger().severe("Residence 对账失败 " + state.town().id()
                                    + ": " + result.message());
                        }
                        recordLandAudit(null, "SYSTEM", state.town().id(), true, result);
                    }
                });
            } catch (RuntimeException exception) {
                databaseAvailable.set(false);
                plugin.getLogger().severe("Residence 对账读取 MySQL 失败: " + safeMessage(exception));
            }
        });
    }

    <T> void read(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, false, operation, success);
    }

    <T> void write(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, true, operation, success);
    }

    void provision(CommandSender sender, UUID applicationId, UUID reviewerId,
                   String reviewerName, String reason, String idempotencyKey,
                   Consumer<ApplicationSnapshot> completion) {
        if (!databaseAvailable.get()) {
            sender.sendMessage("§cMySQL 当前不可用，写操作已锁定；现有 Residence 保护不受影响。");
            return;
        }
        if (!provisions.tryBegin(applicationId)) {
            sender.sendMessage("§e该申请正在执行建镇流程，本次重复请求已合并。");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PhaseOneRepository.Provisioning provisioning = repository.beginProvision(applicationId,
                        reviewerId, reviewerName, reason, idempotencyKey);
                databaseAvailable.set(true);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> projectProvision(sender, applicationId, provisioning, completion));
            } catch (RuntimeException exception) {
                provisions.finish(applicationId);
                handleFailure(sender, exception);
            }
        });
    }

    private void projectProvision(CommandSender sender, UUID applicationId,
                                  PhaseOneRepository.Provisioning provisioning,
                                  Consumer<ApplicationSnapshot> completion) {
        if (provisioning.town().status() == TownStatus.ACTIVE) {
            provisions.finish(applicationId);
            sender.sendMessage("§a该申请已完成建镇，无需重复批准。");
            return;
        }
        try {
            // 碰撞由创建服务检查，使重试能够识别并复用本镇已经创建的系统投影。
            SitePolicy.Validation validation = sitePolicy.validateEnvironment(
                    provisioning.town().territory());
            LandProtectionService.Result land = validation.valid()
                    ? landProtection.create(provisioning.town().residenceName(),
                    provisioning.town().territory(),
                    provisioning.members())
                    : LandProtectionService.Result.failure("批准时选址复核失败: " + validation.error());
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> finishProvision(sender, applicationId, land, completion));
        } catch (RuntimeException exception) {
            provisions.finish(applicationId);
            handleFailure(sender, exception);
        }
    }

    private void finishProvision(CommandSender sender, UUID applicationId,
                                 LandProtectionService.Result land,
                                 Consumer<ApplicationSnapshot> completion) {
        try {
            boolean completed = land.success();
            String completedDetail = land.message();
            ApplicationSnapshot application = repository.finishProvision(
                    applicationId, completed, completedDetail);
            databaseAvailable.set(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(completed ? "§a小镇已批准并完成 3×3 领地投影。"
                        : "§c自动创建失败，申请已进入 PROVISION_FAILED: " + completedDetail);
                completion.accept(application);
            });
        } catch (RuntimeException exception) {
            handleFailure(sender, exception);
        } finally {
            provisions.finish(applicationId);
        }
    }

    void reconcile(CommandSender sender, TownSnapshot town, List<UUID> members, boolean repair) {
        LandProtectionService.Result result = landProtection.reconcile(town.residenceName(), town.territory(),
                members, repair);
        sender.sendMessage((result.success() ? "§a" : "§c") + town.profile().name()
                + ": " + result.message());
        UUID actorId = sender instanceof org.bukkit.entity.Player player
                ? player.getUniqueId() : null;
        recordLandAudit(actorId, sender.getName(), town.id(), repair, result);
    }

    private void recordLandAudit(UUID actorId, String actorName, UUID townId, boolean repair,
                                 LandProtectionService.Result result) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.recordAudit(actorId, actorName,
                        repair ? "LAND_RECONCILE_REPAIR" : "LAND_RECONCILE_CHECK", "TOWN",
                        townId.toString(), result.success() ? "对账完成" : "对账失败",
                        result.message());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("写入领地对账审计失败: " + safeMessage(exception));
            }
        });
    }

    private <T> void execute(CommandSender sender, boolean write, Supplier<T> operation,
                             Consumer<T> success) {
        if (write && !databaseAvailable.get()) {
            sender.sendMessage("§cMySQL 当前不可用，写操作已锁定；现有 Residence 保护不受影响。");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.getServer().getScheduler().runTask(plugin, () -> success.accept(result));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void handleFailure(CommandSender sender, RuntimeException exception) {
        if (exception instanceof PhaseOneRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
            plugin.getLogger().severe(exception.getMessage());
        }
        plugin.getServer().getScheduler().runTask(plugin,
                () -> sender.sendMessage("§c操作失败: " + safeMessage(exception)));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }
}

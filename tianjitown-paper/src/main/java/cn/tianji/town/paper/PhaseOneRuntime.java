package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.profile.TownProfile;
import cn.tianji.town.core.profile.ProfileChecksum;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.phase1.PhaseOneRepository;
import cn.tianji.town.storage.phase1.TownSnapshot;
import cn.tianji.town.storage.profile.YamlProfileStore;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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
    private final YamlProfileStore profiles;
    private final Path profileDirectory;
    private final AtomicBoolean databaseAvailable = new AtomicBoolean(true);

    PhaseOneRuntime(TianjiTownPlugin plugin, DatabaseGate database,
                    LandProtectionService landProtection) {
        this.plugin = plugin;
        this.database = database;
        this.landProtection = landProtection;
        this.sitePolicy = new SitePolicy(plugin, landProtection);
        this.repository = new PhaseOneRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.profiles = new YamlProfileStore();
        this.profileDirectory = plugin.getDataFolder().toPath().resolve("towns");
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
                        LandProtectionService.Result result = landProtection.reconcile(state.town().id(),
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

    void inspectProfileMirrors() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                for (TownSnapshot town : repository.listTowns(true)) {
                    Path path = profilePath(town.id());
                    try {
                        if (!Files.exists(path)) {
                            exportProfile(town.id());
                            continue;
                        }
                        YamlProfileStore.ReadResult result = profiles.readAndValidate(path);
                        if (!result.valid()) {
                            repository.markProfileManualChange(town.id(), String.join("；", result.errors()));
                            plugin.getLogger().warning("YAML 基本资料待确认 " + town.id() + ": "
                                    + String.join("；", result.errors()));
                            continue;
                        }
                        TownProfile expected = profiles.sign(repository.profileForExport(town.id()));
                        if (result.profile().revision() < expected.revision()) {
                            exportProfile(town.id());
                        } else if (result.profile().revision() > expected.revision()
                                || !ProfileChecksum.matches(result.profile())
                                || !expected.checksum().equals(result.profile().checksum())) {
                            repository.markProfileManualChange(town.id(), "内容与 MySQL 当前资料不一致");
                            plugin.getLogger().warning("YAML 基本资料与 MySQL 不一致，未自动覆盖: "
                                    + town.id());
                        }
                    } catch (IOException | RuntimeException exception) {
                        repository.markProfileFailed(town.id(), safeMessage(exception));
                        plugin.getLogger().warning("检查 YAML 基本资料失败 " + town.id() + ": "
                                + safeMessage(exception));
                    }
                }
            } catch (RuntimeException exception) {
                databaseAvailable.set(false);
                plugin.getLogger().warning("读取 YAML 同步清单失败: " + safeMessage(exception));
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
                   String reviewerName, String reason, String idempotencyKey) {
        write(sender, () -> repository.beginProvision(applicationId, reviewerId, reviewerName,
                reason, idempotencyKey), provisioning -> {
            SitePolicy.Validation validation = sitePolicy.validate(provisioning.town().territory());
            LandProtectionService.Result land = validation.valid()
                    ? landProtection.create(provisioning.town().id(), provisioning.town().territory(),
                    provisioning.members())
                    : LandProtectionService.Result.failure("批准时选址复核失败: " + validation.error());
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                String detail = land.message();
                boolean success = land.success();
                if (success) {
                    try {
                        exportProfile(provisioning.town().id());
                    } catch (RuntimeException | IOException exception) {
                        success = false;
                        detail = "YAML 基本资料导出失败: " + exception.getMessage();
                        repository.markProfileFailed(provisioning.town().id(), detail);
                    }
                }
                boolean completed = success;
                String completedDetail = detail;
                try {
                    repository.finishProvision(applicationId, completed, completedDetail);
                    databaseAvailable.set(true);
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                            completed ? "§a小镇已批准并完成 3×3 领地投影。"
                                    : "§c自动创建失败，申请已进入 PROVISION_FAILED: "
                                    + completedDetail));
                } catch (RuntimeException exception) {
                    handleFailure(sender, exception);
                }
            });
        });
    }

    TownProfile exportProfile(UUID townId) throws IOException {
        TownProfile profile = repository.profileForExport(townId);
        Path target = profilePath(townId);
        profiles.writeAtomically(target, profile);
        TownProfile signed = profiles.sign(profile);
        repository.markProfileSynced(townId, profile.revision(), signed.checksum());
        return signed;
    }

    YamlProfileStore.ReadResult validateProfile(UUID townId) throws IOException {
        return profiles.readAndValidate(profilePath(townId));
    }

    TownSnapshot importProfile(UUID townId, UUID actorId, String actorName) throws IOException {
        Path source = profilePath(townId);
        YamlProfileStore.ReadResult result = profiles.readForImport(source);
        if (!result.valid()) {
            throw new IllegalArgumentException(String.join("；", result.errors()));
        }
        TownProfile imported = result.profile();
        if (!townId.equals(imported.townId())) {
            throw new IllegalArgumentException("YAML town-id 与命令目标不一致");
        }
        TownSnapshot current = repository.findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("找不到小镇 " + townId));
        TownProfile canonical = repository.profileForExport(townId);
        if (imported.revision() != canonical.revision()) {
            throw new IllegalArgumentException("YAML revision 与 MySQL 当前 revision 不一致");
        }
        if (!imported.createdAt().equals(canonical.createdAt())) {
            throw new IllegalArgumentException("禁止修改 created-at");
        }
        Path backup = source.resolveSibling(source.getFileName() + ".bak-" + Instant.now().toEpochMilli());
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        TownSnapshot updated = repository.updateTownProfile(townId,
                new cn.tianji.town.core.application.ApplicationText(imported.name(),
                        imported.shortName(), imported.description(), imported.rules()),
                current.version(), actorId, actorName, "管理员从 YAML 手工导入");
        exportProfile(townId);
        return updated;
    }

    void reconcile(CommandSender sender, TownSnapshot town, List<UUID> members, boolean repair) {
        LandProtectionService.Result result = landProtection.reconcile(town.id(), town.territory(),
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

    private Path profilePath(UUID townId) {
        return profileDirectory.resolve(townId + ".yml");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }
}

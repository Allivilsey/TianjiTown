package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.integrations.residence.ResidenceSmokeTest;
import cn.tianji.town.storage.phase1.ApplicationSnapshot;
import cn.tianji.town.storage.phase1.AuditSnapshot;
import cn.tianji.town.storage.phase1.PhaseOneRepository;
import cn.tianji.town.storage.phase1.TownSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class TownAdminCommand implements CommandExecutor {
    private static final UUID CONSOLE_ID = new UUID(0, 0);
    private final TianjiTownPlugin plugin;
    private final PhaseZeroCommand phaseZeroCommand;

    TownAdminCommand(TianjiTownPlugin plugin) {
        this.plugin = plugin;
        this.phaseZeroCommand = new PhaseZeroCommand(plugin, new ResidenceSmokeTest());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("tianjitown.admin")) {
            sender.sendMessage("§c没有权限。");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        try {
            if (root.equals("status")) {
                status(sender);
                return true;
            }
            if (root.equals("reload")) {
                plugin.reloadConfig();
                sender.sendMessage("§a配置已重新读取。版本锁和数据库连接参数需重启后生效。");
                return true;
            }
            if (root.equals("phase0")) {
                return phaseZeroCommand.onCommand(sender, command, label, args);
            }
            PhaseOneRuntime runtime = requireRuntime(sender);
            if (runtime == null) {
                return true;
            }
            return switch (root) {
                case "audit" -> audit(sender, runtime, args);
                case "station" -> station(sender, args);
                case "handbook" -> handbook(sender, args);
                case "application" -> application(sender, runtime, args);
                case "town" -> town(sender, runtime, args);
                case "member" -> member(sender, runtime, args);
                case "mayor" -> mayor(sender, runtime, args);
                case "archive" -> archive(sender, runtime, args);
                case "land" -> land(sender, runtime, args);
                case "data" -> data(sender, runtime, args);
                default -> {
                    help(sender);
                    yield true;
                }
            };
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c参数错误: " + exception.getMessage());
            return true;
        }
    }

    private void status(CommandSender sender) {
        GateStatus status = plugin.gateStatus();
        sender.sendMessage("§6TianjiTown 1.0.0: §f" + status.state());
        status.details().forEach(detail -> sender.sendMessage("§7- " + detail));
        PhaseOneRuntime runtime = plugin.phaseOneRuntime();
        if (runtime != null) {
            sender.sendMessage("§7- MySQL 运行状态: "
                    + (runtime.databaseAvailable() ? "READY" : "WRITE_LOCKED"));
        }
    }

    private boolean audit(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        int limit = args.length >= 2 ? Integer.parseInt(args[1]) : 20;
        runtime.read(sender, () -> runtime.repository().auditLog(limit), records -> {
            sender.sendMessage("§6最近审计记录:");
            for (AuditSnapshot record : records) {
                sender.sendMessage("§7#" + record.id() + " " + record.createdAt() + " "
                        + record.actorName() + " " + record.action() + " " + record.targetType()
                        + "/" + record.targetId() + " 原因=" + record.reason());
            }
        });
        return true;
    }

    private boolean station(CommandSender sender, String[] args) {
        if (args.length != 2 || !args[1].equalsIgnoreCase("create") || !(sender instanceof Player player)) {
            sender.sendMessage("§e/townadmin station create（玩家看向讲台）");
            return true;
        }
        plugin.townUi().createStation(player);
        return true;
    }

    private boolean handbook(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else {
            target = sender instanceof Player player ? player : null;
        }
        if (target == null) {
            sender.sendMessage("§c目标玩家必须在线。用法: /townadmin handbook <player>");
            return true;
        }
        plugin.townUi().giveHandbook(target);
        if (!sender.equals(target)) {
            sender.sendMessage("§a已向 " + target.getName() + " 发放小镇手册。");
        }
        return true;
    }

    private boolean application(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        if (args.length < 2) {
            applicationHelp(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            runtime.read(sender, () -> runtime.repository().listReviewQueue(100), applications -> {
                sender.sendMessage("§6待审核/待恢复申请: " + applications.size());
                applications.forEach(app -> sender.sendMessage("§7" + app.id() + " "
                        + app.status() + " " + app.text().name() + " applicant=" + app.applicantId()));
            });
            return true;
        }
        requireLength(args, 3, "application " + action + " <applicationUuid>");
        UUID applicationId = UUID.fromString(args[2]);
        if (action.equals("view")) {
            runtime.read(sender, () -> requireApplication(runtime, applicationId),
                    application -> showApplication(sender, application));
            return true;
        }
        if (action.equals("review")) {
            runtime.write(sender, () -> runtime.repository().beginReview(applicationId,
                    actorId(sender), sender.getName()), application -> {
                sender.sendMessage("§a申请已进入 UNDER_REVIEW。");
                showApplication(sender, application);
            });
            return true;
        }
        String reason = joinReason(args, 3);
        if (action.equals("changes")) {
            runtime.write(sender, () -> runtime.repository().requestChanges(applicationId,
                    actorId(sender), sender.getName(), reason), application ->
                    sender.sendMessage("§a已要求申请人补充资料。"));
        } else if (action.equals("reject")) {
            runtime.write(sender, () -> runtime.repository().reject(applicationId, actorId(sender),
                    sender.getName(), reason), application ->
                    sender.sendMessage("§a申请已拒绝，选址预留已释放。"));
        } else if (action.equals("approve") || action.equals("retry")) {
            String key = action.equals("approve") ? "phase1:approve:" + applicationId
                    : "phase1:retry:" + applicationId + ":" + UUID.randomUUID();
            runtime.provision(sender, applicationId, actorId(sender), sender.getName(), reason, key);
        } else {
            applicationHelp(sender);
        }
        return true;
    }

    private boolean town(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 3, "town view <townUuid>");
        if (!args[1].equalsIgnoreCase("view")) {
            throw new IllegalArgumentException("用法: /townadmin town view <townUuid>");
        }
        UUID townId = UUID.fromString(args[2]);
        runtime.read(sender, () -> requireTown(runtime, townId), town -> {
            sender.sendMessage("§6" + town.profile().name() + " [" + town.profile().shortName() + "]");
            sender.sendMessage("§7ID=" + town.id() + " status=" + town.status()
                    + " mayor=" + town.mayorId() + " version=" + town.version());
            if (town.territory() != null) {
                sender.sendMessage("§7领地=" + town.territory().center().worldName() + " "
                        + town.territory().center().x() + "," + town.territory().center().z()
                        + " projection=" + town.projectionStatus());
            }
        });
        return true;
    }

    private boolean member(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 5, "member <invite|add|remove> <townUuid> <player> <reason>");
        String action = args[1].toLowerCase(Locale.ROOT);
        UUID townId = UUID.fromString(args[2]);
        UUID playerId = playerId(args[3]);
        String reason = joinReason(args, 4);
        if (action.equals("invite")) {
            runtime.write(sender, () -> runtime.repository().adminInvite(townId, playerId,
                    actorId(sender), sender.getName(), java.time.Duration.ofDays(7), reason), invitation ->
                    sender.sendMessage("§a管理员邀请已创建，到期时间: " + invitation.expiresAt()));
        } else if (action.equals("add")) {
            runtime.write(sender, () -> {
                runtime.repository().addMember(townId, playerId, actorId(sender), sender.getName(), reason);
                return townId;
            }, id -> {
                sender.sendMessage("§a成员已添加，正在同步 Residence 权限。");
                reconcileOne(sender, runtime, id, true);
            });
        } else if (action.equals("remove")) {
            runtime.write(sender, () -> {
                runtime.repository().removeMember(townId, playerId, actorId(sender), sender.getName(),
                        reason);
                return townId;
            }, id -> {
                sender.sendMessage("§a成员已移除，正在同步 Residence 权限。");
                reconcileOne(sender, runtime, id, true);
            });
        } else {
            throw new IllegalArgumentException("member 只支持 invite、add 或 remove");
        }
        return true;
    }

    private boolean mayor(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 5, "mayor transfer <townUuid> <player> <reason>");
        if (!args[1].equalsIgnoreCase("transfer")) {
            throw new IllegalArgumentException("用法: /townadmin mayor transfer <townUuid> <player> <reason>");
        }
        UUID townId = UUID.fromString(args[2]);
        UUID newMayor = playerId(args[3]);
        String reason = joinReason(args, 4);
        runtime.write(sender, () -> {
            runtime.repository().transferMayor(townId, newMayor, actorId(sender), sender.getName(), reason);
            return townId;
        }, id -> {
            sender.sendMessage("§a镇长已紧急转移。");
            reconcileOne(sender, runtime, id, true);
        });
        return true;
    }

    private boolean archive(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 3, "archive <townUuid> <reason>");
        UUID townId = UUID.fromString(args[1]);
        String reason = joinReason(args, 2);
        runtime.write(sender, () -> {
            TownSnapshot town = requireTown(runtime, townId);
            runtime.repository().archiveTown(townId, actorId(sender), sender.getName(), reason);
            return town;
        }, town -> {
            LandProtectionService.Result result = runtime.landProtection().remove(town.id(), town.territory());
            sender.sendMessage((result.success() ? "§a" : "§c")
                    + "小镇已归档；Residence: " + result.message());
        });
        return true;
    }

    private boolean land(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 3, "land <preview|reconcile|rebuild> <townUuid|all>");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("preview")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c领地粒子预览只能由游戏内玩家执行。");
                return true;
            }
            UUID townId = UUID.fromString(args[2]);
            runtime.read(sender, () -> requireTown(runtime, townId),
                    town -> plugin.townUi().previewTownForAdmin(player, town));
            return true;
        }
        boolean repair = action.equals("rebuild") || contains(args, "--repair");
        if (action.equals("rebuild") && !contains(args, "--confirm")) {
            sender.sendMessage("§c重建需要 --confirm；未修改 Residence。");
            return true;
        }
        if (!action.equals("reconcile") && !action.equals("rebuild")) {
            throw new IllegalArgumentException("land 只支持 preview/reconcile/rebuild");
        }
        if (args[2].equalsIgnoreCase("all")) {
            runtime.read(sender, () -> {
                List<TownMembers> states = new ArrayList<>();
                for (TownSnapshot town : runtime.repository().listTowns(false)) {
                    states.add(new TownMembers(town, runtime.repository().listMemberIds(town.id())));
                }
                return states;
            }, states -> states.forEach(state -> {
                if (action.equals("rebuild")) {
                    LandProtectionService.Result removal = runtime.landProtection()
                            .remove(state.town().id(), state.town().territory());
                    sender.sendMessage((removal.success() ? "§a" : "§c") + removal.message());
                    if (!removal.success()) {
                        return;
                    }
                }
                runtime.reconcile(sender, state.town(), state.members(), repair);
            }));
        } else {
            UUID townId = UUID.fromString(args[2]);
            if (action.equals("rebuild")) {
                runtime.read(sender, () -> new TownMembers(requireTown(runtime, townId),
                        runtime.repository().listMemberIds(townId)), state -> {
                    LandProtectionService.Result removal = runtime.landProtection()
                            .remove(state.town().id(), state.town().territory());
                    sender.sendMessage((removal.success() ? "§a" : "§c") + removal.message());
                    if (removal.success()) {
                        runtime.reconcile(sender, state.town(), state.members(), true);
                    }
                });
            } else {
                reconcileOne(sender, runtime, townId, repair);
            }
        }
        return true;
    }

    private boolean data(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 3, "data <validate|export|import> <townUuid|all>");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("export") && args[2].equalsIgnoreCase("all")) {
            runtime.write(sender, () -> {
                int count = 0;
                for (TownSnapshot town : runtime.repository().listTowns(true)) {
                    try {
                        runtime.exportProfile(town.id());
                        count++;
                    } catch (IOException exception) {
                        runtime.repository().markProfileFailed(town.id(), exception.getMessage());
                        throw new IllegalStateException("导出 " + town.id() + " 失败: "
                                + exception.getMessage(), exception);
                    }
                }
                return count;
            }, count -> sender.sendMessage("§a已导出 " + count + " 个 YAML 基本资料镜像。"));
            return true;
        }
        UUID townId = UUID.fromString(args[2]);
        if (action.equals("validate")) {
            runtime.read(sender, () -> {
                try {
                    return runtime.validateProfile(townId);
                } catch (IOException exception) {
                    throw new IllegalStateException("读取 YAML 失败: " + exception.getMessage(), exception);
                }
            }, result -> {
                if (result.valid()) {
                    sender.sendMessage("§aYAML schema、revision、UUID、字段和 checksum 均有效。");
                } else {
                    sender.sendMessage("§cYAML 校验失败: " + String.join("；", result.errors()));
                }
            });
        } else if (action.equals("export")) {
            runtime.write(sender, () -> {
                try {
                    return runtime.exportProfile(townId);
                } catch (IOException exception) {
                    runtime.repository().markProfileFailed(townId, exception.getMessage());
                    throw new IllegalStateException("导出 YAML 失败: " + exception.getMessage(), exception);
                }
            }, profile -> sender.sendMessage("§a已导出 YAML，revision=" + profile.revision()
                    + " checksum=" + profile.checksum()));
        } else if (action.equals("import")) {
            if (!plugin.getConfig().getBoolean("phase1.maintenance-mode", false)) {
                sender.sendMessage("§cYAML 导入只能在 phase1.maintenance-mode=true 时执行。");
                return true;
            }
            if (!contains(args, "--confirm")) {
                sender.sendMessage("§c导入需要 --confirm；未修改任何数据。");
                return true;
            }
            runtime.write(sender, () -> {
                try {
                    return runtime.importProfile(townId, actorId(sender), sender.getName());
                } catch (IOException exception) {
                    throw new IllegalStateException("导入 YAML 失败: " + exception.getMessage(), exception);
                }
            }, town -> sender.sendMessage("§aYAML 已导入 MySQL，并重新生成 checksum；原文件已备份。"));
        } else {
            throw new IllegalArgumentException("data 只支持 validate/export/import");
        }
        return true;
    }

    private void reconcileOne(CommandSender sender, PhaseOneRuntime runtime, UUID townId,
                              boolean repair) {
        runtime.read(sender, () -> new TownMembers(requireTown(runtime, townId),
                runtime.repository().listMemberIds(townId)),
                state -> runtime.reconcile(sender, state.town(), state.members(), repair));
    }

    private static ApplicationSnapshot requireApplication(PhaseOneRuntime runtime, UUID id) {
        return runtime.repository().findApplication(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到申请 " + id));
    }

    private static TownSnapshot requireTown(PhaseOneRuntime runtime, UUID id) {
        return runtime.repository().findTown(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到小镇 " + id));
    }

    private static void showApplication(CommandSender sender, ApplicationSnapshot application) {
        sender.sendMessage("§6申请 " + application.id() + " §f" + application.text().name());
        sender.sendMessage("§7applicant=" + application.applicantId() + " status="
                + application.status() + " version=" + application.version());
        sender.sendMessage("§7简称=" + application.text().shortName() + " 简介="
                + application.text().description() + " 规则=" + String.join(" | ", application.text().rules()));
        if (application.territory() != null) {
            sender.sendMessage("§7选址=" + application.territory().center().worldName() + " "
                    + application.territory().center().x() + ","
                    + application.territory().center().z() + " 到期="
                    + application.reservationExpiresAt());
        }
        if (application.reviewMessage() != null) {
            sender.sendMessage("§e审核意见=" + application.reviewMessage());
        }
        if (application.lastError() != null) {
            sender.sendMessage("§c创建错误=" + application.lastError());
        }
    }

    private PhaseOneRuntime requireRuntime(CommandSender sender) {
        PhaseOneRuntime runtime = plugin.phaseOneRuntime();
        if (runtime == null) {
            sender.sendMessage("§c阶段1尚未就绪。请先使用 /townadmin status 查看启动门禁。");
        }
        return runtime;
    }

    private static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_ID;
    }

    @SuppressWarnings("deprecation")
    private static UUID playerId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(value);
            return player.getUniqueId();
        }
    }

    private static void requireLength(String[] args, int minimum, String usage) {
        if (args.length < minimum) {
            throw new IllegalArgumentException("用法: /townadmin " + usage);
        }
    }

    private static String joinReason(String[] args, int start) {
        if (args.length <= start) {
            throw new IllegalArgumentException("必须填写原因");
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
        if (reason.isBlank()) {
            throw new IllegalArgumentException("必须填写原因");
        }
        return reason;
    }

    private static boolean contains(String[] args, String value) {
        return java.util.Arrays.stream(args).anyMatch(value::equalsIgnoreCase);
    }

    private static void applicationHelp(CommandSender sender) {
        sender.sendMessage("§e/townadmin application list|view|review <id>");
        sender.sendMessage("§e/townadmin application changes|reject|approve|retry <id> <reason>");
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("§6TianjiTown 1.0.0 管理命令");
        sender.sendMessage("§e/townadmin status | reload | audit [limit]");
        sender.sendMessage("§e/townadmin phase0 status | residence-smoke ...");
        sender.sendMessage("§e/townadmin station create | handbook [player]");
        sender.sendMessage("§e/townadmin application list|view|review|changes|approve|reject|retry ...");
        sender.sendMessage("§e/townadmin town view <townUuid>");
        sender.sendMessage("§e/townadmin member invite|add|remove <townUuid> <player> <reason>");
        sender.sendMessage("§e/townadmin mayor transfer <townUuid> <player> <reason>");
        sender.sendMessage("§e/townadmin archive <townUuid> <reason>");
        sender.sendMessage("§e/townadmin land preview|reconcile|rebuild <townUuid|all> [--repair|--confirm]");
        sender.sendMessage("§e/townadmin data validate|export|import <townUuid|all> [--confirm]");
    }

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }
}

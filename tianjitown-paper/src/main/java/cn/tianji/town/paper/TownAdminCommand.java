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
            if (root.equals("maintenance")) {
                return maintenance(sender, args);
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
                case "land" -> land(sender, runtime, args);
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
        sender.sendMessage("§7- 玩家入口: " + (maintenanceMode() ? "MAINTENANCE" : "OPEN"));
    }

    private boolean maintenance(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage("§6维护模式: §f" + (maintenanceMode() ? "已开启" : "已关闭"));
            return true;
        }
        if (args.length != 2) {
            throw new IllegalArgumentException("用法: /townadmin maintenance <on|off|status>");
        }
        if (args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("§6维护模式: §f" + (maintenanceMode() ? "已开启" : "已关闭"));
            return true;
        }
        boolean enabled;
        if (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("enable")) {
            enabled = true;
        } else if (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("disable")) {
            enabled = false;
        } else {
            throw new IllegalArgumentException("用法: /townadmin maintenance <on|off|status>");
        }
        plugin.getConfig().set("phase1.maintenance-mode", enabled);
        plugin.saveConfig();
        sender.sendMessage(enabled
                ? "§e维护模式已开启；服务台、手册、玩家 GUI 和表单提交现已暂停。"
                : "§a维护模式已关闭；玩家入口已恢复。");
        return true;
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
        if (args.length != 2) {
            stationHelp(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            plugin.townUi().listStations(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该服务台操作需要游戏内管理员看向讲台执行。");
            return true;
        }
        switch (action) {
            case "create" -> plugin.townUi().createStation(player);
            case "remove" -> plugin.townUi().removeStation(player);
            case "info" -> plugin.townUi().showStationInfo(player);
            default -> stationHelp(sender);
        }
        return true;
    }

    private static void stationHelp(CommandSender sender) {
        sender.sendMessage("§e/townadmin station create|remove|info（玩家看向讲台）");
        sender.sendMessage("§e/townadmin station list");
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
            runtime.read(sender, () -> runtime.repository().listReviewQueue(100),
                    applications -> plugin.townUi().showAdminApplicationList(sender, applications));
            return true;
        }
        if (!action.equals("approve") && !action.equals("reject") && !action.equals("change")) {
            applicationHelp(sender);
            return true;
        }
        requireLength(args, 3, "application " + action + " <小镇全名> --reason <原因>");
        TownCommandParser.NamedReason parsed = TownCommandParser.requiredNamedReason(args, 2);
        runtime.read(sender, () -> requireReviewApplication(runtime, parsed.townName()), application -> {
            if (action.equals("approve")) {
                String key = application.status() == cn.tianji.town.core.application.ApplicationStatus.PROVISION_FAILED
                        ? "phase1:retry:" + application.id() + ":" + UUID.randomUUID()
                        : "phase1:approve:" + application.id();
                runtime.provision(sender, application.id(), actorId(sender), sender.getName(),
                        parsed.reason(), key, plugin.townUi()::notifyApplicationDecision);
            } else if (action.equals("reject")) {
                runtime.write(sender, () -> runtime.repository().reject(application.id(), actorId(sender),
                        sender.getName(), parsed.reason()), updated -> {
                    sender.sendMessage("§a申请已拒绝，选址预留已释放。");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            } else {
                runtime.write(sender, () -> runtime.repository().requestChanges(application.id(),
                        actorId(sender), sender.getName(), parsed.reason()), updated -> {
                    sender.sendMessage("§a已要求申请人补充资料。");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            }
        });
        return true;
    }

    private boolean town(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 3, "town <view|delete> <小镇全名>");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("view")) {
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> requireTown(runtime, townName), town -> {
                sender.sendMessage("§6" + town.profile().name() + " [" + town.profile().shortName() + "]");
                sender.sendMessage("§7status=" + town.status() + " mayor=" + town.mayorId()
                        + " version=" + town.version());
                if (town.territory() != null) {
                    sender.sendMessage("§7领地=" + town.territory().center().worldName() + " "
                            + town.territory().center().x() + "," + town.territory().center().z()
                            + " Residence=" + town.residenceName()
                            + " projection=" + town.projectionStatus());
                }
            });
            return true;
        }
        if (action.equals("delete")) {
            TownCommandParser.NamedReason parsed = TownCommandParser.requiredNamedReason(args, 2);
            if (!parsed.confirmed()) {
                throw new IllegalArgumentException(
                        "删除需要 --reason <原因> 和 --confirm；数据库审计记录会保留");
            }
            runtime.write(sender, () -> {
                TownSnapshot town = requireTown(runtime, parsed.townName());
                runtime.repository().deleteTown(town.id(), actorId(sender), sender.getName(),
                        parsed.reason());
                return town;
            }, deleted -> {
                LandProtectionService.Result result = runtime.landProtection().remove(
                        deleted.residenceName(), deleted.territory());
                if (result.success()) {
                    runtime.write(sender, () -> {
                        runtime.repository().completeTownDeletion(deleted.id(), actorId(sender),
                                sender.getName(), parsed.reason());
                        return deleted;
                    }, completed -> sender.sendMessage("§a小镇“" + completed.profile().name()
                            + "”已删除，成员、名称和区块占位已释放；Residence: "
                            + result.message()));
                } else {
                    sender.sendMessage("§c小镇已安全归档，但 Residence 移除失败："
                            + result.message() + "。名称、领地名称和区块仍保持锁定；处理后可重复执行删除命令。");
                    plugin.getLogger().warning("删除小镇后 Residence 移除失败 "
                            + deleted.profile().name() + "/" + deleted.residenceName()
                            + ": " + result.message());
                }
            });
            return true;
        }
        throw new IllegalArgumentException("town 只支持 view 或 delete");
    }

    private boolean member(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 5,
                "member <invite|add|remove> <小镇全名> --player <玩家> --reason <原因>");
        String action = args[1].toLowerCase(Locale.ROOT);
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(args, 2);
        UUID playerId = playerId(parsed.player());
        if (action.equals("invite")) {
            runtime.write(sender, () -> {
                TownSnapshot town = requireTown(runtime, parsed.townName());
                return runtime.repository().adminInvite(town.id(), playerId,
                        actorId(sender), sender.getName(), java.time.Duration.ofDays(7), parsed.reason());
            }, invitation -> {
                sender.sendMessage("§a管理员邀请已创建，到期时间: " + invitation.expiresAt());
                plugin.townUi().notifyInvitation(invitation, playerId);
            });
        } else if (action.equals("add")) {
            runtime.write(sender, () -> {
                TownSnapshot town = requireTown(runtime, parsed.townName());
                runtime.repository().addMember(town.id(), playerId, actorId(sender), sender.getName(),
                        parsed.reason());
                return town;
            }, town -> {
                sender.sendMessage("§a成员已添加，正在同步 Residence 权限。");
                reconcileOne(sender, runtime, town.id(), true);
            });
        } else if (action.equals("remove")) {
            runtime.write(sender, () -> {
                TownSnapshot town = requireTown(runtime, parsed.townName());
                runtime.repository().removeMember(town.id(), playerId, actorId(sender), sender.getName(),
                        parsed.reason());
                return town;
            }, town -> {
                sender.sendMessage("§a成员已移除，正在同步 Residence 权限。");
                reconcileOne(sender, runtime, town.id(), true);
            });
        } else {
            throw new IllegalArgumentException("member 只支持 invite、add 或 remove");
        }
        return true;
    }

    private boolean mayor(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 5,
                "mayor transfer <小镇全名> --player <玩家> --reason <原因>");
        if (!args[1].equalsIgnoreCase("transfer")) {
            throw new IllegalArgumentException(
                    "用法: /townadmin mayor transfer <小镇全名> --player <玩家> --reason <原因>");
        }
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(args, 2);
        UUID newMayor = playerId(parsed.player());
        runtime.write(sender, () -> {
            TownSnapshot town = requireTown(runtime, parsed.townName());
            runtime.repository().transferMayor(town.id(), newMayor, actorId(sender), sender.getName(),
                    parsed.reason());
            return town;
        }, town -> {
            sender.sendMessage("§a镇长已紧急转移。");
            reconcileOne(sender, runtime, town.id(), true);
        });
        return true;
    }

    private boolean land(CommandSender sender, PhaseOneRuntime runtime, String[] args) {
        requireLength(args, 3, "land <preview|reconcile|rebuild> <小镇全名|all>");
        String action = args[1].toLowerCase(Locale.ROOT);
        String townName = TownCommandParser.townName(args, 2);
        if (action.equals("preview")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c领地粒子预览只能由游戏内玩家执行。");
                return true;
            }
            runtime.read(sender, () -> requireTown(runtime, townName),
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
        if (townName.equalsIgnoreCase("all")) {
            runtime.read(sender, () -> {
                List<TownMembers> states = new ArrayList<>();
                for (TownSnapshot town : runtime.repository().listTowns(false)) {
                    states.add(new TownMembers(town, runtime.repository().listMemberIds(town.id())));
                }
                return states;
            }, states -> states.forEach(state -> {
                if (action.equals("rebuild")) {
                    LandProtectionService.Result removal = runtime.landProtection()
                            .remove(state.town().residenceName(), state.town().territory());
                    sender.sendMessage((removal.success() ? "§a" : "§c") + removal.message());
                    if (!removal.success()) {
                        return;
                    }
                }
                runtime.reconcile(sender, state.town(), state.members(), repair);
            }));
        } else {
            if (action.equals("rebuild")) {
                runtime.read(sender, () -> {
                    TownSnapshot town = requireTown(runtime, townName);
                    return new TownMembers(town, runtime.repository().listMemberIds(town.id()));
                }, state -> {
                    LandProtectionService.Result removal = runtime.landProtection()
                            .remove(state.town().residenceName(), state.town().territory());
                    sender.sendMessage((removal.success() ? "§a" : "§c") + removal.message());
                    if (removal.success()) {
                        runtime.reconcile(sender, state.town(), state.members(), true);
                    }
                });
            } else {
                runtime.read(sender, () -> requireTown(runtime, townName),
                        town -> reconcileOne(sender, runtime, town.id(), repair));
            }
        }
        return true;
    }

    private void reconcileOne(CommandSender sender, PhaseOneRuntime runtime, UUID townId,
                              boolean repair) {
        runtime.read(sender, () -> new TownMembers(requireTown(runtime, townId),
                runtime.repository().listMemberIds(townId)),
                state -> runtime.reconcile(sender, state.town(), state.members(), repair));
    }

    private static ApplicationSnapshot requireReviewApplication(PhaseOneRuntime runtime,
                                                                 String townName) {
        return runtime.repository().findReviewApplicationByName(townName)
                .orElseThrow(() -> new IllegalArgumentException("找不到待处理申请 “" + townName + "”"));
    }

    private static TownSnapshot requireTown(PhaseOneRuntime runtime, String townName) {
        return runtime.repository().findTownByName(townName)
                .orElseThrow(() -> new IllegalArgumentException("找不到小镇 “" + townName + "”"));
    }

    private static TownSnapshot requireTown(PhaseOneRuntime runtime, UUID townId) {
        return runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("找不到小镇记录"));
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

    private static boolean contains(String[] args, String value) {
        return java.util.Arrays.stream(args).anyMatch(value::equalsIgnoreCase);
    }

    private boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("phase1.maintenance-mode", false);
    }

    private static void applicationHelp(CommandSender sender) {
        sender.sendMessage("§e/townadmin application list");
        sender.sendMessage("§e/townadmin application approve|reject|change <小镇全名> --reason <原因>");
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("§6TianjiTown 1.0.0 管理命令");
        sender.sendMessage("§e/townadmin status | reload | maintenance <on|off|status> | audit [limit]");
        sender.sendMessage("§e/townadmin phase0 status | residence-smoke ...");
        sender.sendMessage("§e/townadmin station create|remove|info|list | handbook [player]");
        sender.sendMessage("§e/townadmin application list|approve|reject|change ...");
        sender.sendMessage("§e/townadmin town view <小镇全名>");
        sender.sendMessage("§e/townadmin town delete <小镇全名> --reason <原因> --confirm");
        sender.sendMessage("§e/townadmin member invite|add|remove <小镇全名> --player <玩家> --reason <原因>");
        sender.sendMessage("§e/townadmin mayor transfer <小镇全名> --player <玩家> --reason <原因>");
        sender.sendMessage("§e/townadmin land preview|reconcile|rebuild <小镇全名|all> [--repair|--confirm]");
    }

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }
}

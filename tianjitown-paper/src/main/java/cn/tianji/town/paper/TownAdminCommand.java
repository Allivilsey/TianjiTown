package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.economy.MoneyAmount;
import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.land.ExpansionPricing;
import cn.tianji.town.core.land.TerritoryRules;
import cn.tianji.town.core.governance.VoteType;
import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.storage.town.ApplicationSnapshot;
import cn.tianji.town.storage.town.AuditSnapshot;
import cn.tianji.town.storage.town.TownSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class TownAdminCommand implements CommandExecutor {
    private static final UUID CONSOLE_ID = new UUID(0, 0);
    private final TianjiTownPlugin plugin;
    private final CommandConfirmationManager confirmations = new CommandConfirmationManager();

    TownAdminCommand(TianjiTownPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!TownAdminPermissions.hasAny(sender::hasPermission)) {
                sender.sendMessage("§c没有权限。");
                return true;
            }
            help(sender, null);
            return true;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (!TownAdminPermissions.canUseRoot(sender::hasPermission, root)) {
            sender.sendMessage("§c没有权限。");
            return true;
        }
        if (root.equals("help")) {
            help(sender, args.length >= 2 ? args[1] : null);
            return true;
        }
        try {
            if (root.equals("confirm")) {
                return confirm(sender, args);
            }
            if (root.equals("cancel")) {
                return cancel(sender, args);
            }
            if (root.equals("status")) {
                status(sender);
                return true;
            }
            if (root.equals("reload")) {
                plugin.reloadConfig();
                sender.sendMessage("§a配置已重新读取；税收/消费、Buff 商店和领地加成开关立即生效。"
                        + "SQLite、清算账户、金额精度和商品定义需重启后生效。");
                return true;
            }
            if (root.equals("maintenance")) {
                return maintenance(sender, args);
            }
            TownRuntime runtime = requireRuntime(sender);
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
                case "vote" -> vote(sender, runtime, args);
                case "land" -> land(sender, runtime, args);
                case "money" -> money(sender, runtime, args);
                case "tax" -> tax(sender, runtime, args);
                case "ledger" -> ledger(sender, runtime, args);
                case "expand" -> expand(sender, runtime, args);
                case "buff" -> buff(sender, runtime, args);
                case "diagnose" -> diagnose(sender, runtime, args);
                case "backup" -> backup(sender, runtime, args);
                default -> {
                    sender.sendMessage("§c未知子命令：" + args[0]
                            + "。使用 /townadmin help 查看帮助。");
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
        sender.sendMessage("§6TianjiTown " + plugin.getPluginMeta().getVersion()
                + ": §f" + status.state());
        status.details().forEach(detail -> sender.sendMessage("§7- " + detail));
        TownRuntime runtime = plugin.townRuntime();
        if (runtime != null) {
            sender.sendMessage("§7- SQLite 运行状态: "
                    + (runtime.databaseAvailable() ? "READY" : "WRITE_LOCKED"));
            sender.sendMessage("§7- Buff 商店: "
                    + (runtime.buffs().buffShopEnabled() ? "OPEN" : "PAUSED")
                    + "，配置商品=" + runtime.buffs().settings().buffs().size());
            sender.sendMessage("§7- 建筑返还: "
                    + (runtime.bonuses().buildingRefundEnabled() ? "ENABLED" : "PAUSED")
                    + "，黑名单=" + runtime.bonuses().settings().buildingRefund()
                    .blacklist().size() + "，周上限=" + runtime.bonuses().settings()
                    .buildingRefund().weeklyLimit());
            sender.sendMessage("§7- 信标增强: "
                    + (runtime.bonuses().beaconEnabled() ? "ENABLED" : "PAUSED"));
            TownBonusRuntime.DiagnosticResult diagnostic = runtime.bonuses().lastDiagnostic();
            sender.sendMessage("§7- 最近统一诊断: " + diagnostic.detail()
                    + (diagnostic.report() == null ? "" : "，报告=" + diagnostic.report()));
            OnlineBackupService.Result backup = runtime.bonuses().lastBackup();
            sender.sendMessage("§7- 最近在线备份: " + backup.detail()
                    + (backup.databaseFile() == null ? "" : "，文件=" + backup.databaseFile()));
        }
        sender.sendMessage("§7- 玩家入口: " + (maintenanceMode() ? "MAINTENANCE" : "OPEN"));
    }

    private boolean diagnose(CommandSender sender, TownRuntime runtime, String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("用法: /townadmin diagnose [1~180天]");
        }
        int days = args.length == 2 ? Integer.parseInt(args[1])
                : runtime.bonuses().settings().operations().quickShopDiagnosticDays();
        runtime.bonuses().diagnose(sender, days);
        return true;
    }

    private boolean backup(CommandSender sender, TownRuntime runtime, String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("用法: /townadmin backup");
        }
        runtime.bonuses().createBackup(sender);
        return true;
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

    private boolean confirm(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("§c确认链接无效，请重新发出危险操作命令。");
            return true;
        }
        CommandConfirmationManager.Result result = confirmations.consume(ownerKey(sender), args[1]);
        switch (result.status()) {
            case CONFIRMED -> {
                sender.sendMessage("§e已确认：" + result.description());
                try {
                    result.action().run();
                } catch (RuntimeException exception) {
                    sender.sendMessage("§c确认后的操作启动失败: " + safeMessage(exception));
                    plugin.getLogger().warning("危险操作启动失败: " + safeMessage(exception));
                }
            }
            case EXPIRED -> sender.sendMessage("§c确认已过期，请重新发出危险操作命令。");
            case NOT_OWNER -> sender.sendMessage("§c该确认不属于你，未执行任何操作。");
            case NOT_FOUND, CANCELLED -> sender.sendMessage("§c确认不存在或已经使用。");
        }
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("§c取消链接无效。");
            return true;
        }
        CommandConfirmationManager.Result result = confirmations.cancel(ownerKey(sender), args[1]);
        switch (result.status()) {
            case CANCELLED -> sender.sendMessage("§a已取消：" + result.description());
            case EXPIRED -> sender.sendMessage("§c确认已过期，无需取消。");
            case NOT_OWNER -> sender.sendMessage("§c该确认不属于你。");
            case NOT_FOUND, CONFIRMED -> sender.sendMessage("§c确认不存在或已经使用。");
        }
        return true;
    }

    private void requestConfirmation(CommandSender sender, String description, Runnable action) {
        CommandConfirmationManager.Confirmation confirmation = confirmations.request(
                ownerKey(sender), description, action);
        String confirmCommand = "/townadmin confirm " + confirmation.token();
        String cancelCommand = "/townadmin cancel " + confirmation.token();
        Component message = Component.text("危险操作：" + description + " ", NamedTextColor.YELLOW)
                .append(Component.text("[确认执行]", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand(confirmCommand))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "60 秒内点击确认", NamedTextColor.RED))))
                .append(Component.space())
                .append(Component.text("[取消]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand(cancelCommand))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "取消本次操作", NamedTextColor.GREEN))));
        sender.sendMessage(message);
    }

    private boolean audit(CommandSender sender, TownRuntime runtime, String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("用法: /townadmin audit [1~200]");
        }
        int limit;
        try {
            limit = args.length == 2 ? Integer.parseInt(args[1]) : 20;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("audit 数量必须是 1~200 的整数");
        }
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("audit 数量必须在 1~200");
        }
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

    private boolean application(CommandSender sender, TownRuntime runtime, String[] args) {
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
        requireLength(args, 4, "application " + action + " <小镇全名> <原因>");
        runtime.read(sender, () -> {
            List<ApplicationSnapshot> candidates = runtime.repository()
                    .listApplicationsForCompletion(500);
            TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(args, 2,
                    candidates.stream().map(candidate -> candidate.text().name()).toList());
            ApplicationSnapshot application = candidates.stream()
                    .filter(candidate -> sameName(candidate.text().name(), parsed.townName()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "找不到待处理申请 “" + parsed.townName() + "”"));
            return new ApplicationRequest(application, parsed.reason());
        }, request -> {
            ApplicationSnapshot application = request.application();
            if (action.equals("approve")) {
                String key = application.status() == cn.tianji.town.core.application.ApplicationStatus.PROVISION_FAILED
                        ? "phase1:retry:" + application.id() + ":" + UUID.randomUUID()
                        : "phase1:approve:" + application.id();
                runtime.provision(sender, application.id(), actorId(sender), sender.getName(),
                        request.reason(), key, plugin.townUi()::notifyApplicationDecision);
            } else if (action.equals("reject")) {
                runtime.write(sender, () -> runtime.repository().reject(application.id(), actorId(sender),
                        sender.getName(), request.reason()), updated -> {
                    sender.sendMessage("§a申请已拒绝，选址预留已释放。");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            } else {
                runtime.write(sender, () -> runtime.repository().requestChanges(application.id(),
                        actorId(sender), sender.getName(), request.reason()), updated -> {
                    sender.sendMessage("§a已要求申请人补充资料。");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            }
        });
        return true;
    }

    private boolean town(CommandSender sender, TownRuntime runtime, String[] args) {
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
            requireLength(args, 4, "town delete <小镇全名> <原因>");
            runtime.read(sender, () -> {
                List<TownSnapshot> candidates = runtime.repository().listTowns(true);
                TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(args, 2,
                        townNames(candidates));
                TownSnapshot target = candidates.stream()
                        .filter(candidate -> sameName(candidate.profile().name(), parsed.townName()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "找不到小镇 “" + parsed.townName() + "”"));
                return new TownDeleteRequest(target.id(), target.profile().name(), target.version(),
                        parsed.reason());
            }, request -> requestConfirmation(sender,
                    "删除小镇“" + request.townName() + "”（审计记录会保留）",
                    () -> deleteTown(sender, runtime, request)));
            return true;
        }
        throw new IllegalArgumentException("town 只支持 view 或 delete");
    }

    private void deleteTown(CommandSender sender, TownRuntime runtime,
                            TownDeleteRequest request) {
        runtime.write(sender, () -> {
            TownSnapshot current = requireTown(runtime, request.townId());
            requireVersion(current, request.version());
            runtime.repository().deleteTown(current.id(), actorId(sender), sender.getName(),
                    request.reason());
            return current;
        }, deleted -> {
            LandProtectionService.Result result = runtime.landProtection().remove(
                    deleted.residenceName(), deleted.territory());
            if (result.success()) {
                runtime.write(sender, () -> {
                    runtime.repository().completeTownDeletion(deleted.id(), actorId(sender),
                            sender.getName(), request.reason());
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
    }

    private boolean member(CommandSender sender, TownRuntime runtime, String[] args) {
        requireLength(args, 5,
                "member <add|remove|role> <小镇全名> <玩家> <原因|角色>");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("add") && !action.equals("remove") && !action.equals("role")) {
            throw new IllegalArgumentException("member 只支持 add、remove 或 role");
        }
        runtime.read(sender, () -> memberRequest(runtime, args), request -> {
            UUID playerId = playerId(request.player());
            if (action.equals("role")) {
                MemberRole role;
                try {
                    role = MemberRole.valueOf(request.reason().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    sender.sendMessage("§c角色只支持 DEPUTY_MAYOR 或 MEMBER；MAYOR 请使用镇长转移流程。");
                    return;
                }
                runtime.write(sender, () -> {
                    TownSnapshot town = requireTown(runtime, request.townId());
                    runtime.governance().changeRoleByAdmin(town.id(), playerId, role,
                            actorId(sender), sender.getName(), "管理员调整成员角色");
                    return town;
                }, town -> {
                    sender.sendMessage("§a成员角色已调整为 " + role + "，正在复核 Residence 权限。");
                    reconcileOne(sender, runtime, town.id(), true);
                });
            } else if (action.equals("add")) {
                runtime.write(sender, () -> {
                    TownSnapshot town = requireTown(runtime, request.townId());
                    runtime.repository().addMember(town.id(), playerId, actorId(sender),
                            sender.getName(), request.reason());
                    return town;
                }, town -> {
                    sender.sendMessage("§a成员已添加，正在同步 Residence 权限。");
                    Player added = Bukkit.getPlayer(playerId);
                    if (added != null) {
                        runtime.buffs().refreshPlayer(added);
                    }
                    reconcileOne(sender, runtime, town.id(), true);
                });
            } else {
                runtime.write(sender, () -> {
                    TownSnapshot town = requireTown(runtime, request.townId());
                    runtime.repository().removeMember(town.id(), playerId, actorId(sender),
                            sender.getName(), request.reason());
                    return town;
                }, town -> {
                    sender.sendMessage("§a成员已移除，正在同步 Residence 权限。");
                    Player removed = Bukkit.getPlayer(playerId);
                    if (removed != null) {
                        runtime.buffs().refreshPlayer(removed);
                    }
                    reconcileOne(sender, runtime, town.id(), true);
                });
            }
        });
        return true;
    }

    private boolean vote(CommandSender sender, TownRuntime runtime, String[] args) {
        requireLength(args, 3,
                "vote <create-kick|create-mayor|settle|cancel> <小镇全名|voteId> ...");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("settle")) {
            UUID voteId = UUID.fromString(args[2]);
            runtime.write(sender, () -> runtime.governance().settleVote(voteId,
                    actorId(sender), sender.getName(), false), vote -> {
                sender.sendMessage("§a投票状态: " + vote.status() + "，赞成/门槛: "
                        + vote.yesVotes() + "/" + vote.requiredYes());
                if (vote.passed() && vote.type() == VoteType.KICK_MEMBER) {
                    reconcileOne(sender, runtime, vote.townId(), true);
                }
            });
            return true;
        }
        if (action.equals("cancel")) {
            requireLength(args, 4, "vote cancel <voteId> <原因>");
            UUID voteId = UUID.fromString(args[2]);
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            runtime.write(sender, () -> runtime.governance().cancelVote(voteId,
                    actorId(sender), sender.getName(), reason), vote ->
                    sender.sendMessage("§a投票已取消: " + vote.id()));
            return true;
        }
        if (!action.equals("create-kick") && !action.equals("create-mayor")) {
            throw new IllegalArgumentException(
                    "vote 只支持 create-kick、create-mayor、settle 或 cancel");
        }
        requireLength(args, 4, "vote " + action + " <小镇全名> <玩家>");
        GovernanceSettings settings;
        try {
            settings = GovernanceSettings.load(plugin.getConfig());
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("§c治理配置无效: " + exception.getMessage());
            plugin.getLogger().warning("拒绝创建治理投票: " + exception.getMessage());
            return true;
        }
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(false).stream()
                    .filter(town -> town.status() == TownStatus.ACTIVE).toList();
            TownCommandParser.NamedPlayer parsed = TownCommandParser.namedPlayer(args, 2,
                    townNames(towns));
            TownSnapshot town = towns.stream()
                    .filter(candidate -> sameName(candidate.profile().name(), parsed.townName()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到可操作的小镇"));
            return new VoteCreateRequest(town.id(), playerId(parsed.player()),
                    action.equals("create-kick") ? VoteType.KICK_MEMBER : VoteType.REPLACE_MAYOR);
        }, request -> {
            runtime.write(sender, () -> runtime.governance().createVote(request.townId(),
                    request.type(), request.targetId(), actorId(sender),
                    settings.activeMemberWindow(), settings.minimumMembership(),
                    settings.voteDuration(), true), vote -> sender.sendMessage(
                    "§a投票已创建: " + vote.id() + "，有效选民=" + vote.eligibleVoters()
                            + "，通过门槛=" + vote.requiredYes()));
        });
        return true;
    }

    private MemberRequest memberRequest(TownRuntime runtime, String[] args) {
        List<TownSnapshot> candidates = runtime.repository().listTowns(false).stream()
                .filter(town -> town.status() == TownStatus.ACTIVE).toList();
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(args, 2,
                townNames(candidates));
        TownSnapshot town = candidates.stream()
                .filter(candidate -> sameName(candidate.profile().name(), parsed.townName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "找不到可操作的小镇 “" + parsed.townName() + "”"));
        return new MemberRequest(town.id(), parsed.player(), parsed.reason());
    }

    private boolean mayor(CommandSender sender, TownRuntime runtime, String[] args) {
        requireLength(args, 5,
                "mayor transfer <小镇全名> <玩家> <原因>");
        if (!args[1].equalsIgnoreCase("transfer")) {
            throw new IllegalArgumentException(
                    "用法: /townadmin mayor transfer <小镇全名> <玩家> <原因>");
        }
        runtime.read(sender, () -> memberRequest(runtime, args), request -> {
            UUID newMayor = playerId(request.player());
            runtime.write(sender, () -> {
                TownSnapshot town = requireTown(runtime, request.townId());
                runtime.repository().transferMayor(town.id(), newMayor, actorId(sender),
                        sender.getName(), request.reason());
                return town;
            }, town -> {
                sender.sendMessage("§a镇长已紧急转移。");
                reconcileOne(sender, runtime, town.id(), true);
            });
        });
        return true;
    }

    private boolean land(CommandSender sender, TownRuntime runtime, String[] args) {
        requireLength(args, 3, "land <preview|reconcile|rebuild> <小镇全名|all>");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("preview")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§c领地粒子预览只能由游戏内玩家执行。");
                return true;
            }
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> requireTown(runtime, townName),
                    town -> plugin.townUi().previewTownForAdmin(player, town));
            return true;
        }
        if (!action.equals("reconcile") && !action.equals("rebuild")) {
            throw new IllegalArgumentException("land 只支持 preview/reconcile/rebuild");
        }
        if (action.equals("reconcile")) {
            runtime.read(sender, () -> {
                List<TownSnapshot> candidates = runtime.repository().listTowns(false);
                List<String> names = new ArrayList<>(townNames(candidates));
                names.add("all");
                TownCommandParser.NamedAction parsed = TownCommandParser.namedAction(args, 2,
                        names, List.of("repair"));
                List<TownSnapshot> targets = selectLandTargets(candidates, parsed.townName());
                return new LandReconcileRequest(loadTownMembers(runtime, targets),
                        parsed.action() != null);
            }, request -> request.states().forEach(state -> runtime.reconcile(sender,
                    state.town(), state.members(), request.repair())));
        } else {
            runtime.read(sender, () -> {
                List<TownSnapshot> candidates = runtime.repository().listTowns(false);
                List<String> names = new ArrayList<>(townNames(candidates));
                names.add("all");
                String targetName = TownCommandParser.exactName(args, 2, names);
                List<TownSnapshot> targets = selectLandTargets(candidates, targetName);
                return new LandRebuildRequest(targetName.equalsIgnoreCase("all"),
                        targets.stream().map(town -> new TownReference(town.id(),
                                town.profile().name(), town.version())).toList());
            }, request -> {
                String target = request.all() ? "全部 " + request.towns().size() + " 个小镇"
                        : "小镇“" + request.towns().getFirst().townName() + "”";
                requestConfirmation(sender, "移除并重建" + target + "的 Residence 投影",
                        () -> rebuildLand(sender, runtime, request));
            });
        }
        return true;
    }

    private boolean money(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.MONEY);
        requireLength(args, 2, "money <view|adjust|reconcile> ...");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("reconcile")) {
            runtime.reconcileSettlement();
            sender.sendMessage("§a已提交清算账户对账；差额不足会立即锁定全部小镇消费。");
            return true;
        }
        requireLength(args, 3, "money <view|adjust> <小镇全名> [金额 原因]");
        if (action.equals("view")) {
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> {
                TownSnapshot town = requireTown(runtime, townName);
                return runtime.finance().findFinanceByTown(town.id()).orElseThrow();
            }, account -> sender.sendMessage("§6" + account.townName() + " 公共余额: §f"
                    + runtime.money(account.balanceMinor()) + (account.locked()
                    ? " §c[LOCKED] " + account.lockReason() : " §a[READY]")));
            return true;
        }
        if (action.equals("adjust")) {
            requireLength(args, 5, "money adjust <小镇全名> <带符号金额> <原因>");
            runtime.read(sender, () -> {
                List<TownSnapshot> towns = runtime.repository().listTowns(true);
                TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                        args, 2, townNames(towns));
                TownSnapshot town = towns.stream().filter(candidate -> sameName(
                                candidate.profile().name(), parsed.townName())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在"));
                long amount = MoneyAmount.from(new BigDecimal(parsed.amount()),
                        runtime.settlement().scale()).minorUnits();
                if (amount == 0) {
                    throw new IllegalArgumentException("调整金额不能为 0");
                }
                return new MoneyAdjustment(town.id(), amount, parsed.reason());
            }, request -> runtime.adjustFunds(sender, request.townId(), request.amountMinor(),
                    request.reason()));
            return true;
        }
        throw new IllegalArgumentException("money 只支持 view、adjust 或 reconcile");
    }

    private boolean tax(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.TAX);
        requireLength(args, 5, "tax set <小镇全名> <百分比> <原因>");
        if (!args[1].equalsIgnoreCase("set")) {
            throw new IllegalArgumentException("tax 只支持 set");
        }
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                    args, 2, townNames(towns));
            TownSnapshot town = towns.stream().filter(candidate -> sameName(
                            candidate.profile().name(), parsed.townName())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("小镇不存在"));
            BigDecimal percent = new BigDecimal(parsed.amount().replace("%", ""));
            int bps = percent.movePointRight(2).intValueExact();
            return new TaxAdjustment(town.id(), bps, parsed.reason());
        }, request -> runtime.forceTaxRate(sender, request.townId(), request.basisPoints(),
                request.reason()));
        return true;
    }

    private boolean ledger(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.LEDGER);
        requireLength(args, 3, "ledger view <小镇全名>");
        if (!args[1].equalsIgnoreCase("view")) {
            throw new IllegalArgumentException("ledger 只支持 view");
        }
        String townName = TownCommandParser.townName(args, 2);
        runtime.read(sender, () -> {
            TownSnapshot town = requireTown(runtime, townName);
            return runtime.finance().ledger(town.id(), 0, 45);
        }, entries -> {
            sender.sendMessage("§6完整公共账本（最新 " + entries.size() + " 条）");
            for (cn.tianji.town.storage.economy.EconomyRepository.LedgerEntry entry : entries) {
                sender.sendMessage("§7" + entry.createdAt() + " §f" + entry.entryType()
                        + " §e" + runtime.money(entry.amountMinor()) + " §8余额="
                        + runtime.money(entry.balanceAfterMinor()) + " " + entry.note());
            }
        });
        return true;
    }

    private boolean expand(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.EXPAND);
        requireLength(args, 3, "expand <view|preview> <小镇全名> [方向]");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("preview") && !(sender instanceof Player)) {
            sender.sendMessage("§c扩张预览需要玩家当前位置，只能由游戏内玩家执行。");
            return true;
        }
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            if (action.equals("view")) {
                String name = TownCommandParser.exactName(args, 2, townNames(towns));
                TownSnapshot town = towns.stream().filter(candidate -> sameName(
                                candidate.profile().name(), name)).findFirst().orElseThrow();
                return new AdminExpansion(town, runtime.finance().territoryUnits(town.id()), null);
            }
            if (action.equals("preview")) {
                TownCommandParser.NamedAction parsed = TownCommandParser.namedAction(args, 2,
                        townNames(towns), List.of("north", "east", "south", "west"));
                if (parsed.action() == null) {
                    throw new IllegalArgumentException("必须指定 north/east/south/west");
                }
                TownSnapshot town = towns.stream().filter(candidate -> sameName(
                                candidate.profile().name(), parsed.townName())).findFirst().orElseThrow();
                var units = runtime.finance().territoryUnits(town.id());
                var candidate = TerritoryRules.next(units.stream().map(
                                cn.tianji.town.storage.economy.EconomyRepository
                                        .TerritoryUnitSnapshot::unit).toList(),
                        ExpansionDirection.parse(parsed.action()));
                return new AdminExpansion(town, units, candidate);
            }
            throw new IllegalArgumentException("expand 只支持 view 或 preview");
        }, view -> {
            sender.sendMessage("§6" + view.town().profile().name() + " 领地单元: "
                    + view.units().size() + "/" + runtime.economySettings().maximumUnits());
            view.units().forEach(unit -> sender.sendMessage("§7- grid=" + unit.unit().gridX()
                    + "," + unit.unit().gridZ() + " area=" + unit.residenceAreaName()
                    + " projection=" + unit.projectionStatus()));
            if (view.preview() != null) {
                Player player = (Player) sender;
                long price = ExpansionPricing.price(runtime.economySettings().expansionBaseCost(),
                        runtime.economySettings().expansionPerUnitIncrease(), view.units().size(),
                        runtime.settlement().scale()).minorUnits();
                runtime.sitePolicy().teleportAndPreview(player, view.preview().territory());
                player.sendMessage("§e预估价格: " + runtime.money(price));
            }
        });
        return true;
    }

    private boolean buff(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.BUFF);
        requireLength(args, 2, "buff <list|grant> ...");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            requireLength(args, 3, "buff list <小镇全名>");
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> {
                TownSnapshot town = requireTown(runtime, townName);
                return runtime.buffs().repository().activeBuffsForTown(town.id(),
                        java.time.Instant.now());
            }, buffs -> {
                sender.sendMessage("§6生效中的公共 Buff: " + buffs.size());
                buffs.forEach(value -> sender.sendMessage("§7" + value.buffId() + " §d"
                        + value.buffKey() + " §f等级=" + value.level() + " 层数="
                        + value.stackCount() + " 到期=" + value.expiresAt()));
            });
            return true;
        }
        if (action.equals("grant")) {
            if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
                throw new IllegalArgumentException("公共 Buff 新购买已由功能开关暂停");
            }
            requireLength(args, 5, "buff grant <小镇全名> <buffKey> <原因>");
            runtime.read(sender, () -> {
                List<TownSnapshot> towns = runtime.repository().listTowns(true);
                TownCommandParser.NamedActionReason parsed = TownCommandParser.namedActionReason(
                        args, 2, townNames(towns),
                        runtime.buffs().settings().buffs().keySet());
                TownSnapshot town = towns.stream().filter(candidate -> sameName(
                                candidate.profile().name(), parsed.townName())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在"));
                BuffDefinition definition = runtime.buffs().settings().requireBuff(
                        parsed.action().toLowerCase(Locale.ROOT));
                return new BuffGrantRequest(town.id(), town.profile().name(), definition,
                        parsed.reason());
            }, request -> requestConfirmation(sender, "为小镇“" + request.townName()
                    + "”代购 Buff “" + request.definition().displayName() + "”并扣除公共资金",
                    () -> runtime.write(sender, () -> runtime.buffs().repository()
                                    .purchaseBuffForTown(request.townId(), actorId(sender),
                                            sender.getName(), request.definition(),
                                            runtime.settlement().scale(),
                                            BuffDurationOption.ONE_HOUR,
                                            "admin-buff-purchase:" + UUID.randomUUID(),
                                            java.time.Instant.now(), request.reason()),
                            purchase -> {
                                sender.sendMessage("§aBuff 代购完成，公共余额: "
                                        + runtime.money(purchase.balanceAfterMinor()));
                                runtime.buffs().refreshAllPlayers();
                            })));
            return true;
        }
        throw new IllegalArgumentException("buff 只支持 list 或 grant；公共 Buff 不接受退款");
    }

    private void rebuildLand(CommandSender sender, TownRuntime runtime,
                             LandRebuildRequest request) {
        runtime.read(sender, () -> {
            List<TownSnapshot> targets = request.towns().stream().map(reference -> {
                TownSnapshot current = requireTown(runtime, reference.townId());
                requireVersion(current, reference.version());
                if (current.status() == TownStatus.ARCHIVED) {
                    throw new IllegalArgumentException("小镇“" + current.profile().name()
                            + "”已归档，请重新发起操作");
                }
                return current;
            }).toList();
            return loadTownMembers(runtime, targets);
        }, states -> states.forEach(state -> {
            LandProtectionService.Result removal = runtime.landProtection()
                    .remove(state.town().residenceName(), state.town().territory());
            sender.sendMessage((removal.success() ? "§a" : "§c")
                    + state.town().profile().name() + ": " + removal.message());
            if (removal.success()) {
                runtime.reconcile(sender, state.town(), state.members(), true);
            }
        }));
    }

    private static List<TownSnapshot> selectLandTargets(List<TownSnapshot> candidates,
                                                         String targetName) {
        List<TownSnapshot> targets = targetName.equalsIgnoreCase("all")
                ? candidates
                : candidates.stream().filter(town -> sameName(town.profile().name(), targetName))
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("没有可操作的小镇");
        }
        return targets;
    }

    private static List<TownMembers> loadTownMembers(TownRuntime runtime,
                                                      List<TownSnapshot> towns) {
        return towns.stream().map(town -> new TownMembers(town,
                runtime.repository().listMemberIds(town.id()))).toList();
    }

    private void reconcileOne(CommandSender sender, TownRuntime runtime, UUID townId,
                              boolean repair) {
        runtime.read(sender, () -> new TownMembers(requireTown(runtime, townId),
                runtime.repository().listMemberIds(townId)),
                state -> runtime.reconcile(sender, state.town(), state.members(), repair));
    }

    private static TownSnapshot requireTown(TownRuntime runtime, String townName) {
        return runtime.repository().findTownByName(townName)
                .orElseThrow(() -> new IllegalArgumentException("找不到小镇 “" + townName + "”"));
    }

    private static TownSnapshot requireTown(TownRuntime runtime, UUID townId) {
        return runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("找不到小镇记录"));
    }

    private TownRuntime requireRuntime(CommandSender sender) {
        TownRuntime runtime = plugin.townRuntime();
        if (runtime == null) {
            sender.sendMessage("§c小镇系统尚未就绪。请先使用 /townadmin status 查看启动门禁。");
        }
        return runtime;
    }

    private static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_ID;
    }

    private static String ownerKey(CommandSender sender) {
        return sender instanceof Player player ? "player:" + player.getUniqueId()
                : "sender:" + sender.getName().toLowerCase(Locale.ROOT);
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

    private static String reasonTail(String[] args, int start) {
        if (start >= args.length) {
            throw new IllegalArgumentException("必须填写原因");
        }
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length))
                .strip();
        if (reason.isBlank() || reason.equalsIgnoreCase("<原因>")) {
            throw new IllegalArgumentException("必须填写实际原因");
        }
        return reason;
    }

    private static void requirePermission(CommandSender sender, String permission) {
        if (!TownAdminPermissions.has(sender::hasPermission, permission)) {
            throw new IllegalArgumentException("缺少权限 " + permission);
        }
    }

    private static boolean sameName(String first, String second) {
        return ApplicationText.normalizeNameKey(first)
                .equals(ApplicationText.normalizeNameKey(second));
    }

    private static List<String> townNames(List<TownSnapshot> towns) {
        return towns.stream().map(town -> town.profile().name()).toList();
    }

    private static void requireVersion(TownSnapshot town, long expectedVersion) {
        if (town.version() != expectedVersion) {
            throw new IllegalArgumentException("小镇“" + town.profile().name()
                    + "”在确认期间发生变化，请重新发起操作");
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("phase1.maintenance-mode", false);
    }

    private static void applicationHelp(CommandSender sender) {
        sender.sendMessage("§e/townadmin application list");
        sender.sendMessage("§e/townadmin application approve|reject|change <小镇全名> <原因>");
    }

    private void help(CommandSender sender, String topic) {
        if (topic == null || topic.isBlank()) {
            sender.sendMessage("§6TianjiTown " + plugin.getPluginMeta().getVersion() + " 管理帮助");
            sender.sendMessage("§7用法: §f/townadmin help <分类>");
            if (sender.hasPermission(TownAdminPermissions.ROOT)) {
                sender.sendMessage("§esystem §7状态、重载、维护与审计");
                sender.sendMessage("§estation §7服务台与小镇手册");
                sender.sendMessage("§eapplication §7申请审批");
                sender.sendMessage("§etown §7小镇查看与删除");
                sender.sendMessage("§emember §7成员与镇长管理");
                sender.sendMessage("§evote §7治理投票代办与结算");
                sender.sendMessage("§eland §7领地预览与对账");
            }
            if (TownAdminPermissions.has(sender::hasPermission, TownAdminPermissions.MONEY)) {
                sender.sendMessage("§emoney §7公共资金查询、调整与对账");
            }
            if (TownAdminPermissions.has(sender::hasPermission, TownAdminPermissions.TAX)) {
                sender.sendMessage("§etax §7QuickShop 税率调整");
            }
            if (TownAdminPermissions.has(sender::hasPermission, TownAdminPermissions.LEDGER)) {
                sender.sendMessage("§eledger §7公共资金账本查询");
            }
            if (TownAdminPermissions.has(sender::hasPermission, TownAdminPermissions.EXPAND)) {
                sender.sendMessage("§eexpand §7领地扩张查询与预览");
            }
            if (TownAdminPermissions.has(sender::hasPermission, TownAdminPermissions.BUFF)) {
                sender.sendMessage("§ebuff §7公共 Buff 查询、代购与退款取消");
            }
            if (TownAdminPermissions.has(sender::hasPermission,
                    TownAdminPermissions.OPERATIONS)) {
                sender.sendMessage("§esystem §7统一诊断、在线备份、状态与维护");
            }
            sender.sendMessage("§8Tab 补全中的 <原因> 是位置提示，请替换为实际内容。");
            return;
        }
        if (!TownAdminPermissions.canViewHelpTopic(sender::hasPermission, topic)) {
            sender.sendMessage("§c没有该帮助分类的权限。");
            return;
        }
        switch (topic.toLowerCase(Locale.ROOT)) {
            case "system" -> systemHelp(sender);
            case "station" -> stationHelp(sender);
            case "application" -> applicationHelp(sender);
            case "town" -> townHelp(sender);
            case "member" -> memberHelp(sender);
            case "vote" -> voteHelp(sender);
            case "land" -> landHelp(sender);
            case "money", "tax", "ledger", "expand" -> economyHelp(sender, topic);
            case "buff" -> buffsHelp(sender);
            default -> {
                sender.sendMessage("§c未知帮助分类：" + topic);
                help(sender, null);
            }
        }
    }

    private static void systemHelp(CommandSender sender) {
        sender.sendMessage("§6系统与运维");
        sender.sendMessage("§e/townadmin status §7查看依赖、SQLite 和玩家入口状态");
        sender.sendMessage("§e/townadmin reload §7重载可热更新的配置");
        sender.sendMessage("§e/townadmin maintenance <on|off|status> §7管理维护模式");
        sender.sendMessage("§e/townadmin audit [1~200] §7查看最近审计记录");
        sender.sendMessage("§e/townadmin diagnose [1~180天] §7生成统一对账诊断报告");
        sender.sendMessage("§e/townadmin backup §7立即创建 SQLite 在线备份与配置快照");
    }

    private static void economyHelp(CommandSender sender, String topic) {
        sender.sendMessage("§6公共经济与领地扩张");
        switch (topic.toLowerCase(Locale.ROOT)) {
            case "money" -> {
                sender.sendMessage("§e/townadmin money view <小镇全名>");
                sender.sendMessage("§e/townadmin money adjust <小镇全名> <带符号金额> <原因>");
                sender.sendMessage("§e/townadmin money reconcile");
            }
            case "tax" -> sender.sendMessage(
                    "§e/townadmin tax set <小镇全名> <百分比> <原因>");
            case "ledger" -> sender.sendMessage(
                    "§e/townadmin ledger view <小镇全名>");
            case "expand" -> sender.sendMessage(
                    "§e/townadmin expand view|preview <小镇全名> [方向]");
            default -> throw new IllegalArgumentException("未知经济帮助分类");
        }
    }

    private static void buffsHelp(CommandSender sender) {
        sender.sendMessage("§6公共 Buff 管理");
        sender.sendMessage("§e/townadmin buff list <小镇全名>");
        sender.sendMessage("§e/townadmin buff grant <小镇全名> <buffKey> <原因>");
        sender.sendMessage("§7公共 Buff 购买后不接受退款；管理员代购默认持续一小时。");
    }

    private static void townHelp(CommandSender sender) {
        sender.sendMessage("§6小镇管理");
        sender.sendMessage("§e/townadmin town view <小镇全名> §7查看小镇资料与投影状态");
        sender.sendMessage("§e/townadmin town delete <小镇全名> <原因> §7随后点击聊天确认按钮");
    }

    private static void memberHelp(CommandSender sender) {
        sender.sendMessage("§6成员与镇长管理");
        sender.sendMessage("§e/townadmin member add|remove <小镇全名>"
                + " <玩家> <原因>");
        sender.sendMessage("§e/townadmin member role <小镇全名> <玩家> <DEPUTY_MAYOR|MEMBER>");
        sender.sendMessage("§7普通玩家加入小镇使用申请制；管理员这里只保留直接添加和移除。");
        sender.sendMessage("§e/townadmin mayor transfer <小镇全名>"
                + " <玩家> <原因>");
    }

    private static void voteHelp(CommandSender sender) {
        sender.sendMessage("§6治理投票管理");
        sender.sendMessage("§e/townadmin vote create-kick <小镇全名> <目标玩家>");
        sender.sendMessage("§e/townadmin vote create-mayor <小镇全名> <候选玩家>");
        sender.sendMessage("§e/townadmin vote settle <voteId>");
        sender.sendMessage("§e/townadmin vote cancel <voteId> <原因>");
    }

    private static void landHelp(CommandSender sender) {
        sender.sendMessage("§6领地管理");
        sender.sendMessage("§e/townadmin land preview <小镇全名> §7在游戏内显示边界");
        sender.sendMessage("§e/townadmin land reconcile <小镇全名|all> [repair]");
        sender.sendMessage("§e/townadmin land rebuild <小镇全名|all> §7随后点击聊天确认按钮");
    }

    private record ApplicationRequest(ApplicationSnapshot application, String reason) {
    }

    private record TownDeleteRequest(UUID townId, String townName, long version, String reason) {
    }

    private record MemberRequest(UUID townId, String player, String reason) {
    }

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }

    private record LandReconcileRequest(List<TownMembers> states, boolean repair) {
    }

    private record LandRebuildRequest(boolean all, List<TownReference> towns) {
    }

    private record TownReference(UUID townId, String townName, long version) {
    }

    private record VoteCreateRequest(UUID townId, UUID targetId, VoteType type) {
    }

    private record MoneyAdjustment(UUID townId, long amountMinor, String reason) {
    }

    private record TaxAdjustment(UUID townId, int basisPoints, String reason) {
    }

    private record AdminExpansion(TownSnapshot town,
                                  List<cn.tianji.town.storage.economy.EconomyRepository
                                          .TerritoryUnitSnapshot> units,
                                  cn.tianji.town.core.land.TerritoryUnit preview) {
    }

    private record BuffGrantRequest(UUID townId, String townName,
                                    BuffDefinition definition, String reason) {
    }

}

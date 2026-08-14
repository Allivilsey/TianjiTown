package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.governance.VoteType;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.town.MemberRole;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 仅供隔离测试服务器使用的玩家业务接口，不复用管理员越权命令。
 */
final class TestCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "tianjitown.testcommand";
    private final TianjiTownPlugin plugin;

    TestCommand(TianjiTownPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!plugin.getConfig().getBoolean("test-command.enabled", false)) {
            fail(sender, "COMMAND", "TEST_INTERFACE_DISABLED", Map.of());
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) {
            fail(sender, "COMMAND", "PERMISSION_DENIED", Map.of());
            return true;
        }
        TownActions actions = plugin.townActions();
        if (actions == null || plugin.gateStatus().state() != GateStatus.State.READY) {
            fail(sender, "COMMAND", "SYSTEM_NOT_READY", Map.of());
            return true;
        }
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("query")) {
                Player actor = requireOnlinePlayer(args[1]);
                actions.queryActor(actor, output(sender));
                return true;
            }
            if (args.length >= 4 && args[0].equalsIgnoreCase("action")) {
                Player actor = requireOnlinePlayer(args[1]);
                if (dispatchAction(sender, actions, actor, args)) {
                    return true;
                }
            }
            usage(sender);
        } catch (IllegalArgumentException exception) {
            fail(sender, "COMMAND", "INVALID_ARGUMENT",
                    Map.of("detail", TownActionFailures.safeMessage(exception)));
        }
        return true;
    }

    private boolean dispatchAction(CommandSender sender, TownActions actions, Player actor,
                                   String[] args) {
        String domain = args[2].toLowerCase(Locale.ROOT);
        String verb = args[3].toLowerCase(Locale.ROOT);
        return switch (domain + ":" + verb) {
            case "application:select-site" -> {
                requireLength(args, 5);
                actions.selectApplicationSite(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "application:create" -> {
                requireLength(args, 11);
                actions.createApplication(actor, applicationText(args, 4),
                        List.of(uuid(args[9]), uuid(args[10])), output(sender));
                yield true;
            }
            case "application:update" -> {
                requireLength(args, 13);
                actions.updateApplication(actor, uuid(args[4]), applicationText(args, 6),
                        List.of(uuid(args[11]), uuid(args[12])), number(args[5]), output(sender));
                yield true;
            }
            case "application:submit" -> {
                requireLength(args, 5);
                actions.submitApplication(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "application:cancel" -> {
                requireLength(args, 5);
                actions.cancelApplication(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "join:apply" -> {
                requireLength(args, 5);
                actions.applyToTown(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "join:cancel" -> {
                requireLength(args, 5);
                actions.cancelJoinApplication(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "join:approve" -> {
                requireLength(args, 5);
                actions.approveJoinApplication(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "join:reject" -> {
                requireLength(args, 5);
                actions.rejectJoinApplication(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "member:role" -> {
                requireLength(args, 7);
                actions.changeMemberRole(actor, uuid(args[4]), uuid(args[5]),
                        MemberRole.valueOf(args[6].toUpperCase(Locale.ROOT)), output(sender));
                yield true;
            }
            case "member:kick" -> {
                requireLength(args, 6);
                actions.kickMember(actor, uuid(args[4]), uuid(args[5]), output(sender));
                yield true;
            }
            case "transfer:request" -> {
                requireLength(args, 6);
                actions.requestMayorTransfer(actor, uuid(args[4]), uuid(args[5]), output(sender));
                yield true;
            }
            case "transfer:decide" -> {
                requireLength(args, 6);
                actions.decideMayorTransfer(actor, uuid(args[4]), bool(args[5]), output(sender));
                yield true;
            }
            case "rules:acknowledge" -> {
                requireLength(args, 6);
                actions.acknowledgeRules(actor, uuid(args[4]), number(args[5]), output(sender));
                yield true;
            }
            case "vote:create" -> {
                requireLength(args, 7);
                actions.createVote(actor, uuid(args[4]),
                        VoteType.valueOf(args[5].toUpperCase(Locale.ROOT)), uuid(args[6]),
                        output(sender));
                yield true;
            }
            case "vote:cast" -> {
                requireLength(args, 6);
                actions.castVote(actor, uuid(args[4]), bool(args[5]), output(sender));
                yield true;
            }
            case "town:leave" -> {
                requireLength(args, 5);
                actions.leaveTown(actor, uuid(args[4]), output(sender));
                yield true;
            }
            case "town:profile" -> {
                requireLength(args, 11);
                actions.updateTownProfile(actor, uuid(args[4]), applicationText(args, 6),
                        number(args[5]), output(sender));
                yield true;
            }
            case "town:disband" -> {
                requireLength(args, 6);
                actions.disbandTown(actor, uuid(args[4]), number(args[5]), output(sender));
                yield true;
            }
            case "finance:tax" -> {
                requireLength(args, 6);
                actions.changeTaxRate(actor, uuid(args[4]), integer(args[5]), output(sender));
                yield true;
            }
            case "finance:acknowledge-tax" -> {
                requireLength(args, 5);
                actions.acknowledgeTaxRevision(actor, integer(args[4]), output(sender));
                yield true;
            }
            case "finance:donate" -> {
                requireLength(args, 5);
                actions.donate(actor, number(args[4]), output(sender));
                yield true;
            }
            case "buff:buy" -> {
                requireLength(args, 6);
                actions.buyBuff(actor, args[4], BuffDurationOption.parse(args[5]),
                        output(sender));
                yield true;
            }
            case "town:expand" -> {
                requireLength(args, 5);
                actions.expandTown(actor,
                        ExpansionDirection.valueOf(args[4].toUpperCase(Locale.ROOT)),
                        output(sender));
                yield true;
            }
            default -> false;
        };
    }

    private Player requireOnlinePlayer(String value) {
        Player player;
        try {
            player = plugin.getServer().getPlayer(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            player = plugin.getServer().getPlayerExact(value);
        }
        if (player == null || !player.isOnline()) {
            throw new IllegalArgumentException("actor 必须是在线玩家: " + value);
        }
        return player;
    }

    private static <T> Consumer<TownActionOutcome<T>> output(CommandSender sender) {
        return outcome -> sender.sendMessage(outcome.result().machineLine());
    }

    private static void usage(CommandSender sender) {
        fail(sender, "COMMAND", "USAGE", Map.of("syntax",
                "/testcommand query <actor> | /testcommand action <actor> <domain> <verb> ..."));
    }

    private static void fail(CommandSender sender, String action, String reason,
                             Map<String, ?> data) {
        sender.sendMessage(TownActionResult.failure(action, reason, data).machineLine());
    }

    private static void requireLength(String[] args, int expected) {
        if (args.length != expected) {
            throw new IllegalArgumentException("参数数量应为 " + expected + "，实际为 " + args.length);
        }
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("UUID 无效: " + value, exception);
        }
    }

    private static boolean bool(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no")) {
            return false;
        }
        throw new IllegalArgumentException("布尔值必须为 true/false: " + value);
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("整数无效: " + value, exception);
        }
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("整数无效: " + value, exception);
        }
    }

    private static ApplicationText applicationText(String[] args, int offset) {
        List<String> rules = java.util.Arrays.stream(args[offset + 4].split("\\|"))
                .map(String::strip).filter(rule -> !rule.isEmpty()).toList();
        return new ApplicationText(args[offset], args[offset + 1], args[offset + 2],
                args[offset + 3], rules);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                 @NotNull Command command,
                                                 @NotNull String alias,
                                                 @NotNull String[] args) {
        if (!plugin.getConfig().getBoolean("test-command.enabled", false)
                || !sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        List<String> candidates = switch (args.length) {
            case 1 -> List.of("action", "query");
            case 2 -> plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).sorted().toList();
            case 3 -> args[0].equalsIgnoreCase("action")
                    ? List.of("application", "buff", "finance", "join", "member", "rules", "town",
                    "transfer", "vote") : List.of();
            case 4 -> verbs(args[2]);
            default -> List.of();
        };
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static List<String> verbs(String domain) {
        return switch (domain.toLowerCase(Locale.ROOT)) {
            case "application" -> List.of("cancel", "create", "select-site", "submit", "update");
            case "buff" -> List.of("buy");
            case "finance" -> List.of("acknowledge-tax", "donate", "tax");
            case "join" -> List.of("apply", "cancel", "approve", "reject");
            case "member" -> List.of("role", "kick");
            case "rules" -> List.of("acknowledge");
            case "town" -> List.of("disband", "expand", "leave", "profile");
            case "transfer" -> List.of("request", "decide");
            case "vote" -> List.of("create", "cast");
            default -> List.of();
        };
    }
}

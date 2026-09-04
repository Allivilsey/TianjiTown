package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.storage.town.AuditSnapshot;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

final class TownAdminCommand implements CommandExecutor {
    private static final UUID CONSOLE_ID = new UUID(0, 0);
    private final TownAdminApplicationCommands townAdminApplicationCommands;
    private final TownAdminGovernanceCommands townAdminGovernanceCommands;
    private final TownAdminEconomyCommands townAdminEconomyCommands;
    private final TownAdminLandCommands townAdminLandCommands;
    private final TianjiTownPlugin plugin;
    private final CommandConfirmationManager confirmations = new CommandConfirmationManager();

    TownAdminCommand(TianjiTownPlugin plugin) {
        this.plugin = plugin;
        this.townAdminApplicationCommands = new TownAdminApplicationCommands(this, plugin);
        this.townAdminGovernanceCommands = new TownAdminGovernanceCommands(this, plugin);
        this.townAdminEconomyCommands = new TownAdminEconomyCommands(this, plugin);
        this.townAdminLandCommands = new TownAdminLandCommands(this, plugin);
    }

    void send(CommandSender recipient, String key) {
        plugin.messages().send(recipient, key);
    }

    void send(CommandSender recipient, String key, Map<String, ?> placeholders) {
        plugin.messages().send(recipient, key, placeholders);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!TownAdminPermissions.hasAny(sender::hasPermission)) {
                send(sender, "chat.admin.no-permission");
                return true;
            }
            help(sender, null);
            return true;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (!TownAdminPermissions.canUseRoot(sender::hasPermission, root)) {
            send(sender, "chat.admin.no-permission");
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
                plugin.reloadMessages();
                send(sender, "chat.admin.reload");
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
                default -> {
                    send(sender, "chat.admin.unknown-command", Map.of("command", args[0]));
                    yield true;
                }
            };
        } catch (IllegalArgumentException exception) {
            String detail = exception instanceof TownCommandParser.ParseException parse
                    ? plugin.messages().text(parse.messageKey(), parse.placeholders())
                    : safeMessage(exception);
            send(sender, "chat.admin.argument-error", Map.of(
                    "detail", detail));
            return true;
        }
    }

    private void status(CommandSender sender) {
        GateStatus status = plugin.gateStatus();
        send(sender, "chat.admin.status-header", Map.of(
                "version", plugin.getPluginMeta().getVersion(), "state", status.state()));
        status.details().forEach(detail -> send(sender, "chat.admin.status-detail",
                Map.of("detail", detail)));
        TownRuntime runtime = plugin.townRuntime();
        if (runtime != null) {
            send(sender, "chat.admin.status-sqlite", Map.of("state",
                    runtime.databaseAvailable() ? "READY" : "WRITE_LOCKED"));
            send(sender, "chat.admin.status-buffs", Map.of(
                    "state", runtime.buffs().buffShopEnabled() ? "OPEN" : "PAUSED",
                    "count", runtime.buffs().settings().buffs().size()));
            send(sender, "chat.admin.status-refund", Map.of(
                    "state", runtime.bonuses().buildingRefundEnabled() ? "ENABLED" : "PAUSED",
                    "blacklist", runtime.bonuses().settings().buildingRefund()
                            .blacklist().size(),
                    "limit", runtime.bonuses().settings().buildingRefund().weeklyLimit()));
            send(sender, "chat.admin.status-beacon", Map.of("state",
                    runtime.bonuses().beaconEnabled() ? "ENABLED" : "PAUSED"));
            TownBonusRuntime.DiagnosticResult diagnostic = runtime.bonuses().lastDiagnostic();
            if (diagnostic.report() == null) {
                send(sender, "chat.admin.status-diagnostic-no-report", Map.of(
                        "detail", diagnostic.detail()));
            } else {
                send(sender, "chat.admin.status-diagnostic", Map.of(
                        "detail", diagnostic.detail(), "report", safeText(diagnostic.report())));
            }
        }
        send(sender, "chat.admin.status-player-entry", Map.of("state",
                maintenanceMode() ? "MAINTENANCE" : "OPEN"));
    }

    private boolean diagnose(CommandSender sender, TownRuntime runtime, String[] args) {
        if (args.length > 2) {
            throw messageArgument("chat.admin.usage-diagnose");
        }
        int days = args.length == 2 ? Integer.parseInt(args[1])
                : runtime.bonuses().settings().operations().quickShopDiagnosticDays();
        runtime.bonuses().diagnose(sender, days);
        return true;
    }

    private boolean maintenance(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sendMaintenanceStatus(sender);
            return true;
        }
        if (args.length != 2) {
            throw messageArgument("chat.admin.usage-maintenance");
        }
        if (args[1].equalsIgnoreCase("status")) {
            sendMaintenanceStatus(sender);
            return true;
        }
        boolean enabled;
        if (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("enable")) {
            enabled = true;
        } else if (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("disable")) {
            enabled = false;
        } else {
            throw messageArgument("chat.admin.usage-maintenance");
        }
        plugin.getConfig().set("town.maintenance-mode", enabled);
        plugin.saveConfig();
        send(sender, enabled ? "chat.admin.maintenance-enabled"
                : "chat.admin.maintenance-disabled");
        return true;
    }

    private void sendMaintenanceStatus(CommandSender sender) {
        send(sender, maintenanceMode() ? "chat.admin.maintenance-status-enabled"
                : "chat.admin.maintenance-status-disabled");
    }

    IllegalArgumentException messageArgument(String key) {
        return new IllegalArgumentException(plugin.messages().text(key));
    }

    IllegalArgumentException messageArgument(String key, Map<String, ?> placeholders) {
        return new IllegalArgumentException(plugin.messages().text(key, placeholders));
    }

    void requireMessageLength(String[] args, int minimum, String key) {
        if (args.length < minimum) {
            throw messageArgument(key);
        }
    }

    void requireMessageLength(String[] args, int minimum, String key,
                                      Map<String, ?> placeholders) {
        if (args.length < minimum) {
            throw messageArgument(key, placeholders);
        }
    }

    private boolean confirm(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "chat.admin.confirm-invalid");
            return true;
        }
        CommandConfirmationManager.Result result = confirmations.consume(ownerKey(sender), args[1]);
        switch (result.status()) {
            case CONFIRMED -> {
                send(sender, "chat.admin.confirm-success", Map.of(
                        "description", result.description()));
                try {
                    result.action().run();
                } catch (RuntimeException exception) {
                    send(sender, "chat.admin.confirm-start-failed", Map.of(
                            "detail", safeMessage(exception)));
                    plugin.getLogger().warning(plugin.messages().plainText(
                            "log.admin.confirmation-start-failure",
                            Map.of("detail", safeText(safeMessage(exception)))));
                }
            }
            case EXPIRED -> send(sender, "chat.admin.confirm-expired");
            case NOT_OWNER -> send(sender, "chat.admin.confirm-not-owner");
            case NOT_FOUND, CANCELLED -> send(sender, "chat.admin.confirm-unavailable");
        }
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "chat.admin.cancel-invalid");
            return true;
        }
        CommandConfirmationManager.Result result = confirmations.cancel(ownerKey(sender), args[1]);
        switch (result.status()) {
            case CANCELLED -> send(sender, "chat.admin.cancel-success", Map.of(
                    "description", result.description()));
            case EXPIRED -> send(sender, "chat.admin.cancel-expired");
            case NOT_OWNER -> send(sender, "chat.admin.cancel-not-owner");
            case NOT_FOUND, CONFIRMED -> send(sender, "chat.admin.confirm-unavailable");
        }
        return true;
    }

    void requestConfirmation(CommandSender sender, String description, Runnable action) {
        CommandConfirmationManager.Confirmation confirmation = confirmations.request(
                ownerKey(sender), description, action);
        String confirmCommand = "/townadmin confirm " + confirmation.token();
        String cancelCommand = "/townadmin cancel " + confirmation.token();
        Component message = plugin.messages().component("chat.admin.confirmation-prompt", Map.of(
                        "description", description))
                .append(plugin.messages().component("chat.buttons.confirm")
                        .decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand(confirmCommand))
                        .hoverEvent(HoverEvent.showText(
                                plugin.messages().component("chat.buttons.confirm-tooltip"))))
                .append(Component.space())
                .append(plugin.messages().component("chat.buttons.cancel")
                        .clickEvent(ClickEvent.runCommand(cancelCommand))
                        .hoverEvent(HoverEvent.showText(
                                plugin.messages().component("chat.buttons.cancel-tooltip"))));
        sender.sendMessage(message);
    }

    private boolean audit(CommandSender sender, TownRuntime runtime, String[] args) {
        if (args.length > 2) {
            throw messageArgument("chat.admin.usage-audit");
        }
        int limit;
        try {
            limit = args.length == 2 ? Integer.parseInt(args[1]) : 20;
        } catch (NumberFormatException exception) {
            throw messageArgument("chat.admin.audit-limit-integer");
        }
        if (limit < 1 || limit > 200) {
            throw messageArgument("chat.admin.audit-limit-range");
        }
        runtime.read(sender, () -> runtime.repository().auditLog(limit), records -> {
            send(sender, "chat.admin.audit-title");
            for (AuditSnapshot record : records) {
                send(sender, "chat.admin.audit-record", Map.of("id", record.id(),
                        "created", record.createdAt(), "actor", record.actorName(),
                        "action", record.action(), "target-type", record.targetType(),
                        "target-id", record.targetId(), "reason", record.reason()));
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
            send(sender, "chat.admin.station-player-only");
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

    private void stationHelp(CommandSender sender) {
        send(sender, "chat.admin.station-help-create");
        send(sender, "chat.admin.station-help-list");
    }

    private boolean handbook(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else {
            target = sender instanceof Player player ? player : null;
        }
        if (target == null) {
            send(sender, "chat.admin.handbook-target");
            return true;
        }
        boolean delivered = plugin.townUi().giveHandbook(target, true);
        if (!sender.equals(target) && delivered) {
            send(sender, "chat.admin.handbook-delivered", Map.of("player", target.getName()));
        } else if (!sender.equals(target)) {
            send(sender, "chat.admin.handbook-cooldown", Map.of("player", target.getName()));
        }
        return true;
    }

    private boolean application(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminApplicationCommands.application(sender, runtime, args);
    }

    private boolean town(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminApplicationCommands.town(sender, runtime, args);
    }

    private boolean member(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminGovernanceCommands.member(sender, runtime, args);
    }

    private boolean vote(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminGovernanceCommands.vote(sender, runtime, args);
    }

    private boolean mayor(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminGovernanceCommands.mayor(sender, runtime, args);
    }

    private boolean land(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminLandCommands.land(sender, runtime, args);
    }

    private boolean money(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminEconomyCommands.money(sender, runtime, args);
    }

    private boolean tax(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminEconomyCommands.tax(sender, runtime, args);
    }

    private boolean ledger(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminEconomyCommands.ledger(sender, runtime, args);
    }

    private boolean expand(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminLandCommands.expand(sender, runtime, args);
    }

    private boolean buff(CommandSender sender, TownRuntime runtime, String[] args) {
        return townAdminEconomyCommands.buff(sender, runtime, args);
    }

    void reconcileOne(CommandSender sender, TownRuntime runtime, UUID townId,
                              boolean repair) {
        townAdminLandCommands.reconcileOne(sender, runtime, townId, repair);
    }

    TownSnapshot requireTown(TownRuntime runtime, String townName) {
        return runtime.repository().findTownByName(townName)
                .orElseThrow(() -> messageArgument("chat.admin.town-not-found",
                        Map.of("town", safeText(townName))));
    }

    TownSnapshot requireTown(TownRuntime runtime, UUID townId) {
        return runtime.repository().findTown(townId)
                .orElseThrow(() -> messageArgument("chat.admin.town-record-not-found"));
    }

    private TownRuntime requireRuntime(CommandSender sender) {
        TownRuntime runtime = plugin.townRuntime();
        if (runtime == null) {
            send(sender, "chat.admin.runtime-not-ready");
        }
        return runtime;
    }

    static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_ID;
    }

    private static String ownerKey(CommandSender sender) {
        return sender instanceof Player player ? "player:" + player.getUniqueId()
                : "sender:" + sender.getName().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("deprecation")
    static UUID playerId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(value);
            return player.getUniqueId();
        }
    }

    void requirePermission(CommandSender sender, String permission) {
        if (!TownAdminPermissions.has(sender::hasPermission, permission)) {
            throw messageArgument("chat.admin.permission-missing", Map.of(
                    "permission", safeText(permission)));
        }
    }

    static boolean sameName(String first, String second) {
        return ApplicationText.normalizeNameKey(first)
                .equals(ApplicationText.normalizeNameKey(second));
    }

    static List<String> townNames(List<TownSnapshot> towns) {
        return towns.stream().map(town -> town.profile().name()).toList();
    }

    void requireVersion(TownSnapshot town, long expectedVersion) {
        if (town.version() != expectedVersion) {
            throw messageArgument("chat.admin.confirmation-stale", Map.of(
                    "town", safeText(town.profile().name())));
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("town.maintenance-mode", false);
    }

    void applicationHelp(CommandSender sender) {
        send(sender, "chat.admin.help-application-list");
        send(sender, "chat.admin.help-application-review");
    }

    private void help(CommandSender sender, String topic) {
        if (topic == null || topic.isBlank()) {
            send(sender, "chat.admin.help-title", Map.of(
                    "version", plugin.getPluginMeta().getVersion()));
            send(sender, "chat.admin.help-usage");
            configuredRootHelpEntries(sender::hasPermission).forEach(sender::sendMessage);
            send(sender, "chat.admin.help-placeholder-hint");
            return;
        }
        if (!TownAdminPermissions.canViewHelpTopic(sender::hasPermission, topic)) {
            send(sender, "chat.admin.help-forbidden");
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
                send(sender, "chat.admin.help-unknown", Map.of("topic", topic));
                help(sender, null);
            }
        }
    }

    private List<String> configuredRootHelpEntries(Predicate<String> hasPermission) {
        return rootHelpEntries(hasPermission, plugin.messages()::text);
    }

    static List<String> rootHelpEntries(Predicate<String> hasPermission,
                                        Function<String, String> messageResolver) {
        List<String> entries = new ArrayList<>();
        if (TownAdminPermissions.has(hasPermission, TownAdminPermissions.OPERATIONS)) {
            entries.add(messageResolver.apply("chat.admin.help-entry-system"));
        }
        if (hasPermission.test(TownAdminPermissions.ROOT)) {
            entries.add(messageResolver.apply("chat.admin.help-entry-station"));
            entries.add(messageResolver.apply("chat.admin.help-entry-application"));
            entries.add(messageResolver.apply("chat.admin.help-entry-town"));
            entries.add(messageResolver.apply("chat.admin.help-entry-member"));
            entries.add(messageResolver.apply("chat.admin.help-entry-vote"));
            entries.add(messageResolver.apply("chat.admin.help-entry-land"));
        }
        if (TownAdminPermissions.has(hasPermission, TownAdminPermissions.MONEY)) {
            entries.add(messageResolver.apply("chat.admin.help-entry-money"));
        }
        if (TownAdminPermissions.has(hasPermission, TownAdminPermissions.TAX)) {
            entries.add(messageResolver.apply("chat.admin.help-entry-tax"));
        }
        if (TownAdminPermissions.has(hasPermission, TownAdminPermissions.LEDGER)) {
            entries.add(messageResolver.apply("chat.admin.help-entry-ledger"));
        }
        if (TownAdminPermissions.has(hasPermission, TownAdminPermissions.EXPAND)) {
            entries.add(messageResolver.apply("chat.admin.help-entry-expand"));
        }
        if (TownAdminPermissions.has(hasPermission, TownAdminPermissions.BUFF)) {
            entries.add(messageResolver.apply("chat.admin.help-entry-buff"));
        }
        return List.copyOf(entries);
    }

    private void systemHelp(CommandSender sender) {
        send(sender, "chat.admin.help-system-title");
        send(sender, "chat.admin.help-system-status");
        send(sender, "chat.admin.help-system-reload");
        send(sender, "chat.admin.help-system-maintenance");
        send(sender, "chat.admin.help-system-audit");
        send(sender, "chat.admin.help-system-diagnose");
    }

    private void economyHelp(CommandSender sender, String topic) {
        send(sender, "chat.admin.help-economy-title");
        switch (topic.toLowerCase(Locale.ROOT)) {
            case "money" -> {
                send(sender, "chat.admin.help-economy-money-view");
                send(sender, "chat.admin.help-economy-money-adjust");
                send(sender, "chat.admin.help-economy-money-reconcile");
            }
            case "tax" -> send(sender, "chat.admin.help-economy-tax");
            case "ledger" -> send(sender, "chat.admin.help-economy-ledger");
            case "expand" -> send(sender, "chat.admin.help-economy-expand");
            default -> throw messageArgument("chat.admin.help-unknown", Map.of("topic", topic));
        }
    }

    private void buffsHelp(CommandSender sender) {
        send(sender, "chat.admin.help-buff-title");
        send(sender, "chat.admin.help-buff-list");
        send(sender, "chat.admin.help-buff-grant");
        send(sender, "chat.admin.help-buff-note");
    }

    private void townHelp(CommandSender sender) {
        send(sender, "chat.admin.help-town-title");
        send(sender, "chat.admin.help-town-view");
        send(sender, "chat.admin.help-town-delete");
    }

    private void memberHelp(CommandSender sender) {
        send(sender, "chat.admin.help-member-title");
        send(sender, "chat.admin.help-member-add-remove");
        send(sender, "chat.admin.help-member-role");
        send(sender, "chat.admin.help-member-note");
        send(sender, "chat.admin.help-member-mayor");
    }

    private void voteHelp(CommandSender sender) {
        send(sender, "chat.admin.help-vote-title");
        send(sender, "chat.admin.help-vote-kick");
        send(sender, "chat.admin.help-vote-mayor");
        send(sender, "chat.admin.help-vote-settle");
        send(sender, "chat.admin.help-vote-cancel");
    }

    private void landHelp(CommandSender sender) {
        send(sender, "chat.admin.help-land-title");
        send(sender, "chat.admin.help-land-preview");
        send(sender, "chat.admin.help-land-reconcile");
        send(sender, "chat.admin.help-land-rebuild");
    }

}

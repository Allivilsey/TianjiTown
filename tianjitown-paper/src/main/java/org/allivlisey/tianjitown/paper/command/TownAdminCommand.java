package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.bonus.TownBonusRuntime;
import org.allivlisey.tianjitown.paper.runtime.GateStatus;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.storage.town.AuditSnapshot;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Usage;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Range;
import revxrsal.commands.annotation.Suggest;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

public final class TownAdminCommand {
    private static final UUID CONSOLE_ID = new UUID(0, 0);
    private final TownAdminApplicationCommands townAdminApplicationCommands;
    private final TownAdminGovernanceCommands townAdminGovernanceCommands;
    private final TownAdminEconomyCommands townAdminEconomyCommands;
    private final TownAdminLandCommands townAdminLandCommands;
    private final TownAdminApplicationFeeCommands applicationFeeCommands;
    private final TownAdminRecoveryCommands recoveryCommands;
    private final TianjiTownPlugin plugin;
    private final CommandConfirmationManager confirmations = new CommandConfirmationManager();

    public TownAdminCommand(TianjiTownPlugin plugin) {
        this.plugin = plugin;
        this.townAdminApplicationCommands = new TownAdminApplicationCommands(this, plugin);
        this.townAdminGovernanceCommands = new TownAdminGovernanceCommands(this, plugin);
        this.townAdminEconomyCommands = new TownAdminEconomyCommands(this, plugin);
        this.townAdminLandCommands = new TownAdminLandCommands(this, plugin);
        this.applicationFeeCommands = new TownAdminApplicationFeeCommands(this, plugin);
        this.recoveryCommands = new TownAdminRecoveryCommands(this, plugin);
    }

    public void send(CommandSender recipient, String key) {
        plugin.messages().send(recipient, key);
    }

    public void send(CommandSender recipient, String key, Map<String, ?> placeholders) {
        plugin.messages().send(recipient, key, placeholders);
    }

    public void register(revxrsal.commands.Lamp<BukkitCommandActor> lamp) {
        lamp.register(this, townAdminApplicationCommands, townAdminGovernanceCommands,
                townAdminEconomyCommands, townAdminLandCommands, applicationFeeCommands, recoveryCommands);
    }

    @Command("tianjitown")
    @Usage("/tianjitown help")
    @AdminAccess
    public void root(CommandSender sender) {
        help(sender, null);
    }

    @Command("tianjitown help")
    @Usage("/tianjitown help [分类]")
    @AdminAccess
    public void helpCommand(CommandSender sender, @Optional @Single String topic) {
        help(sender, topic);
    }

    @Command("tianjitown reload")
    @Usage("/tianjitown reload")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void reload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.reloadMessages();
        send(sender, "chat.admin.reload");
    }

    @Command("tianjitown status")
    @Usage("/tianjitown status")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void status(CommandSender sender) {
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

    @Command("tianjitown diagnose")
    @Usage("/tianjitown diagnose [1~180天]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void diagnose(CommandSender sender, TownRuntime runtime,
                         @Optional @Range(min = 1, max = 180) @Suggest({"1", "7", "14", "30", "90", "180"}) Integer days) {
        runtime.bonuses().diagnose(sender, days == null
                ? runtime.bonuses().settings().operations().quickShopDiagnosticDays() : days);
    }

    @Command({"tianjitown maintenance", "tianjitown maintenance status"})
    @AdminAccess(TownAdminPermissions.ROOT)
    public void maintenanceStatus(CommandSender sender) {
        sendMaintenanceStatus(sender);
    }

    @Command("tianjitown maintenance on")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void enableMaintenance(CommandSender sender) {
        setMaintenance(sender, true);
    }

    @Command("tianjitown maintenance off")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void disableMaintenance(CommandSender sender) {
        setMaintenance(sender, false);
    }

    private void setMaintenance(CommandSender sender, boolean enabled) {
        plugin.getConfig().set("town.maintenance-mode", enabled);
        plugin.saveConfig();
        send(sender, enabled ? "chat.admin.maintenance-enabled" : "chat.admin.maintenance-disabled");
    }

    private void sendMaintenanceStatus(CommandSender sender) {
        send(sender, maintenanceMode() ? "chat.admin.maintenance-status-enabled"
                : "chat.admin.maintenance-status-disabled");
    }

    public IllegalArgumentException messageArgument(String key) {
        return new IllegalArgumentException(plugin.messages().text(key));
    }

    public IllegalArgumentException messageArgument(String key, Map<String, ?> placeholders) {
        return new IllegalArgumentException(plugin.messages().text(key, placeholders));
    }

    @revxrsal.commands.annotation.SecretCommand
    @Command("tianjitown confirm")
    @Usage("/tianjitown confirm <确认码>")
    @AdminAccess
    public void confirm(CommandSender sender, @Single String token) {
        CommandConfirmationManager.Result result = confirmations.consume(ownerKey(sender), token);
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
    }

    @revxrsal.commands.annotation.SecretCommand
    @Command("tianjitown cancel")
    @Usage("/tianjitown cancel <确认码>")
    @AdminAccess
    public void cancel(CommandSender sender, @Single String token) {
        CommandConfirmationManager.Result result = confirmations.cancel(ownerKey(sender), token);
        switch (result.status()) {
            case CANCELLED -> send(sender, "chat.admin.cancel-success", Map.of(
                    "description", result.description()));
            case EXPIRED -> send(sender, "chat.admin.cancel-expired");
            case NOT_OWNER -> send(sender, "chat.admin.cancel-not-owner");
            case NOT_FOUND, CONFIRMED -> send(sender, "chat.admin.confirm-unavailable");
        }
    }

    public void requestConfirmation(CommandSender sender, String description, Runnable action) {
        CommandConfirmationManager.Confirmation confirmation = confirmations.request(
                ownerKey(sender), description, action);
        String confirmCommand = "/tianjitown confirm " + confirmation.token();
        String cancelCommand = "/tianjitown cancel " + confirmation.token();
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

    void requireRecoveryAccess(CommandSender sender, TownRuntime expected) {
        if (!sender.hasPermission(TownAdminPermissions.ROOT)) {
            throw messageArgument("chat.admin.no-permission");
        }
        if (plugin.townRuntime() != expected) {
            throw new IllegalStateException("运行时已变化，请重新查询后确认");
        }
    }

    @Command("tianjitown audit")
    @Usage("/tianjitown audit [1~200]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void audit(CommandSender sender, TownRuntime runtime,
                      @Default("20") @Range(min = 1, max = 200) @Suggest({"10", "20", "50", "100", "200"}) int limit) {
        runtime.read(sender, () -> runtime.repository().auditLog(limit), records -> {
            send(sender, "chat.admin.audit-title");
            for (AuditSnapshot record : records) {
                send(sender, "chat.admin.audit-record", Map.of("id", record.id(),
                        "created", record.createdAt(), "actor", record.actorName(),
                        "action", record.action(), "target-type", record.targetType(),
                        "target-id", record.targetId(), "reason", record.reason()));
            }
        });
    }

    @Command("tianjitown station")
    @Usage("/tianjitown station")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void station(CommandSender sender, TownRuntime runtime) {
        stationHelp(sender);
    }

    @Command("tianjitown station list")
    @Usage("/tianjitown station list")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void listStations(CommandSender sender, TownRuntime runtime) {
        plugin.townUi().listStations(sender);
    }

    @Command("tianjitown station create")
    @Usage("/tianjitown station create")
    @AdminAccess(value = TownAdminPermissions.ROOT, playerOnly = true)
    public void createStation(Player player, TownRuntime runtime) {
        plugin.townUi().createStation(player);
    }

    @Command("tianjitown station remove")
    @Usage("/tianjitown station remove")
    @AdminAccess(value = TownAdminPermissions.ROOT, playerOnly = true)
    public void removeStation(Player player, TownRuntime runtime) {
        plugin.townUi().removeStation(player);
    }

    @Command("tianjitown station info")
    @Usage("/tianjitown station info")
    @AdminAccess(value = TownAdminPermissions.ROOT, playerOnly = true)
    public void stationInfo(Player player, TownRuntime runtime) {
        plugin.townUi().showStationInfo(player);
    }

    private void stationHelp(CommandSender sender) {
        send(sender, "chat.admin.help-station-title");
        send(sender, "chat.admin.station-help-create");
        send(sender, "chat.admin.station-help-list");
        send(sender, "chat.admin.help-station-handbook");
    }

    @Command("tianjitown open")
    @Usage("/tianjitown open <玩家>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void open(CommandSender sender, TownRuntime runtime,
                     @revxrsal.commands.annotation.NotSender Player target) {
        plugin.townUi().openMain(target);
    }

    @Command("tianjitown handbook")
    @Usage("/tianjitown handbook [玩家]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void handbook(CommandSender sender, TownRuntime runtime, @Optional @revxrsal.commands.annotation.NotSender Player target) {
        if (target == null && sender instanceof Player player) {
            target = player;
        }
        if (target == null) {
            send(sender, "chat.admin.handbook-target");
            return;
        }
        plugin.townUi().giveHandbookByAdmin(target);
        if (!sender.equals(target)) {
            send(sender, "chat.admin.handbook-delivered", Map.of("player", target.getName()));
        }
    }

    public void reconcileOne(CommandSender sender, TownRuntime runtime, UUID townId,
                              boolean repair) {
        townAdminLandCommands.reconcileOne(sender, runtime, townId, repair);
    }

    public TownSnapshot requireTown(TownRuntime runtime, String townName) {
        return runtime.repository().findTownByCode(townName)
                .orElseThrow(() -> messageArgument("chat.admin.town-not-found",
                        Map.of("town", safeText(townName))));
    }

    public TownSnapshot requireTown(TownRuntime runtime, UUID townId) {
        return runtime.repository().findTown(townId)
                .orElseThrow(() -> messageArgument("chat.admin.town-record-not-found"));
    }

    public static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE_ID;
    }

    private static String ownerKey(CommandSender sender) {
        return sender instanceof Player player ? "player:" + player.getUniqueId()
                : "sender:" + sender.getName().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("deprecation")
    public static UUID playerId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(value);
            return player.getUniqueId();
        }
    }

    public static boolean sameCode(String first, String second) {
        return first.equalsIgnoreCase(second);
    }

    public static List<String> townCodes(List<TownSnapshot> towns) {
        return towns.stream().map(town -> town.profile().residenceName()).toList();
    }

    public void requireVersion(TownSnapshot town, long expectedVersion) {
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

    public static String safeText(Object value) {
        return org.allivlisey.tianjitown.core.time.TownTime.display(value).replace('&', '＆').replace('§', '�');
    }

    private boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("town.maintenance-mode", false);
    }

    public void applicationHelp(CommandSender sender) {
        send(sender, "chat.admin.help-application-title");
        send(sender, "chat.admin.help-application-list");
        send(sender, "chat.admin.help-application-delate");
        send(sender, "chat.admin.help-application-clearcd");
        send(sender, "chat.admin.application-fee-help");
    }

    private void help(CommandSender sender, String topic) {
        if (topic == null || topic.isBlank()) {
            send(sender, "chat.admin.help-title", Map.of(
                    "version", plugin.getPluginMeta().getVersion()));
            send(sender, "chat.admin.help-usage");
            visibleHelpTopics(sender::hasPermission).forEach(category -> sender.sendMessage(
                    plugin.messages().component("chat.admin.help-entry-" + category)
                            .clickEvent(ClickEvent.runCommand("/tianjitown help " + category))
                            .hoverEvent(HoverEvent.showText(plugin.messages().component(
                                    "chat.admin.help-topic-tooltip", Map.of("topic", category))))));
            send(sender, "chat.admin.help-placeholder-hint");
            return;
        }
        topic = topic.toLowerCase(Locale.ROOT);
        if (!TownAdminPermissions.HELP_TOPICS.contains(topic)) {
            send(sender, "chat.admin.help-unknown", Map.of("topic", safeText(topic)));
            help(sender, null);
            return;
        }
        if (!TownAdminPermissions.canViewHelpTopic(sender::hasPermission, topic)) {
            send(sender, "chat.admin.help-forbidden");
            return;
        }
        switch (topic) {
            case "system" -> systemHelp(sender);
            case "station" -> stationHelp(sender);
            case "application" -> applicationHelp(sender);
            case "town" -> townHelp(sender);
            case "member" -> memberHelp(sender);
            case "vote" -> voteHelp(sender);
            case "land" -> landHelp(sender);
            case "money", "tax", "ledger" -> economyHelp(sender, topic);
            case "buff" -> buffsHelp(sender);
            default -> throw new IllegalStateException("Unhandled help topic: " + topic);
        }
        send(sender, "chat.admin.help-placeholder-hint");
        sender.sendMessage(plugin.messages().component("chat.admin.help-back")
                .clickEvent(ClickEvent.runCommand("/tianjitown help")));
    }

    private static List<String> visibleHelpTopics(Predicate<String> hasPermission) {
        return TownAdminPermissions.HELP_TOPICS.stream()
                .filter(topic -> TownAdminPermissions.canViewHelpTopic(hasPermission, topic)).toList();
    }

    public static List<String> rootHelpEntries(Predicate<String> hasPermission,
                                        Function<String, String> messageResolver) {
        return visibleHelpTopics(hasPermission).stream()
                .map(topic -> messageResolver.apply("chat.admin.help-entry-" + topic)).toList();
    }

    private void systemHelp(CommandSender sender) {
        send(sender, "chat.admin.help-system-title");
        send(sender, "chat.admin.help-system-status");
        if (sender.hasPermission(TownAdminPermissions.ROOT)) {
            send(sender, "chat.admin.help-system-reload");
            send(sender, "chat.admin.help-system-open");
            send(sender, "chat.admin.help-system-maintenance");
            send(sender, "chat.admin.help-system-audit");
        }
        send(sender, "chat.admin.help-system-diagnose");
    }

    private void economyHelp(CommandSender sender, String topic) {
        send(sender, "chat.admin.help-economy-title");
        switch (topic.toLowerCase(Locale.ROOT)) {
            case "money" -> {
                send(sender, "chat.admin.help-economy-money-view");
                send(sender, "chat.admin.help-economy-money-adjust");
                send(sender, "chat.admin.recovery-help");
                send(sender, "chat.admin.income-tax-recovery-help");
            }
            case "tax" -> send(sender, "chat.admin.help-economy-tax");
            case "ledger" -> send(sender, "chat.admin.help-economy-ledger");
            default -> throw messageArgument("chat.admin.help-unknown", Map.of("topic", topic));
        }
    }

    private void buffsHelp(CommandSender sender) {
        send(sender, "chat.admin.help-buff-title");
        send(sender, "chat.admin.help-buff-list");
        send(sender, "chat.admin.help-buff-set");
        send(sender, "chat.admin.help-buff-note");
    }

    private void townHelp(CommandSender sender) {
        send(sender, "chat.admin.help-town-title");
        send(sender, "chat.admin.help-town-list");
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
        send(sender, "chat.admin.help-vote-cancel");
    }

    private void landHelp(CommandSender sender) {
        send(sender, "chat.admin.help-land-title");
        send(sender, "chat.admin.help-land-preview");
        send(sender, "chat.admin.help-land-reconcile");
        send(sender, "chat.admin.help-land-rebuild");
    }

}

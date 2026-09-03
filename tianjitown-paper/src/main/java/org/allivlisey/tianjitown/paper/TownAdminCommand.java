package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.core.land.ExpansionDirection;
import org.allivlisey.tianjitown.core.land.ExpansionPricing;
import org.allivlisey.tianjitown.core.land.TerritoryRules;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.AuditSnapshot;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

final class TownAdminCommand implements CommandExecutor {
    private static final UUID CONSOLE_ID = new UUID(0, 0);
    private final TianjiTownPlugin plugin;
    private final CommandConfirmationManager confirmations = new CommandConfirmationManager();

    TownAdminCommand(TianjiTownPlugin plugin) {
        this.plugin = plugin;
    }

    private void send(CommandSender recipient, String key) {
        plugin.messages().send(recipient, key);
    }

    private void send(CommandSender recipient, String key, Map<String, ?> placeholders) {
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

    private IllegalArgumentException messageArgument(String key) {
        return new IllegalArgumentException(plugin.messages().text(key));
    }

    private IllegalArgumentException messageArgument(String key, Map<String, ?> placeholders) {
        return new IllegalArgumentException(plugin.messages().text(key, placeholders));
    }

    private void requireMessageLength(String[] args, int minimum, String key) {
        if (args.length < minimum) {
            throw messageArgument(key);
        }
    }

    private void requireMessageLength(String[] args, int minimum, String key,
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

    private void requestConfirmation(CommandSender sender, String description, Runnable action) {
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
        requireMessageLength(args, 4, "chat.admin.usage-application-review",
                Map.of("action", action));
        runtime.read(sender, () -> {
            List<ApplicationSnapshot> candidates = runtime.repository()
                    .listApplicationsForCompletion(500);
            TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(args, 2,
                    candidates.stream().map(candidate -> candidate.text().name()).toList(),
                    plugin.messages()::plainText);
            ApplicationSnapshot application = candidates.stream()
                    .filter(candidate -> sameName(candidate.text().name(), parsed.townName()))
                    .findFirst().orElseThrow(() -> messageArgument(
                            "chat.admin.application-not-found",
                            Map.of("town", safeText(parsed.townName()))));
            return new ApplicationRequest(application, parsed.reason());
        }, request -> {
            ApplicationSnapshot application = request.application();
            if (action.equals("approve")) {
                String key = application.status() == org.allivlisey.tianjitown.core.application.ApplicationStatus.PROVISION_FAILED
                        ? "town:retry:" + application.id() + ":" + application.version()
                        : "town:approve:" + application.id();
                runtime.provision(sender, application.id(), actorId(sender), sender.getName(),
                        request.reason(), key, result -> {
                            if (result.application() != null) {
                                plugin.townUi().notifyApplicationDecision(result.application());
                            }
                        });
            } else if (action.equals("reject")) {
                runtime.write(sender, () -> runtime.repository().reject(application.id(), actorId(sender),
                        sender.getName(), request.reason()), updated -> {
                    send(sender, "chat.admin.application-rejected");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            } else {
                runtime.write(sender, () -> runtime.repository().requestChanges(application.id(),
                        actorId(sender), sender.getName(), request.reason()), updated -> {
                    send(sender, "chat.admin.application-change-sent");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            }
        });
        return true;
    }

    private boolean town(CommandSender sender, TownRuntime runtime, String[] args) {
        requireMessageLength(args, 3, "chat.admin.usage-town");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("view")) {
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> requireTown(runtime, townName), town -> {
                send(sender, "chat.admin.town-title", Map.of("town", town.profile().name(),
                        "code", town.profile().residenceName()));
                send(sender, "chat.admin.town-status", Map.of("status", town.status(),
                        "mayor", town.mayorId(), "version", town.version()));
                if (town.territory() != null) {
                    send(sender, "chat.admin.town-territory", Map.of(
                            "world", town.territory().center().worldName(),
                            "x", town.territory().center().x(),
                            "z", town.territory().center().z(),
                            "residence", town.residenceName(),
                            "projection", town.projectionStatus()));
                }
            });
            return true;
        }
        if (action.equals("delete")) {
            requireMessageLength(args, 4, "chat.admin.usage-town-delete");
            runtime.read(sender, () -> {
                List<TownSnapshot> candidates = runtime.repository().listTowns(true);
                TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(args, 2,
                        townNames(candidates), plugin.messages()::plainText);
                TownSnapshot target = candidates.stream()
                        .filter(candidate -> sameName(candidate.profile().name(), parsed.townName()))
                        .findFirst().orElseThrow(() -> messageArgument(
                                "chat.admin.town-not-found",
                                Map.of("town", safeText(parsed.townName()))));
                return new TownDeleteRequest(target.id(), target.profile().name(), target.version(),
                        parsed.reason());
            }, request -> requestConfirmation(sender,
                    plugin.messages().text("chat.admin.town-delete-confirmation",
                            Map.of("town", safeText(request.townName()))),
                    () -> deleteTown(sender, runtime, request)));
            return true;
        }
        throw messageArgument("chat.admin.town-action-unsupported");
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
                }, completed -> send(sender, "chat.admin.town-deleted", Map.of(
                        "town", completed.profile().name(), "detail",
                        LandProtectionMessages.detail(plugin.messages(), result))));
            } else {
                send(sender, "chat.admin.town-delete-residence-failed", Map.of(
                        "detail", LandProtectionMessages.detail(plugin.messages(), result)));
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.admin.town-delete-residence-failure", Map.of(
                                "town", safeText(deleted.profile().name()),
                                "residence", safeText(deleted.residenceName()),
                                "detail", safeText(LandProtectionMessages.detail(
                                        plugin.messages(), result)))));
            }
        });
    }

    private boolean member(CommandSender sender, TownRuntime runtime, String[] args) {
        requireMessageLength(args, 5, "chat.admin.usage-member");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("add") && !action.equals("remove") && !action.equals("role")) {
            throw messageArgument("chat.admin.member-action-unsupported");
        }
        runtime.read(sender, () -> memberRequest(runtime, args), request -> {
            UUID playerId = playerId(request.player());
            if (action.equals("role")) {
                MemberRole role;
                try {
                    role = MemberRole.valueOf(request.reason().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    send(sender, "chat.admin.role-invalid");
                    return;
                }
                runtime.write(sender, () -> {
                    TownSnapshot town = requireTown(runtime, request.townId());
                    runtime.governance().changeRoleByAdmin(town.id(), playerId, role,
                            actorId(sender), sender.getName(),
                            plugin.messages().plainText("log.admin.member-role-change-reason"));
                    return town;
                }, town -> {
                    send(sender, "chat.admin.role-updated", Map.of("role", role));
                    reconcileOne(sender, runtime, town.id(), true);
                });
            } else if (action.equals("add")) {
                runtime.write(sender, () -> {
                    TownSnapshot town = requireTown(runtime, request.townId());
                    runtime.repository().addMember(town.id(), playerId, actorId(sender),
                            sender.getName(), request.reason());
                    return town;
                }, town -> {
                    send(sender, "chat.admin.member-added");
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
                    send(sender, "chat.admin.member-removed");
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
        requireMessageLength(args, 3, "chat.admin.usage-vote");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("settle")) {
            UUID voteId = UUID.fromString(args[2]);
            runtime.write(sender, () -> runtime.governance().settleVote(voteId,
                    actorId(sender), sender.getName(), false), vote -> {
                send(sender, "chat.admin.vote-status", Map.of("status", vote.status(),
                        "yes", vote.yesVotes(), "required", vote.requiredYes()));
                if (vote.passed() && vote.type() == VoteType.KICK_MEMBER) {
                    reconcileOne(sender, runtime, vote.townId(), true);
                }
            });
            return true;
        }
        if (action.equals("cancel")) {
            requireMessageLength(args, 4, "chat.admin.usage-vote-cancel");
            UUID voteId = UUID.fromString(args[2]);
            String reason = TownCommandParser.reason(args, 3, plugin.messages()::plainText);
            runtime.write(sender, () -> runtime.governance().cancelVote(voteId,
                    actorId(sender), sender.getName(), reason), vote ->
                    send(sender, "chat.admin.vote-cancelled", Map.of("id", vote.id())));
            return true;
        }
        if (!action.equals("create-kick") && !action.equals("create-mayor")) {
            throw messageArgument("chat.admin.vote-action-unsupported");
        }
        requireMessageLength(args, 4, "chat.admin.usage-vote-create",
                Map.of("action", action));
        GovernanceSettings settings = GovernanceSettings.fixed();
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(false).stream()
                    .filter(town -> town.status() == TownStatus.ACTIVE).toList();
            TownCommandParser.NamedPlayer parsed = TownCommandParser.namedPlayer(args, 2,
                    townNames(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream()
                    .filter(candidate -> sameName(candidate.profile().name(), parsed.townName()))
                    .findFirst().orElseThrow(() -> messageArgument(
                            "chat.admin.vote-town-not-found"));
            return new VoteCreateRequest(town.id(), playerId(parsed.player()),
                    action.equals("create-kick") ? VoteType.KICK_MEMBER : VoteType.REPLACE_MAYOR);
        }, request -> {
            runtime.write(sender, () -> runtime.governance().createVote(request.townId(),
                    request.type(), request.targetId(), actorId(sender),
                    settings.activeMemberWindow(), settings.minimumMembership(),
                    settings.voteDuration(), true), vote -> send(sender, "chat.admin.vote-created",
                    Map.of("id", vote.id(), "voters", vote.eligibleVoters(),
                            "required", vote.requiredYes())));
        });
        return true;
    }

    private MemberRequest memberRequest(TownRuntime runtime, String[] args) {
        List<TownSnapshot> candidates = runtime.repository().listTowns(false).stream()
                .filter(town -> town.status() == TownStatus.ACTIVE).toList();
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(args, 2,
                townNames(candidates), plugin.messages()::plainText);
        TownSnapshot town = candidates.stream()
                .filter(candidate -> sameName(candidate.profile().name(), parsed.townName()))
                .findFirst().orElseThrow(() -> messageArgument(
                        "chat.admin.member-town-not-found",
                        Map.of("town", safeText(parsed.townName()))));
        return new MemberRequest(town.id(), parsed.player(), parsed.reason());
    }

    private boolean mayor(CommandSender sender, TownRuntime runtime, String[] args) {
        requireMessageLength(args, 5, "chat.admin.usage-mayor");
        if (!args[1].equalsIgnoreCase("transfer")) {
            throw messageArgument("chat.admin.usage-mayor");
        }
        runtime.read(sender, () -> memberRequest(runtime, args), request -> {
            UUID newMayor = playerId(request.player());
            runtime.write(sender, () -> {
                TownSnapshot town = requireTown(runtime, request.townId());
                runtime.repository().transferMayor(town.id(), newMayor, actorId(sender),
                        sender.getName(), request.reason());
                return town;
            }, town -> {
                send(sender, "chat.admin.mayor-emergency-transfer");
                reconcileOne(sender, runtime, town.id(), true);
            });
        });
        return true;
    }

    private boolean land(CommandSender sender, TownRuntime runtime, String[] args) {
        requireMessageLength(args, 3, "chat.admin.usage-land");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("preview")) {
            if (!(sender instanceof Player player)) {
                send(sender, "chat.admin.land-player-only");
                return true;
            }
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> requireTown(runtime, townName),
                    town -> plugin.townUi().previewTownForAdmin(player, town));
            return true;
        }
        if (!action.equals("reconcile") && !action.equals("rebuild")) {
            throw messageArgument("chat.admin.land-action-unsupported");
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
                String description = request.all()
                        ? plugin.messages().text("chat.admin.land-rebuild-confirmation-all",
                                Map.of("count", request.towns().size()))
                        : plugin.messages().text("chat.admin.land-rebuild-confirmation-town",
                                Map.of("town", safeText(request.towns().getFirst().townName())));
                requestConfirmation(sender, description,
                        () -> rebuildLand(sender, runtime, request));
            });
        }
        return true;
    }

    private boolean money(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.MONEY);
        requireMessageLength(args, 2, "chat.admin.usage-money-root");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("reconcile")) {
            runtime.reconcileSettlement();
            send(sender, "chat.admin.settlement-reconcile-submitted");
            return true;
        }
        requireMessageLength(args, 3, "chat.admin.usage-money");
        if (action.equals("view")) {
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> {
                TownSnapshot town = requireTown(runtime, townName);
                return runtime.finance().findFinanceByTown(town.id()).orElseThrow();
            }, account -> send(sender, "chat.admin.finance-balance", Map.of(
                    "town", account.townName(), "balance", runtime.money(account.balanceMinor()),
                    "locked", account.locked() ? "[LOCKED]" : "[READY]",
                    "reason", account.locked() ? account.lockReason() : "")));
            return true;
        }
        if (action.equals("adjust")) {
            requireMessageLength(args, 5, "chat.admin.usage-money-adjust");
            runtime.read(sender, () -> {
                List<TownSnapshot> towns = runtime.repository().listTowns(true);
                TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                        args, 2, townNames(towns), plugin.messages()::plainText);
                TownSnapshot town = towns.stream().filter(candidate -> sameName(
                                candidate.profile().name(), parsed.townName())).findFirst()
                        .orElseThrow(() -> messageArgument("chat.admin.town-not-found-generic"));
                long amount = MoneyAmount.from(new BigDecimal(parsed.amount()),
                        runtime.settlement().scale()).minorUnits();
                if (amount == 0) {
                    throw messageArgument("chat.admin.money-adjust-zero");
                }
                return new MoneyAdjustment(town.id(), amount, parsed.reason());
            }, request -> runtime.adjustFunds(sender, request.townId(), request.amountMinor(),
                    request.reason()));
            return true;
        }
        throw messageArgument("chat.admin.money-action-unsupported");
    }

    private boolean tax(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.TAX);
        requireMessageLength(args, 5, "chat.admin.usage-tax");
        if (!args[1].equalsIgnoreCase("set")) {
            throw messageArgument("chat.admin.tax-action-unsupported");
        }
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            TownCommandParser.NamedAmountReason parsed = TownCommandParser.namedAmountReason(
                    args, 2, townNames(towns), plugin.messages()::plainText);
            TownSnapshot town = towns.stream().filter(candidate -> sameName(
                            candidate.profile().name(), parsed.townName())).findFirst()
                    .orElseThrow(() -> messageArgument("chat.admin.town-not-found-generic"));
            BigDecimal percent = new BigDecimal(parsed.amount().replace("%", ""));
            int bps = percent.movePointRight(2).intValueExact();
            return new TaxAdjustment(town.id(), bps, parsed.reason());
        }, request -> runtime.forceTaxRate(sender, request.townId(), request.basisPoints(),
                request.reason()));
        return true;
    }

    private boolean ledger(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.LEDGER);
        requireMessageLength(args, 3, "chat.admin.usage-ledger");
        if (!args[1].equalsIgnoreCase("view")) {
            throw messageArgument("chat.admin.ledger-action-unsupported");
        }
        String townName = TownCommandParser.townName(args, 2);
        runtime.read(sender, () -> {
            TownSnapshot town = requireTown(runtime, townName);
            return runtime.finance().ledger(town.id(), 0, 45);
        }, entries -> {
            send(sender, "chat.admin.ledger-title", Map.of("count", entries.size()));
            for (org.allivlisey.tianjitown.storage.economy.EconomyRepository.LedgerEntry entry : entries) {
                send(sender, "chat.admin.ledger-record", Map.of("created", entry.createdAt(),
                        "type", entry.entryType(), "amount", runtime.money(entry.amountMinor()),
                        "balance", runtime.money(entry.balanceAfterMinor()),
                        "note", entry.note()));
            }
        });
        return true;
    }

    private boolean expand(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.EXPAND);
        requireMessageLength(args, 3, "chat.admin.usage-expand");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("preview") && !(sender instanceof Player)) {
            send(sender, "chat.admin.expand-player-only");
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
                    throw messageArgument("chat.admin.expand-direction-required");
                }
                TownSnapshot town = towns.stream().filter(candidate -> sameName(
                                candidate.profile().name(), parsed.townName())).findFirst().orElseThrow();
                var units = runtime.finance().territoryUnits(town.id());
                var candidate = TerritoryRules.next(units.stream().map(
                                org.allivlisey.tianjitown.storage.economy.EconomyRepository
                                        .TerritoryUnitSnapshot::unit).toList(),
                        ExpansionDirection.parse(parsed.action()));
                return new AdminExpansion(town, units, candidate);
            }
            throw messageArgument("chat.admin.expand-action-unsupported");
        }, view -> {
            send(sender, "chat.admin.expand-title", Map.of("town", view.town().profile().name(),
                    "current", view.units().size(),
                    "maximum", runtime.economySettings().maximumUnits()));
            view.units().forEach(unit -> send(sender, "chat.admin.expand-unit", Map.of(
                    "x", unit.unit().gridX(), "z", unit.unit().gridZ(),
                    "area", unit.residenceAreaName(), "projection", unit.projectionStatus())));
            if (view.preview() != null) {
                Player player = (Player) sender;
                long price = ExpansionPricing.price(runtime.economySettings().expansionCost(),
                        runtime.settlement().scale()).minorUnits();
                runtime.sitePolicy().preview(player, view.preview().territory());
                send(player, "chat.admin.expand-price", Map.of("price", runtime.money(price)));
            }
        });
        return true;
    }

    private boolean buff(CommandSender sender, TownRuntime runtime, String[] args) {
        requirePermission(sender, TownAdminPermissions.BUFF);
        requireMessageLength(args, 2, "chat.admin.usage-buff-root");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            requireMessageLength(args, 3, "chat.admin.usage-buff-list");
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> {
                TownSnapshot town = requireTown(runtime, townName);
                return runtime.buffs().repository().activeBuffsForTown(town.id(),
                        java.time.Instant.now());
            }, buffs -> {
                send(sender, "chat.admin.buff-title", Map.of("count", buffs.size()));
                buffs.forEach(value -> send(sender, "chat.admin.buff-record", Map.of(
                        "id", value.buffId(), "key", value.buffKey(), "level", value.level(),
                        "stacks", value.stackCount(), "expires", value.expiresAt())));
            });
            return true;
        }
        if (action.equals("grant")) {
            if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
                throw messageArgument("chat.admin.buff-purchase-paused");
            }
            requireMessageLength(args, 5, "chat.admin.usage-buff-grant");
            runtime.read(sender, () -> {
                List<TownSnapshot> towns = runtime.repository().listTowns(true);
                TownCommandParser.NamedActionReason parsed = TownCommandParser.namedActionReason(
                        args, 2, townNames(towns),
                        runtime.buffs().settings().buffs().keySet(),
                        plugin.messages()::plainText);
                TownSnapshot town = towns.stream().filter(candidate -> sameName(
                                candidate.profile().name(), parsed.townName())).findFirst()
                        .orElseThrow(() -> messageArgument("chat.admin.town-not-found-generic"));
                BuffDefinition definition = runtime.buffs().settings().requireBuff(
                        parsed.action().toLowerCase(Locale.ROOT));
                return new BuffGrantRequest(town.id(), town.profile().name(), definition,
                        parsed.reason());
            }, request -> requestConfirmation(sender,
                    plugin.messages().text("chat.admin.buff-purchase-confirmation", Map.of(
                            "town", safeText(request.townName()),
                            "buff", safeText(runtime.buffs().settings().label(
                                    request.definition().key())))),
                    () -> runtime.write(sender, () -> runtime.buffs().repository()
                                    .purchaseBuffForTown(request.townId(), actorId(sender),
                                            sender.getName(), request.definition(),
                                            runtime.buffs().settings().label(
                                                    request.definition().key()),
                                            runtime.settlement().scale(),
                                            BuffDurationOption.ONE_HOUR,
                            "admin-buff-purchase:" + UUID.randomUUID(),
                            java.time.Instant.now(), request.reason()),
                            purchase -> {
                                send(sender, "chat.admin.buff-purchase-complete", Map.of(
                                        "balance", runtime.money(purchase.balanceAfterMinor())));
                                runtime.buffs().refreshAllPlayers();
                            })));
            return true;
        }
        throw messageArgument("chat.admin.buff-action-unsupported");
    }

    private void rebuildLand(CommandSender sender, TownRuntime runtime,
                             LandRebuildRequest request) {
        runtime.read(sender, () -> {
            List<TownSnapshot> targets = request.towns().stream().map(reference -> {
                TownSnapshot current = requireTown(runtime, reference.townId());
                requireVersion(current, reference.version());
                if (current.status() == TownStatus.ARCHIVED) {
                    throw messageArgument("chat.admin.town-archived", Map.of(
                            "town", safeText(current.profile().name())));
                }
                return current;
            }).toList();
            return loadTownMembers(runtime, targets);
        }, states -> states.forEach(state -> {
            LandProtectionService.Result removal = runtime.landProtection()
                    .remove(state.town().residenceName(), state.town().territory());
            send(sender, removal.success() ? "chat.admin.land-rebuild-success"
                    : "chat.admin.land-rebuild-failure", Map.of(
                    "town", state.town().profile().name(), "detail",
                    LandProtectionMessages.detail(plugin.messages(), removal)));
            if (removal.success()) {
                runtime.reconcile(sender, state.town(), state.members(), true);
            }
        }));
    }

    private List<TownSnapshot> selectLandTargets(List<TownSnapshot> candidates,
                                                  String targetName) {
        List<TownSnapshot> targets = targetName.equalsIgnoreCase("all")
                ? candidates
                : candidates.stream().filter(town -> sameName(town.profile().name(), targetName))
                .toList();
        if (targets.isEmpty()) {
            throw messageArgument("chat.admin.no-operable-town");
        }
        return targets;
    }

    private static List<TownMembers> loadTownMembers(TownRuntime runtime,
                                                      List<TownSnapshot> towns) {
        return towns.stream().map(town -> new TownMembers(town,
                runtime.repository().listLandAccessIds(town.id()))).toList();
    }

    private void reconcileOne(CommandSender sender, TownRuntime runtime, UUID townId,
                              boolean repair) {
        runtime.read(sender, () -> new TownMembers(requireTown(runtime, townId),
                runtime.repository().listLandAccessIds(townId)),
                state -> runtime.reconcile(sender, state.town(), state.members(), repair));
    }

    private TownSnapshot requireTown(TownRuntime runtime, String townName) {
        return runtime.repository().findTownByName(townName)
                .orElseThrow(() -> messageArgument("chat.admin.town-not-found",
                        Map.of("town", safeText(townName))));
    }

    private TownSnapshot requireTown(TownRuntime runtime, UUID townId) {
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

    private void requirePermission(CommandSender sender, String permission) {
        if (!TownAdminPermissions.has(sender::hasPermission, permission)) {
            throw messageArgument("chat.admin.permission-missing", Map.of(
                    "permission", safeText(permission)));
        }
    }

    private static boolean sameName(String first, String second) {
        return ApplicationText.normalizeNameKey(first)
                .equals(ApplicationText.normalizeNameKey(second));
    }

    private static List<String> townNames(List<TownSnapshot> towns) {
        return towns.stream().map(town -> town.profile().name()).toList();
    }

    private void requireVersion(TownSnapshot town, long expectedVersion) {
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

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("town.maintenance-mode", false);
    }

    private void applicationHelp(CommandSender sender) {
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
                                  List<org.allivlisey.tianjitown.storage.economy.EconomyRepository
                                          .TerritoryUnitSnapshot> units,
                                  org.allivlisey.tianjitown.core.land.TerritoryUnit preview) {
    }

    private record BuffGrantRequest(UUID townId, String townName,
                                    BuffDefinition definition, String reason) {
    }

}

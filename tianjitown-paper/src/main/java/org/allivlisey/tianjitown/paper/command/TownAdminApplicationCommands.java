package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Handles administrator application review and town inspection or deletion. */
public final class TownAdminApplicationCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    public TownAdminApplicationCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    public boolean application(CommandSender sender, TownRuntime runtime, String[] args) {
        if (args.length < 2) {
            facade.applicationHelp(sender);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            runtime.read(sender, () -> runtime.repository().listReviewQueue(100),
                    applications -> plugin.townUi().showAdminApplicationList(sender, applications));
            return true;
        }
        if (!action.equals("approve") && !action.equals("reject") && !action.equals("change")) {
            facade.applicationHelp(sender);
            return true;
        }
        facade.requireMessageLength(args, 4, "chat.admin.usage-application-review",
                Map.of("action", action));
        runtime.read(sender, () -> {
            List<ApplicationSnapshot> candidates = runtime.repository()
                    .listApplicationsForCompletion(500);
            TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(args, 2,
                    candidates.stream().map(candidate -> candidate.text().name()).toList(),
                    plugin.messages()::plainText);
            ApplicationSnapshot application = candidates.stream()
                    .filter(candidate -> TownAdminCommand.sameName(candidate.text().name(), parsed.townName()))
                    .findFirst().orElseThrow(() -> facade.messageArgument(
                            "chat.admin.application-not-found",
                            Map.of("town", TownAdminCommand.safeText(parsed.townName()))));
            return new ApplicationRequest(application, parsed.reason());
        }, request -> {
            ApplicationSnapshot application = request.application();
            if (action.equals("approve")) {
                String key = application.status() == org.allivlisey.tianjitown.core.application.ApplicationStatus.PROVISION_FAILED
                        ? "town:retry:" + application.id() + ":" + application.version()
                        : "town:approve:" + application.id();
                runtime.provision(sender, application.id(), TownAdminCommand.actorId(sender), sender.getName(),
                        request.reason(), key, result -> {
                            if (result.application() != null) {
                                plugin.townUi().notifyApplicationDecision(result.application());
                            }
                        });
            } else if (action.equals("reject")) {
                runtime.write(sender, () -> runtime.repository().reject(application.id(), TownAdminCommand.actorId(sender),
                        sender.getName(), request.reason()), updated -> {
                    facade.send(sender, "chat.admin.application-rejected");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            } else {
                runtime.write(sender, () -> runtime.repository().requestChanges(application.id(),
                        TownAdminCommand.actorId(sender), sender.getName(), request.reason()), updated -> {
                    facade.send(sender, "chat.admin.application-change-sent");
                    plugin.townUi().notifyApplicationDecision(updated);
                });
            }
        });
        return true;
    }

    public boolean town(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requireMessageLength(args, 3, "chat.admin.usage-town");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("view")) {
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> facade.requireTown(runtime, townName), town -> {
                facade.send(sender, "chat.admin.town-title", Map.of("town", town.profile().name(),
                        "code", town.profile().residenceName()));
                facade.send(sender, "chat.admin.town-status", Map.of("status", town.status(),
                        "mayor", town.mayorId(), "version", town.version()));
                if (town.territory() != null) {
                    facade.send(sender, "chat.admin.town-territory", Map.of(
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
            facade.requireMessageLength(args, 4, "chat.admin.usage-town-delete");
            runtime.read(sender, () -> {
                List<TownSnapshot> candidates = runtime.repository().listTowns(true);
                TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(args, 2,
                        TownAdminCommand.townNames(candidates), plugin.messages()::plainText);
                TownSnapshot target = candidates.stream()
                        .filter(candidate -> TownAdminCommand.sameName(candidate.profile().name(), parsed.townName()))
                        .findFirst().orElseThrow(() -> facade.messageArgument(
                                "chat.admin.town-not-found",
                                Map.of("town", TownAdminCommand.safeText(parsed.townName()))));
                return new TownDeleteRequest(target.id(), target.profile().name(), target.version(),
                        parsed.reason());
            }, request -> facade.requestConfirmation(sender,
                    plugin.messages().text("chat.admin.town-delete-confirmation",
                            Map.of("town", TownAdminCommand.safeText(request.townName()))),
                    () -> deleteTown(sender, runtime, request)));
            return true;
        }
        throw facade.messageArgument("chat.admin.town-action-unsupported");
    }

    private void deleteTown(CommandSender sender, TownRuntime runtime,
                            TownDeleteRequest request) {
        runtime.write(sender, () -> {
            TownSnapshot current = facade.requireTown(runtime, request.townId());
            facade.requireVersion(current, request.version());
            runtime.repository().deleteTown(current.id(), TownAdminCommand.actorId(sender), sender.getName(),
                    request.reason());
            return current;
        }, deleted -> {
            LandProtectionService.Result result = runtime.landProtection().remove(
                    deleted.residenceName(), deleted.territory());
            if (result.success()) {
                runtime.write(sender, () -> {
                    runtime.repository().completeTownDeletion(deleted.id(), TownAdminCommand.actorId(sender),
                            sender.getName(), request.reason());
                    return deleted;
                }, completed -> facade.send(sender, "chat.admin.town-deleted", Map.of(
                        "town", completed.profile().name(), "detail",
                        LandProtectionMessages.detail(plugin.messages(), result))));
            } else {
                facade.send(sender, "chat.admin.town-delete-residence-failed", Map.of(
                        "detail", LandProtectionMessages.detail(plugin.messages(), result)));
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.admin.town-delete-residence-failure", Map.of(
                                "town", TownAdminCommand.safeText(deleted.profile().name()),
                                "residence", TownAdminCommand.safeText(deleted.residenceName()),
                                "detail", TownAdminCommand.safeText(LandProtectionMessages.detail(
                                        plugin.messages(), result)))));
            }
        });
    }

    private record ApplicationRequest(ApplicationSnapshot application, String reason) {
    }

    private record TownDeleteRequest(UUID townId, String townName, long version, String reason) {
    }
}

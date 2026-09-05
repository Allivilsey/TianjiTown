package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.land.ExpansionDirection;
import org.allivlisey.tianjitown.core.land.ExpansionPricing;
import org.allivlisey.tianjitown.core.land.TerritoryRules;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Handles administrator land reconciliation, rebuilding and expansion. */
public final class TownAdminLandCommands {
    private final TownAdminCommand facade;
    private final TianjiTownPlugin plugin;

    public TownAdminLandCommands(TownAdminCommand facade, TianjiTownPlugin plugin) {
        this.facade = facade;
        this.plugin = plugin;
    }

    public boolean land(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requireMessageLength(args, 3, "chat.admin.usage-land");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("preview")) {
            if (!(sender instanceof Player player)) {
                facade.send(sender, "chat.admin.land-player-only");
                return true;
            }
            String townName = TownCommandParser.townName(args, 2);
            runtime.read(sender, () -> facade.requireTown(runtime, townName),
                    town -> plugin.townUi().previewTownForAdmin(player, town));
            return true;
        }
        if (!action.equals("reconcile") && !action.equals("rebuild")) {
            throw facade.messageArgument("chat.admin.land-action-unsupported");
        }
        if (action.equals("reconcile")) {
            runtime.read(sender, () -> {
                List<TownSnapshot> candidates = runtime.repository().listTowns(false);
                List<String> names = new ArrayList<>(TownAdminCommand.townNames(candidates));
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
                List<String> names = new ArrayList<>(TownAdminCommand.townNames(candidates));
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
                                Map.of("town", TownAdminCommand.safeText(request.towns().getFirst().townName())));
                facade.requestConfirmation(sender, description,
                        () -> rebuildLand(sender, runtime, request));
            });
        }
        return true;
    }

    public boolean expand(CommandSender sender, TownRuntime runtime, String[] args) {
        facade.requirePermission(sender, TownAdminPermissions.EXPAND);
        facade.requireMessageLength(args, 3, "chat.admin.usage-expand");
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("preview") && !(sender instanceof Player)) {
            facade.send(sender, "chat.admin.expand-player-only");
            return true;
        }
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            if (action.equals("view")) {
                String name = TownCommandParser.exactName(args, 2, TownAdminCommand.townNames(towns));
                TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameName(
                                candidate.profile().name(), name)).findFirst().orElseThrow();
                return new AdminExpansion(town, runtime.finance().territoryUnits(town.id()), null);
            }
            if (action.equals("preview")) {
                TownCommandParser.NamedAction parsed = TownCommandParser.namedAction(args, 2,
                        TownAdminCommand.townNames(towns), List.of("north", "east", "south", "west"));
                if (parsed.action() == null) {
                    throw facade.messageArgument("chat.admin.expand-direction-required");
                }
                TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameName(
                                candidate.profile().name(), parsed.townName())).findFirst().orElseThrow();
                var units = runtime.finance().territoryUnits(town.id());
                var candidate = TerritoryRules.next(units.stream().map(
                                org.allivlisey.tianjitown.storage.economy.EconomyRepository
                                        .TerritoryUnitSnapshot::unit).toList(),
                        ExpansionDirection.parse(parsed.action()));
                return new AdminExpansion(town, units, candidate);
            }
            throw facade.messageArgument("chat.admin.expand-action-unsupported");
        }, view -> {
            facade.send(sender, "chat.admin.expand-title", Map.of("town", view.town().profile().name(),
                    "current", view.units().size(),
                    "maximum", runtime.economySettings().maximumUnits()));
            view.units().forEach(unit -> facade.send(sender, "chat.admin.expand-unit", Map.of(
                    "x", unit.unit().gridX(), "z", unit.unit().gridZ(),
                    "area", unit.residenceAreaName(), "projection", unit.projectionStatus())));
            if (view.preview() != null) {
                Player player = (Player) sender;
                long price = ExpansionPricing.price(runtime.economySettings().expansionCost(),
                        runtime.settlement().scale()).minorUnits();
                runtime.sitePolicy().preview(player, view.preview().territory());
                facade.send(player, "chat.admin.expand-price", Map.of("price", runtime.money(price)));
            }
        });
        return true;
    }

    private void rebuildLand(CommandSender sender, TownRuntime runtime,
                             LandRebuildRequest request) {
        runtime.read(sender, () -> {
            List<TownSnapshot> targets = request.towns().stream().map(reference -> {
                TownSnapshot current = facade.requireTown(runtime, reference.townId());
                facade.requireVersion(current, reference.version());
                if (current.status() == TownStatus.ARCHIVED) {
                    throw facade.messageArgument("chat.admin.town-archived", Map.of(
                            "town", TownAdminCommand.safeText(current.profile().name())));
                }
                return current;
            }).toList();
            return loadTownMembers(runtime, targets);
        }, states -> states.forEach(state -> {
            LandProtectionService.Result removal = runtime.landProtection()
                    .remove(state.town().residenceName(), state.town().territory());
            facade.send(sender, removal.success() ? "chat.admin.land-rebuild-success"
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
                : candidates.stream().filter(town -> TownAdminCommand.sameName(town.profile().name(), targetName))
                .toList();
        if (targets.isEmpty()) {
            throw facade.messageArgument("chat.admin.no-operable-town");
        }
        return targets;
    }

    private static List<TownMembers> loadTownMembers(TownRuntime runtime,
                                                      List<TownSnapshot> towns) {
        return towns.stream().map(town -> new TownMembers(town,
                runtime.repository().listLandAccessIds(town.id()))).toList();
    }

    public void reconcileOne(CommandSender sender, TownRuntime runtime, UUID townId,
                              boolean repair) {
        runtime.read(sender, () -> new TownMembers(facade.requireTown(runtime, townId),
                runtime.repository().listLandAccessIds(townId)),
                state -> runtime.reconcile(sender, state.town(), state.members(), repair));
    }

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }

    private record LandReconcileRequest(List<TownMembers> states, boolean repair) {
    }

    private record LandRebuildRequest(boolean all, List<TownReference> towns) {
    }

    private record TownReference(UUID townId, String townName, long version) {
    }

    private record AdminExpansion(TownSnapshot town,
                                  List<org.allivlisey.tianjitown.storage.economy.EconomyRepository
                                          .TerritoryUnitSnapshot> units,
                                  org.allivlisey.tianjitown.core.land.TerritoryUnit preview) {
    }
}

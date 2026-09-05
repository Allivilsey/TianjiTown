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

import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Usage;

import java.util.ArrayList;
import java.util.List;
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

    @Command("townadmin land preview")
    @Usage("/townadmin land preview <小镇全名>")
    @AdminAccess(value = TownAdminPermissions.ROOT, playerOnly = true)
    public void previewLand(Player player, TownRuntime runtime, String input) {
        String townName = input.strip();
        runtime.read(player, () -> facade.requireTown(runtime, townName),
                town -> plugin.townUi().previewTownForAdmin(player, town));
    }

    @Command("townadmin land reconcile")
    @Usage("/townadmin land reconcile <小镇全名|all> [repair]")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void reconcileLand(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> {
            List<TownSnapshot> candidates = runtime.repository().listTowns(false);
            List<String> names = new ArrayList<>(TownAdminCommand.townNames(candidates));
            names.add("all");
            TownCommandParser.NamedAction parsed = TownCommandParser.namedAction(input.split(" "), 0,
                    names, List.of("repair"));
            List<TownSnapshot> targets = selectLandTargets(candidates, parsed.townName());
            return new LandReconcileRequest(loadTownMembers(runtime, targets),
                    parsed.action() != null);
        }, request -> request.states().forEach(state -> runtime.reconcile(sender,
                state.town(), state.members(), request.repair())));
    }

    @Command("townadmin land rebuild")
    @Usage("/townadmin land rebuild <小镇全名|all>")
    @AdminAccess(TownAdminPermissions.ROOT)
    public void rebuildLandCommand(CommandSender sender, TownRuntime runtime, String input) {
        runtime.read(sender, () -> {
            List<TownSnapshot> candidates = runtime.repository().listTowns(false);
            List<String> names = new ArrayList<>(TownAdminCommand.townNames(candidates));
            names.add("all");
            String targetName = TownCommandParser.exactName(input.split(" "), 0, names);
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

    @Command("townadmin expand view")
    @Usage("/townadmin expand view <小镇全名>")
    @AdminAccess(TownAdminPermissions.EXPAND)
    public void viewExpansion(CommandSender sender, TownRuntime runtime, String input) {
        showExpansion(sender, runtime, input, false);
    }

    @Command("townadmin expand preview")
    @Usage("/townadmin expand preview <小镇全名> <north|east|south|west>")
    @AdminAccess(value = TownAdminPermissions.EXPAND, playerOnly = true)
    public void previewExpansion(Player player, TownRuntime runtime, String input) {
        showExpansion(player, runtime, input, true);
    }

    private void showExpansion(CommandSender sender, TownRuntime runtime, String input, boolean preview) {
        runtime.read(sender, () -> {
            List<TownSnapshot> towns = runtime.repository().listTowns(true);
            if (!preview) {
                String name = TownCommandParser.exactName(input.split(" "), 0, TownAdminCommand.townNames(towns));
                TownSnapshot town = towns.stream().filter(candidate -> TownAdminCommand.sameName(
                                candidate.profile().name(), name)).findFirst().orElseThrow();
                return new AdminExpansion(town, runtime.finance().territoryUnits(town.id()), null);
            }
            if (preview) {
                TownCommandParser.NamedAction parsed = TownCommandParser.namedAction(input.split(" "), 0,
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
                runtime.territoryPreviews().preview(player, view.preview().territory());
                facade.send(player, "chat.admin.expand-price", Map.of("price", runtime.money(price)));
            }
        });
        return;
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

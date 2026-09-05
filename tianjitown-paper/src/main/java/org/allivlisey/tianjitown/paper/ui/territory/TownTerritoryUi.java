package org.allivlisey.tianjitown.paper.ui.territory;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.land.SitePolicy;
import org.allivlisey.tianjitown.paper.land.TerritoryService;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TerritoryCellState;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns expansion selection, territory previews, and town teleport-point routes. */
public final class TownTerritoryUi {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final Map<UUID, Set<TerritoryService.GridSelection>> expansionSelections =
            new ConcurrentHashMap<>();
    private final Map<UUID, UUID> expansionBatchRequestIds = new ConcurrentHashMap<>();

    public TownTerritoryUi(TownUiLegacyFacade facade) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.presentation = facade.presentation();
    }

    public static List<String> townTerritoryLore(PluginMessages messages, InitialTerritory territory) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(territory, "territory");
        return List.of(
                messages.rawText("dialog.tooltip.town.territory-center", Map.of(
                        "x", territory.center().x(), "z", territory.center().z())),
                messages.rawText("dialog.tooltip.town.territory-preview"));
    }

    public void clear() {
        expansionSelections.clear();
        expansionBatchRequestIds.clear();
    }

    public void clear(UUID playerId) {
        expansionSelections.remove(playerId);
        expansionBatchRequestIds.remove(playerId);
    }

    public void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "EXPANSION_MENU" -> openExpansionMenu(player);
                case "TOGGLE_EXPANSION" -> toggleExpansionSelection(player, target);
                case "CLEAR_EXPANSION_SELECTION" -> clearExpansionSelection(player);
                case "CONFIRM_EXPANSION_BATCH" -> confirmExpansionBatch(player);
                case "PREVIEW_EXPANSION" -> previewExpansion(player, target);
                case "EXPAND" -> expand(player, target);
                case "PREVIEW_TOWN" -> previewTown(player, target);
                case "SET_TOWN_TELEPORT" -> setTownTeleportPoint(player, target);
                default -> throw new IllegalArgumentException("unsupported territory action: " + action);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    public void openExpansionMenu(Player player) {
        facade.runtime().loadTerritoryMap(player, map -> {
            Set<TerritoryService.GridSelection> selected = expansionSelections.computeIfAbsent(
                    player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
            Set<TerritoryService.GridSelection> validSelected = new HashSet<>(selected);
            map.cells().stream()
                    .filter(cell -> cell.state() != TerritoryCellState.EXPANDABLE)
                    .map(cell -> new TerritoryService.GridSelection(cell.gridX(), cell.gridZ()))
                    .forEach(validSelected::remove);
            selected.retainAll(validSelected);
            if (selected.isEmpty()) {
                expansionBatchRequestIds.remove(player.getUniqueId());
            }
            long total = map.priceMinor() <= 0 ? 0
                    : Math.multiplyExact(map.priceMinor(), selected.size());
            String price = map.priceMinor() > 0
                    ? facade.runtime().money(map.priceMinor())
                    : presentation.dialogText("territory.limit-reached");
            Component summary = presentation.dialogComponent("territory.summary", Map.of(
                            "current", map.currentUnits(), "maximum", map.maximumUnits()))
                    .append(Component.newline())
                    .append(presentation.dialogComponent("territory.batch-summary", Map.of(
                            "count", selected.size(), "price", facade.runtime().money(total),
                            "units", map.currentUnits() + selected.size(),
                            "maximum", map.maximumUnits())))
                    .append(Component.newline())
                    .append(presentation.dialogComponent("territory.legend"));
            presentation.openDialogPage(player, presentation.dialogText("territory.title"),
                    List.of(DialogBody.plainMessage(summary, 360)), List.of(),
                    DialogBase.DialogAfterAction.NONE, session -> {
                        ActionButton back = presentation.returnButton(player, session,
                                new DialogRoute("FINANCE", "0"));
                        List<TerritoryDialogRenderer.FooterAction> footer = new ArrayList<>();
                        if (!selected.isEmpty()) {
                            Component confirmLabel = presentation.dialogComponent("territory.batch-confirm");
                            footer.add(new TerritoryDialogRenderer.FooterAction(
                                    TerritoryDialogRenderer.FooterKind.CONFIRM,
                                    confirmLabel,
                                    confirmLabel.append(Component.newline()).append(
                                            presentation.dialogComponent(
                                                    "territory.batch-confirm-consequence", Map.of(
                                                            "price", facade.runtime().money(total),
                                                            "count", selected.size()))),
                                    presentation.dialogAction(player, session,
                                            "CONFIRM_EXPANSION_BATCH", null)));
                            footer.add(new TerritoryDialogRenderer.FooterAction(
                                    TerritoryDialogRenderer.FooterKind.CLEAR,
                                    presentation.dialogComponent("territory.batch-clear"),
                                    presentation.dialogComponent("territory.batch-clear"),
                                    presentation.dialogAction(player, session,
                                            "CLEAR_EXPANSION_SELECTION", null)));
                        }
                        return TerritoryDialogRenderer.render(map, price,
                                facade.plugin().messages(), selected,
                                cell -> presentation.dialogAction(player, session, "TOGGLE_EXPANSION",
                                        cell.gridX() + "," + cell.gridZ()), back, footer);
                    }, new DialogRoute("FINANCE", "0"));
        });
    }

    public void toggleExpansionSelection(Player player, String target) {
        GridTarget grid = GridTarget.parse(target, facade.plugin());
        Set<TerritoryService.GridSelection> selected = expansionSelections.computeIfAbsent(
                player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        TerritoryService.GridSelection choice = new TerritoryService.GridSelection(grid.x(), grid.z());
        boolean changed = selected.add(choice);
        if (!changed) {
            selected.remove(choice);
        }
        if (selected.isEmpty()) {
            expansionBatchRequestIds.remove(player.getUniqueId());
        } else if (changed || !expansionBatchRequestIds.containsKey(player.getUniqueId())) {
            expansionBatchRequestIds.put(player.getUniqueId(), UUID.randomUUID());
        }
        openExpansionMenu(player);
    }

    public void clearExpansionSelection(Player player) {
        clear(player.getUniqueId());
        openExpansionMenu(player);
    }

    public void confirmExpansionBatch(Player player) {
        Set<TerritoryService.GridSelection> selected = expansionSelections.get(player.getUniqueId());
        if (selected == null || selected.isEmpty()) {
            openExpansionMenu(player);
            return;
        }
        Set<TerritoryService.GridSelection> snapshot = Set.copyOf(selected);
        UUID requestId = expansionBatchRequestIds.computeIfAbsent(player.getUniqueId(),
                ignored -> UUID.randomUUID());
        facade.closeUi(player);
        facade.runtime().expandBatchAction(player, snapshot, requestId.toString(), ignored -> {
            if (Objects.equals(expansionBatchRequestIds.get(player.getUniqueId()), requestId)) {
                clear(player.getUniqueId());
            }
            openExpansionMenu(player);
        }, exception -> {
            // The persistence layer refunds failed projection; a real retry needs a fresh key.
            expansionBatchRequestIds.remove(player.getUniqueId(), requestId);
            presentation.openNotice(player, presentation.dialogText("territory.unavailable-title"),
                    exception.getMessage(), presentation.dialogText("common.back"),
                    "EXPANSION_MENU", null);
        });
    }

    public void previewExpansion(Player player, String target) {
        GridTarget grid = GridTarget.parse(target, facade.plugin());
        facade.runtime().read(player, () -> facade.runtime().expansionPreview(
                player.getUniqueId(), grid.x(), grid.z()), preview -> {
            SitePolicy.Validation validation = facade.runtime().validateExpansionPreview(preview);
            if (!validation.valid()) {
                presentation.openNotice(player, presentation.dialogText("territory.unavailable-title"),
                        validation.error(), presentation.dialogText("common.back"),
                        "EXPANSION_MENU", null);
                return;
            }
            facade.territoryPreviews().preview(player, preview.candidate().territory());
            String normalizedTarget = grid.x() + "," + grid.z();
            presentation.openConfirmation(player, presentation.dialogText("territory.confirm-title", Map.of(
                            "grid", normalizedTarget)), "EXPAND", normalizedTarget,
                    presentation.dialogText("territory.confirm-consequence", Map.of(
                            "price", facade.runtime().money(preview.priceMinor()))),
                    "EXPANSION_MENU", null);
        });
    }

    public void expand(Player player, String target) {
        GridTarget grid = GridTarget.parse(target, facade.plugin());
        facade.actions().expandTown(player, grid.x(), grid.z(), outcome ->
                facade.handleOutcome(player, outcome, operation ->
                        presentation.openNotice(player,
                                presentation.dialogText("notice.expansion-complete-title"),
                                presentation.dialogText("notice.expansion-complete-message"),
                                presentation.dialogText("common.back"), "EXPANSION_MENU", null)));
    }

    public void previewTown(Player player, String target) {
        previewTown(player, parseTownId(target), true);
    }

    public void previewTownForAdmin(Player player, TownSnapshot town) {
        previewTown(player, town.id(), false);
    }

    private void previewTown(Player player, UUID townId, boolean closeBeforeRead) {
        if (closeBeforeRead) {
            facade.closeUi(player);
        }
        facade.runtime().read(player, () -> {
            TownSnapshot town = facade.runtime().repository().findTown(townId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            facade.plugin().messages().plainText("chat.runtime.town-not-found")));
            List<InitialTerritory> territories = facade.runtime().finance().territoryUnits(townId).stream()
                    .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                    .map(unit -> unit.unit().territory())
                    .toList();
            if (territories.isEmpty()) {
                throw new IllegalArgumentException(facade.plugin().messages().plainText(
                        "chat.site.no-active-territory"));
            }
            return new TownTerritoryPreview(town, territories);
        }, preview -> teleportTownAndPreview(player, preview));
    }

    public void setTownTeleportPoint(Player player, String target) {
        UUID townId = parseTownId(target);
        facade.runtime().read(player, () -> {
            TownSnapshot town = facade.runtime().repository().findTown(townId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            facade.plugin().messages().plainText("chat.runtime.town-not-found")));
            if (!town.mayorId().equals(player.getUniqueId())) {
                throw new IllegalArgumentException(facade.plugin().messages().plainText(
                        "chat.site.teleport-point-mayor-required"));
            }
            return town;
        }, town -> {
            org.bukkit.Location location = player.getLocation();
            if (!facade.runtime().landProtection().contains(town.residenceName(),
                    location.getWorld().getUID(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ())) {
                presentation.openNotice(player, presentation.dialogText("site.teleport-point-outside-title"),
                        presentation.dialogText("site.teleport-point-outside-message"),
                        presentation.dialogText("common.back"), "TOWN", town.id().toString());
                return;
            }
            String unsafeMessageKey = unsafeTeleportReason(location);
            if (unsafeMessageKey != null) {
                presentation.openNotice(player, presentation.dialogText("site.teleport-point-unsafe-title"),
                        presentation.dialogText(unsafeMessageKey), presentation.dialogText("common.back"),
                        "TOWN", town.id().toString());
                return;
            }
            facade.runtime().setTownTeleportPoint(player, town, location, result ->
                    presentation.openNotice(player, result.success()
                                    ? presentation.dialogText("site.teleport-point-set-title")
                                    : presentation.dialogText("site.teleport-point-failed-title"),
                            LandProtectionMessages.text(facade.plugin().messages(), result),
                            presentation.dialogText("common.back"), "TOWN", town.id().toString()));
        });
    }

    private UUID parseTownId(String target) {
        return UUID.fromString(target);
    }

    private static String unsafeTeleportReason(org.bukkit.Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        if (!feet.isPassable() || !head.isPassable()) {
            return "site.teleport-point-space-occupied";
        }
        if (floor.isPassable() || !floor.getType().isSolid()) {
            return "site.teleport-point-floor-unsafe";
        }
        Set<Material> dangerous = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
                Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE,
                Material.SOUL_CAMPFIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
                Material.WITHER_ROSE);
        return dangerous.contains(feet.getType()) || dangerous.contains(head.getType())
                || dangerous.contains(floor.getType())
                ? "site.teleport-point-dangerous-block" : null;
    }

    private void teleportTownAndPreview(Player player, TownTerritoryPreview preview) {
        if (preview.town().status() != TownStatus.ACTIVE) {
            presentation.openNotice(player, presentation.dialogText("site.territory-teleport-unavailable-title"),
                    presentation.dialogText("site.territory-teleport-unavailable-message"),
                    presentation.dialogText("common.back"), "MAIN", null);
            return;
        }
        facade.closeUi(player);
        if (!player.performCommand("res tp " + preview.town().residenceName())) {
            presentation.openNotice(player, presentation.dialogText("site.territory-teleport-failed-title"),
                    presentation.dialogText("site.territory-teleport-failed-message"),
                    presentation.dialogText("common.back"), "TOWN", preview.town().id().toString());
            return;
        }
        waitForTownTeleport(player, preview, 0);
    }

    private void waitForTownTeleport(Player player, TownTerritoryPreview preview, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        org.bukkit.Location location = player.getLocation();
        if (facade.runtime().landProtection().contains(preview.town().residenceName(),
                location.getWorld().getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ())) {
            facade.territoryPreviews().previewSilently(player, preview.territories());
            return;
        }
        if (attempt >= 120) {
            presentation.openNotice(player, presentation.dialogText("site.territory-teleport-timeout-title"),
                    presentation.dialogText("site.territory-teleport-timeout-message"),
                    presentation.dialogText("common.back"), "TOWN", preview.town().id().toString());
            return;
        }
        facade.plugin().runMainLater(() -> waitForTownTeleport(player, preview, attempt + 1), 10L);
    }

    private record GridTarget(int x, int z) {
        static GridTarget parse(String target, TianjiTownPlugin plugin) {
            String[] coordinates = target == null ? new String[0] : target.split(",", -1);
            if (coordinates.length != 2) {
                throw new IllegalArgumentException(plugin.messages().plainText(
                        "chat.site.invalid-grid-target"));
            }
            return new GridTarget(Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]));
        }
    }

    private record TownTerritoryPreview(TownSnapshot town, List<InitialTerritory> territories) {
        private TownTerritoryPreview {
            territories = List.copyOf(territories);
        }
    }
}

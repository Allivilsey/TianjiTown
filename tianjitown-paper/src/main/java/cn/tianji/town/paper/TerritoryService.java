package cn.tianji.town.paper;

import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.land.ExpansionPricing;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.land.TerritoryCellState;
import cn.tianji.town.core.land.TerritoryRules;
import cn.tianji.town.core.land.TerritoryUnit;
import cn.tianji.town.storage.economy.EconomyRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

final class TerritoryService {
    private static final String BATCH_SELECTION_REQUIRED =
            "validation.territory.batch-selection-required";
    private static final String BATCH_CAPACITY_EXCEEDED =
            "validation.territory.batch-capacity-exceeded";
    private static final String BATCH_GRID_OUT_OF_BOUNDS =
            "validation.territory.batch-grid-out-of-bounds";
    private static final String BATCH_DUPLICATE_SELECTION =
            "validation.territory.batch-duplicate-selection";
    private static final String BATCH_OCCUPIED_SELECTION =
            "validation.territory.batch-occupied-selection";
    private static final String BATCH_NOT_CONNECTED =
            "validation.territory.batch-not-connected";
    private static final String TOWN_REQUIRED = "validation.territory.town-required";
    private static final String MAYOR_REQUIRED = "validation.territory.mayor-required";
    private static final String ORIGIN_MISSING = "validation.territory.origin-missing";
    private static final String CAPACITY_REACHED = "validation.territory.capacity-reached";
    private static final String CELL_NOT_ADJACENT =
            "dialog.territory.cell.not-adjacent-detail";
    private static final String CELL_UNAVAILABLE =
            "dialog.territory.cell.unavailable-detail";
    private static final String MAP_SIZE_INVALID = "territory.map-size-invalid";

    private final EconomyRepository finance;
    private final SitePolicy sitePolicy;
    private final PluginMessages messages;
    private final EconomySettings settings;
    private final int moneyScale;

    TerritoryService(EconomyRepository finance, SitePolicy sitePolicy,
                     PluginMessages messages, EconomySettings settings, int moneyScale) {
        this.finance = Objects.requireNonNull(finance, "finance");
        this.sitePolicy = Objects.requireNonNull(sitePolicy, "sitePolicy");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.moneyScale = moneyScale;
    }

    ExpansionPreview preview(UUID playerId, ExpansionDirection direction) {
        Context context = context(playerId);
        requireCapacity(context);
        TerritoryUnit candidate = TerritoryRules.next(context.units(), direction);
        return preview(context, candidate);
    }

    ExpansionPreview preview(UUID playerId, int gridX, int gridZ) {
        Context context = context(playerId);
        requireCapacity(context);
        TerritoryUnit candidate = TerritoryRules.target(context.units(), gridX, gridZ);
        return preview(context, candidate);
    }

    ExpansionBatchPreview batchPreview(UUID playerId, Set<GridSelection> selections) {
        Context context = context(playerId);
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException(messages.plainText(BATCH_SELECTION_REQUIRED));
        }
        if (context.units().size() + selections.size() > settings.maximumUnits()
                || context.units().size() + selections.size() > TerritoryRules.MAXIMUM_UNITS) {
            throw new IllegalArgumentException(messages.plainText(BATCH_CAPACITY_EXCEEDED));
        }
        Set<Grid> requested = new HashSet<>();
        for (GridSelection selection : selections) {
            if (selection == null || Math.abs((long) selection.gridX()) > TerritoryRules.GRID_RADIUS
                    || Math.abs((long) selection.gridZ()) > TerritoryRules.GRID_RADIUS) {
                throw new IllegalArgumentException(messages.plainText(
                        BATCH_GRID_OUT_OF_BOUNDS));
            }
            if (!requested.add(new Grid(selection.gridX(), selection.gridZ()))) {
                throw new IllegalArgumentException(messages.plainText(
                        BATCH_DUPLICATE_SELECTION));
            }
        }
        Set<Grid> occupied = new HashSet<>();
        context.units().forEach(unit -> occupied.add(new Grid(unit.gridX(), unit.gridZ())));
        if (requested.stream().anyMatch(occupied::contains)) {
            throw new IllegalArgumentException(messages.plainText(BATCH_OCCUPIED_SELECTION));
        }
        List<GridSelection> remaining = new ArrayList<>(selections);
        List<TerritoryUnit> working = new ArrayList<>(context.units());
        List<ExpansionPreview> candidates = new ArrayList<>();
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (int index = 0; index < remaining.size(); index++) {
                GridSelection selection = remaining.get(index);
                try {
                    TerritoryUnit candidate = TerritoryRules.target(working,
                            selection.gridX(), selection.gridZ());
                    ExpansionPreview preview = preview(context, candidate,
                            context.units().size() + candidates.size() + 1);
                    candidates.add(preview);
                    working.add(candidate);
                    remaining.remove(index);
                    progressed = true;
                    break;
                } catch (IllegalArgumentException ignored) {
                    // 允许先选外围单元；下一轮会在其相邻单元已加入后重试。
                }
            }
            if (!progressed) {
                throw new IllegalArgumentException(messages.plainText(BATCH_NOT_CONNECTED));
            }
        }
        long totalPrice = Math.multiplyExact(price(), candidates.size());
        return new ExpansionBatchPreview(context.account(), candidates, totalPrice,
                context.units().size() + candidates.size());
    }

    TerritoryMap map(UUID playerId) {
        Context context = context(playerId);
        TerritoryUnit origin = context.origin().unit();
        InitialTerritory completeGrid = territoryAt(origin,
                TerritoryRules.GRID_RADIUS, TerritoryRules.GRID_RADIUS);
        InitialTerritory oppositeCorner = territoryAt(origin,
                -TerritoryRules.GRID_RADIUS, -TerritoryRules.GRID_RADIUS);
        List<EconomyRepository.OccupiedTerritoryChunk> foreignChunks =
                finance.occupiedChunksOutsideTown(context.account().townId(),
                        origin.territory().center().worldId(), oppositeCorner.minimumChunkX(),
                        completeGrid.maximumChunkX(), oppositeCorner.minimumChunkZ(),
                        completeGrid.maximumChunkZ());
        Map<Chunk, String> foreignOwners = new HashMap<>();
        foreignChunks.forEach(chunk -> foreignOwners.putIfAbsent(
                new Chunk(chunk.chunkX(), chunk.chunkZ()), chunk.townName()));

        Map<Grid, TerritoryUnit> owned = new HashMap<>();
        context.units().forEach(unit -> owned.put(new Grid(unit.gridX(), unit.gridZ()), unit));
        List<TerritoryCell> cells = new ArrayList<>(TerritoryRules.MAXIMUM_UNITS);
        for (int gridZ = -TerritoryRules.GRID_RADIUS;
             gridZ <= TerritoryRules.GRID_RADIUS; gridZ++) {
            for (int gridX = -TerritoryRules.GRID_RADIUS;
                 gridX <= TerritoryRules.GRID_RADIUS; gridX++) {
                Grid grid = new Grid(gridX, gridZ);
                TerritoryUnit occupied = owned.get(grid);
                if (occupied != null) {
                    TerritoryCellState state = gridX == 0 && gridZ == 0
                            ? TerritoryCellState.CENTER : TerritoryCellState.OWNED;
                    cells.add(new TerritoryCell(gridX, gridZ, state, occupied.territory(),
                            null, state == TerritoryCellState.CENTER
                            ? messages.plainText("dialog.territory.cell.center-detail")
                            : messages.plainText("dialog.territory.cell.owned-detail")));
                    continue;
                }

                InitialTerritory territory = territoryAt(origin, gridX, gridZ);
                String foreignTown = foreignTown(territory, foreignOwners);
                if (foreignTown != null) {
                    cells.add(new TerritoryCell(gridX, gridZ,
                            TerritoryCellState.OTHER_TOWN, territory, null,
                            messages.plainText("dialog.territory.cell.other-town-detail",
                                    Map.of("town", foreignTown))));
                    continue;
                }
                if (context.units().size() >= settings.maximumUnits()) {
                    cells.add(new TerritoryCell(gridX, gridZ, TerritoryCellState.BLOCKED,
                            territory, null,
                            messages.plainText("dialog.territory.cell.capacity-detail")));
                    continue;
                }
                try {
                    TerritoryUnit candidate = TerritoryRules.target(
                            context.units(), gridX, gridZ);
                    ExpansionPreview preview = preview(context, candidate);
                    cells.add(new TerritoryCell(gridX, gridZ,
                            TerritoryCellState.EXPANDABLE, territory, preview,
                            messages.plainText("dialog.territory.cell.expandable-detail")));
                } catch (IllegalArgumentException ignored) {
                    cells.add(new TerritoryCell(gridX, gridZ, TerritoryCellState.BLOCKED,
                            territory, null, cellDetail(context.units(), gridX, gridZ)));
                }
            }
        }
        long nextPrice = context.units().size() < settings.maximumUnits() ? price() : 0;
        return new TerritoryMap(cells, context.units().size(), settings.maximumUnits(),
                nextPrice);
    }

    TerritoryMap validate(TerritoryMap map) {
        List<TerritoryCell> validated = map.cells().stream().map(cell -> {
            if (cell.state() != TerritoryCellState.EXPANDABLE || cell.preview() == null) {
                return cell;
            }
            SitePolicy.Validation validation = sitePolicy.validateExpansion(
                    cell.territory(), cell.preview().residenceName());
            return validation.valid() ? cell : new TerritoryCell(cell.gridX(), cell.gridZ(),
                    TerritoryCellState.BLOCKED, cell.territory(), null, validation.error());
        }).toList();
        return new TerritoryMap(validated, map.currentUnits(), map.maximumUnits(),
                map.priceMinor());
    }

    SitePolicy.Validation validate(ExpansionPreview preview) {
        return sitePolicy.validateExpansion(preview.candidate().territory(),
                preview.residenceName());
    }

    private Context context(UUID playerId) {
        EconomyRepository.TownFinance account = finance.findFinanceByPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException(messages.plainText(TOWN_REQUIRED)));
        if (!account.role().equals("MAYOR")) {
            throw new IllegalArgumentException(messages.plainText(MAYOR_REQUIRED));
        }
        List<EconomyRepository.TerritoryUnitSnapshot> snapshots =
                finance.territoryUnits(account.townId()).stream()
                        .filter(unit -> !unit.projectionStatus().equals("FAILED"))
                        .toList();
        EconomyRepository.TerritoryUnitSnapshot origin = snapshots.stream()
                .filter(unit -> unit.unit().gridX() == 0 && unit.unit().gridZ() == 0)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        messages.plainText(ORIGIN_MISSING)));
        List<TerritoryUnit> units = snapshots.stream()
                .map(EconomyRepository.TerritoryUnitSnapshot::unit).toList();
        return new Context(account, units, origin);
    }

    private ExpansionPreview preview(Context context, TerritoryUnit candidate) {
        return preview(context, candidate, context.units().size() + 1);
    }

    private ExpansionPreview preview(Context context, TerritoryUnit candidate, int totalUnits) {
        String areaName = "unit_" + coordinate(candidate.gridX()) + "_"
                + coordinate(candidate.gridZ());
        return new ExpansionPreview(context.account(), candidate,
                context.origin().residenceName(), areaName, price(),
                totalUnits);
    }

    private long price() {
        return ExpansionPricing.price(settings.expansionCost(), moneyScale).minorUnits();
    }

    private String cellDetail(List<TerritoryUnit> units, int gridX, int gridZ) {
        for (ExpansionDirection direction : ExpansionDirection.values()) {
            if (units.stream().anyMatch(unit -> unit.gridX() + direction.gridX() == gridX
                    && unit.gridZ() + direction.gridZ() == gridZ)) {
                return messages.plainText(CELL_UNAVAILABLE);
            }
        }
        return messages.plainText(CELL_NOT_ADJACENT);
    }

    private void requireCapacity(Context context) {
        if (context.units().size() >= settings.maximumUnits()) {
            throw new IllegalArgumentException(messages.plainText(CAPACITY_REACHED));
        }
    }

    private static InitialTerritory territoryAt(TerritoryUnit origin, int gridX, int gridZ) {
        ChunkPosition originCenter = origin.territory().center();
        return new InitialTerritory(new ChunkPosition(originCenter.worldId(),
                originCenter.worldName(),
                Math.addExact(originCenter.x(), Math.multiplyExact(gridX,
                        InitialTerritory.CHUNKS_PER_SIDE)),
                Math.addExact(originCenter.z(), Math.multiplyExact(gridZ,
                        InitialTerritory.CHUNKS_PER_SIDE))));
    }

    private static String foreignTown(InitialTerritory territory,
                                      Map<Chunk, String> foreignOwners) {
        for (ChunkPosition chunk : territory.chunks()) {
            String townName = foreignOwners.get(new Chunk(chunk.x(), chunk.z()));
            if (townName != null) {
                return townName;
            }
        }
        return null;
    }

    private static String coordinate(int value) {
        return value < 0 ? "m" + Math.abs(value) : "p" + value;
    }

    record ExpansionPreview(EconomyRepository.TownFinance account, TerritoryUnit candidate,
                            String residenceName, String areaName, long priceMinor,
                            int totalUnits) {
    }

    record GridSelection(int gridX, int gridZ) {
    }

    record ExpansionBatchPreview(EconomyRepository.TownFinance account,
                                 List<ExpansionPreview> candidates, long totalPriceMinor,
                                 int totalUnits) {
        ExpansionBatchPreview {
            candidates = List.copyOf(candidates);
        }
    }

    record TerritoryCell(int gridX, int gridZ, TerritoryCellState state,
                         InitialTerritory territory, ExpansionPreview preview, String detail) {
    }

    record TerritoryMap(List<TerritoryCell> cells, int currentUnits, int maximumUnits,
                        long priceMinor) {
        TerritoryMap {
            cells = List.copyOf(cells);
            if (cells.size() != TerritoryRules.MAXIMUM_UNITS) {
                throw new IllegalArgumentException(MAP_SIZE_INVALID);
            }
        }
    }

    private record Context(EconomyRepository.TownFinance account, List<TerritoryUnit> units,
                           EconomyRepository.TerritoryUnitSnapshot origin) {
    }

    private record Grid(int x, int z) {
    }

    private record Chunk(int x, int z) {
    }
}

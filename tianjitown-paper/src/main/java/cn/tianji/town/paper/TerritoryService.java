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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class TerritoryService {
    private final EconomyRepository finance;
    private final SitePolicy sitePolicy;
    private final EconomySettings settings;
    private final int moneyScale;

    TerritoryService(EconomyRepository finance, SitePolicy sitePolicy,
                     EconomySettings settings, int moneyScale) {
        this.finance = Objects.requireNonNull(finance, "finance");
        this.sitePolicy = Objects.requireNonNull(sitePolicy, "sitePolicy");
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
                            ? "小镇初始中心" : "已属于当前小镇"));
                    continue;
                }

                InitialTerritory territory = territoryAt(origin, gridX, gridZ);
                String foreignTown = foreignTown(territory, foreignOwners);
                if (foreignTown != null) {
                    cells.add(new TerritoryCell(gridX, gridZ,
                            TerritoryCellState.OTHER_TOWN, territory, null,
                            "属于其他小镇: " + foreignTown));
                    continue;
                }
                if (context.units().size() >= settings.maximumUnits()) {
                    cells.add(new TerritoryCell(gridX, gridZ, TerritoryCellState.BLOCKED,
                            territory, null, "领地单元已达到配置上限"));
                    continue;
                }
                try {
                    TerritoryUnit candidate = TerritoryRules.target(
                            context.units(), gridX, gridZ);
                    ExpansionPreview preview = preview(context, candidate);
                    cells.add(new TerritoryCell(gridX, gridZ,
                            TerritoryCellState.EXPANDABLE, territory, preview,
                            "可使用公共资金扩张"));
                } catch (IllegalArgumentException exception) {
                    cells.add(new TerritoryCell(gridX, gridZ, TerritoryCellState.BLOCKED,
                            territory, null, exception.getMessage()));
                }
            }
        }
        long nextPrice = context.units().size() < settings.maximumUnits()
                ? price(context.units().size()) : 0;
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
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇"));
        if (!account.role().equals("MAYOR")) {
            throw new IllegalArgumentException("只有镇长可以使用公共资金扩张");
        }
        List<EconomyRepository.TerritoryUnitSnapshot> snapshots =
                finance.territoryUnits(account.townId()).stream()
                        .filter(unit -> !unit.projectionStatus().equals("FAILED"))
                        .toList();
        EconomyRepository.TerritoryUnitSnapshot origin = snapshots.stream()
                .filter(unit -> unit.unit().gridX() == 0 && unit.unit().gridZ() == 0)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("初始领地单元缺失"));
        List<TerritoryUnit> units = snapshots.stream()
                .map(EconomyRepository.TerritoryUnitSnapshot::unit).toList();
        return new Context(account, units, origin);
    }

    private ExpansionPreview preview(Context context, TerritoryUnit candidate) {
        String areaName = "unit_" + coordinate(candidate.gridX()) + "_"
                + coordinate(candidate.gridZ());
        return new ExpansionPreview(context.account(), candidate,
                context.origin().residenceName(), areaName, price(context.units().size()),
                context.units().size() + 1);
    }

    private long price(int currentUnits) {
        return ExpansionPricing.price(settings.expansionBaseCost(),
                settings.expansionPerUnitIncrease(), currentUnits, moneyScale).minorUnits();
    }

    private void requireCapacity(Context context) {
        if (context.units().size() >= settings.maximumUnits()) {
            throw new IllegalArgumentException("领地单元已达到配置上限");
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

    record TerritoryCell(int gridX, int gridZ, TerritoryCellState state,
                         InitialTerritory territory, ExpansionPreview preview, String detail) {
    }

    record TerritoryMap(List<TerritoryCell> cells, int currentUnits, int maximumUnits,
                        long priceMinor) {
        TerritoryMap {
            cells = List.copyOf(cells);
            if (cells.size() != TerritoryRules.MAXIMUM_UNITS) {
                throw new IllegalArgumentException("领地地图必须包含 25 个格子");
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

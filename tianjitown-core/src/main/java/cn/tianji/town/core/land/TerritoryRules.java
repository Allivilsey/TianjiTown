package cn.tianji.town.core.land;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;

public final class TerritoryRules {
    public static final int GRID_RADIUS = 2;
    public static final int MAXIMUM_UNITS = 25;

    private TerritoryRules() {
    }

    public static TerritoryUnit next(List<TerritoryUnit> units, ExpansionDirection direction) {
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("小镇至少需要一个初始领地单元");
        }
        Objects.requireNonNull(direction, "direction");
        requireConnected(units);
        TerritoryUnit origin = units.stream()
                .filter(unit -> unit.gridX() == 0 && unit.gridZ() == 0)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("缺少初始领地单元"));
        Set<Grid> occupied = new HashSet<>();
        units.forEach(unit -> occupied.add(new Grid(unit.gridX(), unit.gridZ())));
        Grid candidate = occupied.stream()
                .map(current -> new Grid(current.x() + direction.gridX(),
                        current.z() + direction.gridZ()))
                .filter(grid -> Math.abs(grid.x()) <= GRID_RADIUS
                        && Math.abs(grid.z()) <= GRID_RADIUS)
                .filter(grid -> !occupied.contains(grid))
                .min(Comparator.comparingInt((Grid grid) -> Math.abs(grid.x()) + Math.abs(grid.z()))
                        .thenComparingInt(Grid::z).thenComparingInt(Grid::x))
                .orElseThrow(() -> new IllegalArgumentException("该方向在 5×5 网格内已无可扩张单元"));
        return target(units, candidate.x(), candidate.z());
    }

    public static TerritoryUnit target(List<TerritoryUnit> units, int gridX, int gridZ) {
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("小镇至少需要一个初始领地单元");
        }
        requireConnected(units);
        if (Math.abs((long) gridX) > GRID_RADIUS || Math.abs((long) gridZ) > GRID_RADIUS) {
            throw new IllegalArgumentException("目标超出 5×5 扩张网格");
        }
        TerritoryUnit origin = units.stream()
                .filter(unit -> unit.gridX() == 0 && unit.gridZ() == 0)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("缺少初始领地单元"));
        Set<Grid> occupied = new HashSet<>();
        units.forEach(unit -> occupied.add(new Grid(unit.gridX(), unit.gridZ())));
        Grid target = new Grid(gridX, gridZ);
        if (occupied.contains(target)) {
            throw new IllegalArgumentException("目标领地单元已经被占领");
        }
        boolean adjacent = java.util.Arrays.stream(ExpansionDirection.values())
                .map(direction -> new Grid(gridX + direction.gridX(),
                        gridZ + direction.gridZ()))
                .anyMatch(occupied::contains);
        if (!adjacent) {
            throw new IllegalArgumentException("目标必须与已有领地四方向相邻");
        }
        int centerX = Math.addExact(origin.territory().center().x(),
                Math.multiplyExact(gridX, 3));
        int centerZ = Math.addExact(origin.territory().center().z(),
                Math.multiplyExact(gridZ, 3));
        ChunkPosition center = new ChunkPosition(origin.territory().center().worldId(),
                origin.territory().center().worldName(), centerX, centerZ);
        return new TerritoryUnit(gridX, gridZ, new InitialTerritory(center));
    }

    public static void requireConnected(List<TerritoryUnit> units) {
        Set<Grid> all = new HashSet<>();
        for (TerritoryUnit unit : units) {
            if (!all.add(new Grid(unit.gridX(), unit.gridZ()))) {
                throw new IllegalArgumentException("领地网格坐标重复");
            }
        }
        Set<Grid> visited = new HashSet<>();
        java.util.ArrayDeque<Grid> queue = new java.util.ArrayDeque<>();
        Grid origin = new Grid(0, 0);
        if (!all.contains(origin)) {
            throw new IllegalArgumentException("缺少初始领地单元");
        }
        queue.add(origin);
        visited.add(origin);
        while (!queue.isEmpty()) {
            Grid current = queue.removeFirst();
            for (ExpansionDirection direction : ExpansionDirection.values()) {
                Grid neighbor = new Grid(current.x() + direction.gridX(),
                        current.z() + direction.gridZ());
                if (all.contains(neighbor) && visited.add(neighbor)) {
                    queue.addLast(neighbor);
                }
            }
        }
        if (visited.size() != all.size()) {
            throw new IllegalArgumentException("领地单元必须连续，不能存在飞地");
        }
    }

    private record Grid(int x, int z) {
    }
}

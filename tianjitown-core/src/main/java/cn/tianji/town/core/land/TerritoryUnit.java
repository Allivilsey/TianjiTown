package cn.tianji.town.core.land;

import java.util.Objects;

public record TerritoryUnit(int gridX, int gridZ, InitialTerritory territory) {
    public TerritoryUnit {
        Objects.requireNonNull(territory, "territory");
    }

    public boolean adjacentTo(TerritoryUnit other) {
        Objects.requireNonNull(other, "other");
        long distance = Math.abs((long) gridX - other.gridX)
                + Math.abs((long) gridZ - other.gridZ);
        return distance == 1;
    }
}

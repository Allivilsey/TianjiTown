package org.allivlisey.tianjitown.core.land;

import java.util.ArrayList;
import java.util.List;

/** All 25 units belong to the town; territory_units records only activated units. */
public record TownReservation(InitialTerritory initial) {
    public static final int RADIUS = InitialTerritory.RADIUS
            + TerritoryRules.GRID_RADIUS * InitialTerritory.CHUNKS_PER_SIDE;

    public int minimumChunkX() { return Math.subtractExact(initial.center().x(), RADIUS); }
    public int maximumChunkX() { return Math.addExact(initial.center().x(), RADIUS); }
    public int minimumChunkZ() { return Math.subtractExact(initial.center().z(), RADIUS); }
    public int maximumChunkZ() { return Math.addExact(initial.center().z(), RADIUS); }

    public List<InitialTerritory> units() {
        List<InitialTerritory> result = new ArrayList<>(TerritoryRules.MAXIMUM_UNITS);
        for (int x = -TerritoryRules.GRID_RADIUS; x <= TerritoryRules.GRID_RADIUS; x++) {
            for (int z = -TerritoryRules.GRID_RADIUS; z <= TerritoryRules.GRID_RADIUS; z++) {
                result.add(new InitialTerritory(new ChunkPosition(initial.center().worldId(),
                        initial.center().worldName(), Math.addExact(initial.center().x(),
                        x * InitialTerritory.CHUNKS_PER_SIDE), Math.addExact(initial.center().z(),
                        z * InitialTerritory.CHUNKS_PER_SIDE))));
            }
        }
        return List.copyOf(result);
    }

    public boolean overlaps(java.util.UUID worldId, int minChunkX, int maxChunkX,
                            int minChunkZ, int maxChunkZ) {
        return initial.center().worldId().equals(worldId)
                && minimumChunkX() <= maxChunkX && maximumChunkX() >= minChunkX
                && minimumChunkZ() <= maxChunkZ && maximumChunkZ() >= minChunkZ;
    }
}

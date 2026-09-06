package org.allivlisey.tianjitown.storage.station;

import java.util.Objects;
import java.util.UUID;

public record StationRecord(String id, UUID worldId, String worldName, int x, int y, int z,
                            UUID townId, String townName) {
    public StationRecord {
        if (id == null || id.isBlank() || worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("服务台 ID 和世界名不能为空");
        }
        Objects.requireNonNull(worldId, "worldId");
    }

    public boolean sameLocation(UUID world, int blockX, int blockY, int blockZ) {
        return worldId.equals(world) && x == blockX && y == blockY && z == blockZ;
    }
}

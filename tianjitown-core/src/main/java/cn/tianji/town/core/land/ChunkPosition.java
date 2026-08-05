package cn.tianji.town.core.land;

import java.util.UUID;

public record ChunkPosition(UUID worldId, String worldName, int x, int z) {
    public ChunkPosition {
        if (worldId == null) {
            throw new IllegalArgumentException("worldId 不能为空");
        }
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName 不能为空");
        }
    }
}

package cn.tianji.town.core.land;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record InitialTerritory(ChunkPosition center) {
    public static final int RADIUS = 1;

    public InitialTerritory {
        Objects.requireNonNull(center, "center");
    }

    public int minimumChunkX() {
        return Math.subtractExact(center.x(), RADIUS);
    }

    public int maximumChunkX() {
        return Math.addExact(center.x(), RADIUS);
    }

    public int minimumChunkZ() {
        return Math.subtractExact(center.z(), RADIUS);
    }

    public int maximumChunkZ() {
        return Math.addExact(center.z(), RADIUS);
    }

    public List<ChunkPosition> chunks() {
        List<ChunkPosition> result = new ArrayList<>(9);
        for (int x = minimumChunkX(); x <= maximumChunkX(); x++) {
            for (int z = minimumChunkZ(); z <= maximumChunkZ(); z++) {
                result.add(new ChunkPosition(center.worldId(), center.worldName(), x, z));
            }
        }
        return List.copyOf(result);
    }

    public boolean overlaps(InitialTerritory other) {
        return center.worldId().equals(other.center.worldId())
                && minimumChunkX() <= other.maximumChunkX()
                && maximumChunkX() >= other.minimumChunkX()
                && minimumChunkZ() <= other.maximumChunkZ()
                && maximumChunkZ() >= other.minimumChunkZ();
    }

    public boolean respectsBuffer(InitialTerritory other, int bufferChunks) {
        if (bufferChunks < 0) {
            throw new IllegalArgumentException("bufferChunks 不能小于 0");
        }
        if (!center.worldId().equals(other.center.worldId())) {
            return true;
        }
        long separatedX = (long) maximumChunkX() + bufferChunks < other.minimumChunkX()
                || (long) other.maximumChunkX() + bufferChunks < minimumChunkX() ? 1 : 0;
        long separatedZ = (long) maximumChunkZ() + bufferChunks < other.minimumChunkZ()
                || (long) other.maximumChunkZ() + bufferChunks < minimumChunkZ() ? 1 : 0;
        return separatedX == 1 || separatedZ == 1;
    }
}

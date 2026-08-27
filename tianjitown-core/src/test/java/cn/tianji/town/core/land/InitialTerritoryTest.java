package cn.tianji.town.core.land;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialTerritoryTest {
    private final UUID worldId = UUID.randomUUID();

    @Test
    void initialTerritoryContainsExactlyNineChunks() {
        InitialTerritory territory = territory(10, -4);
        assertEquals(9, territory.chunks().size());
        assertEquals(9, territory.chunks().stream().distinct().count());
    }

    @Test
    void blockBoundsCoverExactlyThreeByThreeChunks() {
        InitialTerritory territory = territory(-1, 4);

        assertEquals(-32, territory.minimumBlockX());
        assertEquals(15, territory.maximumBlockX());
        assertEquals(16, territory.maximumBlockXExclusive());
        assertEquals(48, territory.maximumBlockXExclusive() - territory.minimumBlockX());
        assertEquals(48, territory.maximumBlockZExclusive() - territory.minimumBlockZ());
    }

    @Test
    void detectsOverlapAndConfiguredBuffer() {
        InitialTerritory first = territory(0, 0);
        assertTrue(first.overlaps(territory(2, 0)));
        assertFalse(first.overlaps(territory(3, 0)));
        assertFalse(first.respectsBuffer(territory(3, 0), 1));
        assertTrue(first.respectsBuffer(territory(4, 0), 1));
    }

    private InitialTerritory territory(int x, int z) {
        return new InitialTerritory(new ChunkPosition(worldId, "world", x, z));
    }
}

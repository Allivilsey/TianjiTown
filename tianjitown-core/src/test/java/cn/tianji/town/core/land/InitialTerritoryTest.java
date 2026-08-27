package cn.tianji.town.core.land;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialTerritoryTest {
    private final UUID worldId = UUID.randomUUID();

    @Test
    void initialTerritoryContainsExactlyTwentyFiveChunks() {
        InitialTerritory territory = territory(10, -4);
        assertEquals(25, territory.chunks().size());
        assertEquals(25, territory.chunks().stream().distinct().count());
    }

    @Test
    void blockBoundsCoverExactlyFiveByFiveChunks() {
        InitialTerritory territory = territory(-1, 4);

        assertEquals(-48, territory.minimumBlockX());
        assertEquals(31, territory.maximumBlockX());
        assertEquals(32, territory.maximumBlockXExclusive());
        assertEquals(80, territory.maximumBlockXExclusive() - territory.minimumBlockX());
        assertEquals(80, territory.maximumBlockZExclusive() - territory.minimumBlockZ());
    }

    @Test
    void detectsOverlapAndConfiguredBuffer() {
        InitialTerritory first = territory(0, 0);
        assertTrue(first.overlaps(territory(4, 0)));
        assertFalse(first.overlaps(territory(5, 0)));
        assertFalse(first.respectsBuffer(territory(5, 0), 1));
        assertTrue(first.respectsBuffer(territory(6, 0), 1));
    }

    private InitialTerritory territory(int x, int z) {
        return new InitialTerritory(new ChunkPosition(worldId, "world", x, z));
    }
}

package org.allivlisey.tianjitown.core.land;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TownReservationTest {
    @Test
    void coversExactlyTwentyFiveUnitsIncludingNegativeCoordinates() {
        UUID world = UUID.randomUUID();
        TownReservation reservation = new TownReservation(new InitialTerritory(
                new ChunkPosition(world, "world", -10, -20)));
        assertEquals(25, reservation.units().size());
        assertEquals(625, reservation.units().stream().flatMap(unit -> unit.chunks().stream()).distinct().count());
        assertEquals(-22, reservation.minimumChunkX());
        assertEquals(-8, reservation.maximumChunkZ());
        assertTrue(reservation.overlaps(world, -22, -22, -32, -32));
        assertFalse(reservation.overlaps(world, -23, -23, -32, -32));
        assertFalse(reservation.overlaps(UUID.randomUUID(), -22, 2, -32, -8));
    }
}

package org.allivlisey.tianjitown.core.land;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpansionRulesTest {
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void keepsDirectionValuesAsStableBusinessValuesWithoutPresentationText() {
        assertEquals(0, ExpansionDirection.NORTH.gridX());
        assertEquals(-1, ExpansionDirection.NORTH.gridZ());
        assertEquals(1, ExpansionDirection.EAST.gridX());
        assertEquals(0, ExpansionDirection.EAST.gridZ());
        assertEquals(0, ExpansionDirection.SOUTH.gridX());
        assertEquals(1, ExpansionDirection.SOUTH.gridZ());
        assertEquals(-1, ExpansionDirection.WEST.gridX());
        assertEquals(0, ExpansionDirection.WEST.gridZ());

        assertEquals(ExpansionDirection.NORTH, ExpansionDirection.parse("north"));
        assertEquals(ExpansionDirection.EAST, ExpansionDirection.parse("东"));
        assertEquals(ExpansionDirection.SOUTH, ExpansionDirection.parse("S"));
        assertEquals(ExpansionDirection.WEST, ExpansionDirection.parse("west"));
    }

    @Test
    void expandsInFixedFiveByFiveGridAndStaysConnected() {
        TerritoryUnit origin = unit(0, 0, 10, 20);
        TerritoryUnit east = TerritoryRules.next(List.of(origin), ExpansionDirection.EAST);
        TerritoryUnit north = TerritoryRules.next(List.of(origin, east), ExpansionDirection.NORTH);
        TerritoryUnit northEast = TerritoryRules.next(List.of(origin, east, north),
                ExpansionDirection.EAST);

        assertEquals(1, east.gridX());
        assertEquals(15, east.territory().center().x());
        assertEquals(-1, north.gridZ());
        assertEquals(1, northEast.gridX());
        assertEquals(-1, northEast.gridZ());
        TerritoryUnit farEast = TerritoryRules.next(List.of(origin, east),
                ExpansionDirection.EAST);
        assertEquals(2, farEast.gridX());
        assertThrows(IllegalArgumentException.class, () -> TerritoryRules.next(
                List.of(origin, east, farEast), ExpansionDirection.EAST));
    }

    @Test
    void appliesFixedPriceWithEconomyPrecision() {
        MoneyAmount price = ExpansionPricing.price(new BigDecimal("3000.001"), 2);

        assertEquals(new BigDecimal("3000.01"), price.decimal());
    }

    @Test
    void targetsAnyAvailableFourWayAdjacentCell() {
        TerritoryUnit origin = unit(0, 0, 10, 20);
        TerritoryUnit east = TerritoryRules.target(List.of(origin), 1, 0);
        TerritoryUnit southEast = TerritoryRules.target(List.of(origin, east), 1, 1);

        assertEquals(15, east.territory().center().x());
        assertEquals(25, southEast.territory().center().z());
        assertThrows(IllegalArgumentException.class,
                () -> TerritoryRules.target(List.of(origin, east), 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> TerritoryRules.target(List.of(origin, east), 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TerritoryRules.target(List.of(origin, east), 3, 0));
    }

    private static TerritoryUnit unit(int gridX, int gridZ, int centerX, int centerZ) {
        return new TerritoryUnit(gridX, gridZ, new InitialTerritory(
                new ChunkPosition(WORLD, "world", centerX, centerZ)));
    }
}

package org.allivlisey.tianjitown.core.land;

import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    void appliesCompoundingPriceWithEconomyPrecision() {
        MoneyAmount price = ExpansionPricing.price(new BigDecimal("5000.001"), 0, 2);

        assertEquals(new BigDecimal("5000.01"), price.decimal());
        BigDecimal base = new BigDecimal("5000");
        assertEquals(new BigDecimal("5000.00"), ExpansionPricing.price(base, 0, 2).decimal());
        assertEquals(new BigDecimal("5250.00"), ExpansionPricing.price(base, 1, 2).decimal());
        assertEquals(new BigDecimal("5512.50"), ExpansionPricing.price(base, 2, 2).decimal());
        assertEquals(new BigDecimal("5788.13"), ExpansionPricing.price(base, 3, 2).decimal());
        assertEquals(1_576_250, ExpansionPricing.batchPriceMinor(base, 0, 3, 2));
        assertEquals(1_655_063, ExpansionPricing.batchPriceMinor(base, 1, 3, 2));
        assertEquals(0, ExpansionPricing.batchPriceMinor(base, 24, 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> ExpansionPricing.batchPriceMinor(base, 23, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ExpansionPricing.price(base, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> ExpansionPricing.price(base, 25, 2));
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

    @ParameterizedTest
    @CsvSource({"1,1", "-1,-1", "2,0", "0,-2", "-3,0", "0,3",
            "-2147483648,0", "0,-2147483648", "2147483647,0", "0,2147483647"})
    void rejectsDiagonalDisconnectedAndExtremeTargets(int x, int z) {
        var units = new java.util.ArrayList<>(List.of(unit(0, 0, 10, 20)));
        var before = List.copyOf(units);
        assertThrows(IllegalArgumentException.class, () -> TerritoryRules.target(units, x, z));
        assertEquals(before, units);
    }

    @Test
    void refusesExpansionFromDuplicateDisconnectedOrMissingOriginState() {
        var origin = unit(0, 0, 10, 20);
        for (var units : List.of(List.<TerritoryUnit>of(), List.of(origin, origin),
                List.of(unit(1, 0, 15, 20)), List.of(origin, unit(2, 0, 20, 20)))) {
            assertThrows(IllegalArgumentException.class, () -> TerritoryRules.target(units, 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> TerritoryRules.next(units, ExpansionDirection.SOUTH));
        }
    }

    @Test
    void acceptsTheTwentyFifthUnitAndRejectsFurtherExpansion() {
        var units = new java.util.ArrayList<TerritoryUnit>();
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                if (x != 2 || z != 2) units.add(unit(x, z, 10 + 5 * x, 20 + 5 * z));
            }
        }
        var last = TerritoryRules.target(units, 2, 2);
        assertEquals(unit(2, 2, 20, 30), last);
        units.add(last);
        assertEquals(25, units.size());
        for (var direction : ExpansionDirection.values()) {
            assertThrows(IllegalArgumentException.class, () -> TerritoryRules.next(units, direction));
        }
        assertThrows(IllegalArgumentException.class, () -> TerritoryRules.target(units, 2, 2));
    }

    @Test
    void rejectsBatchTotalOverflowEvenWhenEachItemPriceFits() {
        var base = new BigDecimal("50000000000000000");
        assertEquals(5_000_000_000_000_000_000L, ExpansionPricing.price(base, 0, 2).minorUnits());
        assertEquals(5_250_000_000_000_000_000L, ExpansionPricing.price(base, 1, 2).minorUnits());
        assertThrows(ArithmeticException.class, () -> ExpansionPricing.batchPriceMinor(base, 0, 2, 2));
    }
}

package cn.tianji.town.core.land;

import cn.tianji.town.core.economy.MoneyAmount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpansionRulesTest {
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void expandsInFixedFiveByFiveGridAndStaysConnected() {
        TerritoryUnit origin = unit(0, 0, 10, 20);
        TerritoryUnit east = TerritoryRules.next(List.of(origin), ExpansionDirection.EAST);
        TerritoryUnit north = TerritoryRules.next(List.of(origin, east), ExpansionDirection.NORTH);
        TerritoryUnit northEast = TerritoryRules.next(List.of(origin, east, north),
                ExpansionDirection.EAST);

        assertEquals(1, east.gridX());
        assertEquals(13, east.territory().center().x());
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
    void appliesLinearPriceWithEconomyPrecision() {
        MoneyAmount price = ExpansionPricing.price(new BigDecimal("100.00"),
                new BigDecimal("1.5"), 3, 2);

        assertEquals(new BigDecimal("103.00"), price.decimal());
    }

    private static TerritoryUnit unit(int gridX, int gridZ, int centerX, int centerZ) {
        return new TerritoryUnit(gridX, gridZ, new InitialTerritory(
                new ChunkPosition(WORLD, "world", centerX, centerZ)));
    }
}

package cn.tianji.town.paper;

import cn.tianji.town.core.land.TerritoryCellState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryDialogRendererTest {
    @Test
    void keepsTheTwentyFiveMapCellsCompactAcrossSelectionStates() {
        TerritoryService.TerritoryMap map = map();
        TerritoryDialogRenderer.Layout noSelection = TerritoryDialogRenderer.layout(map, Set.of(),
                List.of());
        TerritoryDialogRenderer.Layout oneSelection = TerritoryDialogRenderer.layout(map,
                Set.of(new TerritoryService.GridSelection(1, 0)), List.of(
                        TerritoryDialogRenderer.FooterKind.CONFIRM,
                        TerritoryDialogRenderer.FooterKind.CLEAR));
        TerritoryDialogRenderer.Layout manySelections = TerritoryDialogRenderer.layout(map,
                Set.of(new TerritoryService.GridSelection(-1, 0),
                        new TerritoryService.GridSelection(1, 0),
                        new TerritoryService.GridSelection(0, 1)), List.of(
                        TerritoryDialogRenderer.FooterKind.CONFIRM,
                        TerritoryDialogRenderer.FooterKind.CLEAR));

        assertMapLayout(noSelection, map);
        assertMapLayout(oneSelection, map);
        assertMapLayout(manySelections, map);
        assertFalse(noSelection.mapButtons().stream().anyMatch(
                TerritoryDialogRenderer.MapButton::selected));
        assertEquals(1, oneSelection.mapButtons().stream().filter(
                TerritoryDialogRenderer.MapButton::selected).count());
        assertEquals(3, manySelections.mapButtons().stream().filter(
                TerritoryDialogRenderer.MapButton::selected).count());
        assertEquals(2, oneSelection.footerButtons().size());
        assertTrue(oneSelection.footerButtons().stream().allMatch(button ->
                button.width() > oneSelection.cellSize()));
    }

    private static void assertMapLayout(TerritoryDialogRenderer.Layout layout,
                                        TerritoryService.TerritoryMap map) {
        assertEquals(5, layout.columns());
        assertEquals(20, layout.cellSize());
        assertEquals(25, layout.mapButtons().size());
        assertEquals(map.cells(), layout.mapButtons().stream()
                .map(TerritoryDialogRenderer.MapButton::cell).toList());
        assertTrue(layout.mapButtons().stream().allMatch(button ->
                button.width() == layout.cellSize()));
    }

    private static TerritoryService.TerritoryMap map() {
        List<TerritoryService.TerritoryCell> cells = new ArrayList<>();
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                TerritoryCellState state = x == 0 && z == 0
                        ? TerritoryCellState.CENTER : TerritoryCellState.EXPANDABLE;
                cells.add(new TerritoryService.TerritoryCell(x, z, state, null, null, null));
            }
        }
        return new TerritoryService.TerritoryMap(cells, 1, 25, 3_000L);
    }
}

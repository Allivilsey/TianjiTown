package cn.tianji.town.paper;

import cn.tianji.town.core.land.TerritoryCellState;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class TerritoryDialogRenderer {
    private static final int COLUMNS = 5;
    private static final int CELL_WIDTH = 44;
    private static final Key ITEM_ATLAS = Key.key("minecraft:items");
    private static final Key CENTER_SPRITE = Key.key("minecraft:item/nether_star");
    private static final Key OWNED_SPRITE = Key.key("minecraft:item/lime_dye");
    private static final Key EXPANDABLE_SPRITE = Key.key("minecraft:item/emerald");
    private static final Key BLOCKED_SPRITE = Key.key("minecraft:item/gray_dye");
    private static final Key OTHER_TOWN_SPRITE = Key.key("minecraft:item/red_dye");

    private TerritoryDialogRenderer() {
    }

    static DialogType render(TerritoryService.TerritoryMap map, String formattedPrice,
                             Function<TerritoryService.TerritoryCell, DialogAction> actionFactory,
                             ActionButton exitAction) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(formattedPrice, "formattedPrice");
        Objects.requireNonNull(actionFactory, "actionFactory");
        List<ActionButton> buttons = map.cells().stream()
                .map(cell -> button(cell, formattedPrice, actionFactory))
                .toList();
        return DialogType.multiAction(buttons)
                .exitAction(exitAction)
                .columns(COLUMNS)
                .build();
    }

    private static ActionButton button(
            TerritoryService.TerritoryCell cell, String formattedPrice,
            Function<TerritoryService.TerritoryCell, DialogAction> actionFactory) {
        TerritoryCellState state = cell.state();
        DialogAction action = state == TerritoryCellState.EXPANDABLE
                ? actionFactory.apply(cell) : null;
        return ActionButton.create(sprite(state), tooltip(cell, formattedPrice),
                CELL_WIDTH, action);
    }

    private static Component sprite(TerritoryCellState state) {
        Key sprite = switch (state) {
            case CENTER -> CENTER_SPRITE;
            case OWNED -> OWNED_SPRITE;
            case EXPANDABLE -> EXPANDABLE_SPRITE;
            case BLOCKED -> BLOCKED_SPRITE;
            case OTHER_TOWN -> OTHER_TOWN_SPRITE;
        };
        return Component.object(ObjectContents.sprite(ITEM_ATLAS, sprite));
    }

    private static Component tooltip(TerritoryService.TerritoryCell cell,
                                     String formattedPrice) {
        Component tooltip = Component.text(name(cell.state()), color(cell.state()))
                .append(Component.newline())
                .append(Component.text("网格: " + cell.gridX() + "," + cell.gridZ(),
                        NamedTextColor.GRAY));
        if (cell.state() == TerritoryCellState.EXPANDABLE && cell.preview() != null) {
            tooltip = tooltip.append(Component.newline())
                    .append(Component.text("价格: " + formattedPrice, NamedTextColor.GOLD))
                    .append(Component.newline())
                    .append(Component.text("扩张后单元: " + cell.preview().totalUnits(),
                            NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text("点击预览边界并进入确认页",
                            NamedTextColor.GREEN));
        } else if (cell.detail() != null && !cell.detail().isBlank()) {
            tooltip = tooltip.append(Component.newline())
                    .append(Component.text(cell.detail(), NamedTextColor.GRAY));
        }
        return tooltip;
    }

    private static String name(TerritoryCellState state) {
        return switch (state) {
            case CENTER -> "小镇中心";
            case OWNED -> "已占领区域";
            case EXPANDABLE -> "可扩张区域";
            case BLOCKED -> "不可扩张区域";
            case OTHER_TOWN -> "其他小镇区域";
        };
    }

    private static NamedTextColor color(TerritoryCellState state) {
        return switch (state) {
            case CENTER -> NamedTextColor.GOLD;
            case OWNED -> NamedTextColor.GREEN;
            case EXPANDABLE -> NamedTextColor.AQUA;
            case BLOCKED -> NamedTextColor.DARK_GRAY;
            case OTHER_TOWN -> NamedTextColor.RED;
        };
    }
}

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
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class TerritoryDialogRenderer {
    private static final int COLUMNS = 5;
    private static final int CELL_SIZE = 20;
    private static final Key BLOCK_ATLAS = Key.key("minecraft:blocks");
    private static final Key CENTER_SPRITE = Key.key("minecraft:block/yellow_stained_glass");
    private static final Key OWNED_SPRITE = Key.key("minecraft:block/green_stained_glass");
    private static final Key EXPANDABLE_SPRITE = Key.key("minecraft:block/light_gray_stained_glass");
    private static final Key BLOCKED_SPRITE = Key.key("minecraft:block/red_stained_glass");

    private TerritoryDialogRenderer() {
    }

    static DialogType render(TerritoryService.TerritoryMap map, String formattedPrice,
                             PluginMessages messages,
                             Function<TerritoryService.TerritoryCell, DialogAction> actionFactory,
                             ActionButton exitAction) {
        // 领地格子的状态名称、坐标和操作提示都从 messages.yml 读取，保持地图界面可配置。
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(formattedPrice, "formattedPrice");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(actionFactory, "actionFactory");
        List<ActionButton> buttons = map.cells().stream()
                .map(cell -> button(cell, formattedPrice, messages, actionFactory))
                .toList();
        return DialogType.multiAction(buttons)
                .exitAction(exitAction)
                .columns(COLUMNS)
                .build();
    }

    private static ActionButton button(
            TerritoryService.TerritoryCell cell, String formattedPrice,
            PluginMessages messages,
            Function<TerritoryService.TerritoryCell, DialogAction> actionFactory) {
        TerritoryCellState state = cell.state();
        DialogAction action = state == TerritoryCellState.EXPANDABLE
                ? actionFactory.apply(cell) : null;
        return ActionButton.create(sprite(state), tooltip(cell, formattedPrice, messages),
                CELL_SIZE, action);
    }

    private static Component sprite(TerritoryCellState state) {
        Key sprite = switch (state) {
            case CENTER -> CENTER_SPRITE;
            case OWNED -> OWNED_SPRITE;
            case EXPANDABLE -> EXPANDABLE_SPRITE;
            case BLOCKED, OTHER_TOWN -> BLOCKED_SPRITE;
        };
        return Component.object(ObjectContents.sprite(BLOCK_ATLAS, sprite));
    }

    private static Component tooltip(TerritoryService.TerritoryCell cell,
                                     String formattedPrice, PluginMessages messages) {
        Component tooltip = Component.text(name(cell.state(), messages), color(cell.state()))
                .append(Component.newline())
                .append(Component.text(messages.plainText("dialog.territory.cell.grid", Map.of(
                        "x", cell.gridX(), "z", cell.gridZ())), NamedTextColor.GRAY));
        if (cell.state() == TerritoryCellState.EXPANDABLE && cell.preview() != null) {
            tooltip = tooltip.append(Component.newline())
                    .append(Component.text(messages.plainText("dialog.territory.cell.price",
                            Map.of("price", formattedPrice)), NamedTextColor.GOLD))
                    .append(Component.newline())
                    .append(Component.text(messages.plainText(
                            "dialog.territory.cell.units-after-expansion",
                            Map.of("units", cell.preview().totalUnits())), NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text(messages.plainText(
                            "dialog.territory.cell.expand-hint"), NamedTextColor.GREEN));
        } else if (cell.detail() != null && !cell.detail().isBlank()) {
            tooltip = tooltip.append(Component.newline())
                    .append(Component.text(cell.detail(), NamedTextColor.GRAY));
        }
        return tooltip;
    }

    private static String name(TerritoryCellState state, PluginMessages messages) {
        return switch (state) {
            case CENTER -> messages.plainText("dialog.territory.cell.center");
            case OWNED -> messages.plainText("dialog.territory.cell.owned");
            case EXPANDABLE -> messages.plainText("dialog.territory.cell.expandable");
            case BLOCKED -> messages.plainText("dialog.territory.cell.blocked");
            case OTHER_TOWN -> messages.plainText("dialog.territory.cell.other-town");
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

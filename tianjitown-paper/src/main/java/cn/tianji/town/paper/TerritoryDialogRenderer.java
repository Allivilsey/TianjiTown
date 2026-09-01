package cn.tianji.town.paper;

import cn.tianji.town.core.land.TerritoryCellState;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

final class TerritoryDialogRenderer {
    private static final int COLUMNS = 5;
    private static final int CELL_SIZE = 20;
    private static final Key BLOCK_ATLAS = Key.key("minecraft:blocks");
    private static final Key CENTER_SPRITE = Key.key("minecraft:block/yellow_stained_glass");
    private static final Key OWNED_SPRITE = Key.key("minecraft:block/green_stained_glass");
    private static final Key EXPANDABLE_SPRITE = Key.key("minecraft:block/light_gray_stained_glass");
    private static final Key BLOCKED_SPRITE = Key.key("minecraft:block/red_stained_glass");
    private static final int FOOTER_WIDTH = 170;

    private TerritoryDialogRenderer() {
    }

    static DialogType render(TerritoryService.TerritoryMap map, String formattedPrice,
                             PluginMessages messages,
                             Function<TerritoryService.TerritoryCell, DialogAction> actionFactory,
                             ActionButton exitAction) {
        return render(map, formattedPrice, messages, Set.of(), actionFactory, exitAction);
    }

    static DialogType render(TerritoryService.TerritoryMap map, String formattedPrice,
                             PluginMessages messages,
                             Set<TerritoryService.GridSelection> selected,
                             Function<TerritoryService.TerritoryCell, DialogAction> actionFactory,
                             ActionButton exitAction) {
        return render(map, formattedPrice, messages, selected, actionFactory, exitAction,
                List.of());
    }

    static DialogType render(TerritoryService.TerritoryMap map, String formattedPrice,
                             PluginMessages messages,
                             Set<TerritoryService.GridSelection> selected,
                             Function<TerritoryService.TerritoryCell, DialogAction> actionFactory,
                             ActionButton exitAction, List<FooterAction> footerActions) {
        // 领地格子的状态名称、颜色、坐标和操作提示都从 messages.yml 读取，保持地图界面可配置。
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(formattedPrice, "formattedPrice");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(actionFactory, "actionFactory");
        Objects.requireNonNull(footerActions, "footerActions");
        Layout layout = layout(map, selected,
                footerActions.stream().map(FooterAction::kind).toList());
        List<ActionButton> buttons = new ArrayList<>(layout.mapButtons().stream()
                .map(cell -> button(cell.cell(), formattedPrice, messages, cell.selected(),
                        cell.width(), actionFactory))
                .toList());
        for (int index = 0; index < footerActions.size(); index++) {
            buttons.add(footerButton(footerActions.get(index),
                    layout.footerButtons().get(index).width()));
        }
        return DialogType.multiAction(buttons)
                .exitAction(exitAction)
                .columns(COLUMNS)
                .build();
    }

    private static ActionButton button(
            TerritoryService.TerritoryCell cell, String formattedPrice,
            PluginMessages messages, boolean isSelected, int width,
            Function<TerritoryService.TerritoryCell, DialogAction> actionFactory) {
        TerritoryCellState state = cell.state();
        DialogAction action = state == TerritoryCellState.EXPANDABLE
                ? actionFactory.apply(cell) : null;
        return ActionButton.create(sprite(state, isSelected),
                tooltip(cell, formattedPrice, messages, isSelected),
                width, action);
    }

    private static ActionButton footerButton(FooterAction action, int width) {
        return ActionButton.create(action.label(),
                action.tooltip(), width, action.action());
    }

    static Layout layout(TerritoryService.TerritoryMap map,
                         Set<TerritoryService.GridSelection> selected,
                         List<FooterKind> footerKinds) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(footerKinds, "footerKinds");
        List<MapButton> mapButtons = map.cells().stream()
                .map(cell -> new MapButton(cell,
                        cell.state() == TerritoryCellState.EXPANDABLE && selected.contains(
                                new TerritoryService.GridSelection(cell.gridX(), cell.gridZ())),
                        CELL_SIZE))
                .toList();
        return new Layout(COLUMNS, CELL_SIZE, mapButtons, footerKinds.stream()
                .map(kind -> new FooterButton(kind, FOOTER_WIDTH))
                .toList());
    }

    private static Component sprite(TerritoryCellState state, boolean selected) {
        if (selected) {
            return Component.object(ObjectContents.sprite(BLOCK_ATLAS,
                    Key.key("minecraft:block/lime_stained_glass")));
        }
        Key sprite = switch (state) {
            case CENTER -> CENTER_SPRITE;
            case OWNED -> OWNED_SPRITE;
            case EXPANDABLE -> EXPANDABLE_SPRITE;
            case BLOCKED, OTHER_TOWN -> BLOCKED_SPRITE;
        };
        return Component.object(ObjectContents.sprite(BLOCK_ATLAS, sprite));
    }

    private static Component tooltip(TerritoryService.TerritoryCell cell,
                                     String formattedPrice, PluginMessages messages,
                                     boolean selected) {
        Component tooltip = messages.component(nameKey(cell.state()))
                .append(Component.newline())
                .append(messages.component("dialog.territory.cell.grid", Map.of(
                        "x", cell.gridX(), "z", cell.gridZ())));
        if (cell.state() == TerritoryCellState.EXPANDABLE && cell.preview() != null) {
            if (selected) {
                tooltip = tooltip.append(Component.newline())
                        .append(messages.component("dialog.territory.cell.selected"));
            }
            tooltip = tooltip.append(Component.newline())
                    .append(messages.component("dialog.territory.cell.price",
                            Map.of("price", formattedPrice)))
                    .append(Component.newline())
                    .append(messages.component(
                            "dialog.territory.cell.units-after-expansion",
                            Map.of("units", cell.preview().totalUnits())))
                    .append(Component.newline())
                    .append(messages.component("dialog.territory.cell.expand-hint"));
        } else if (cell.detail() != null && !cell.detail().isBlank()) {
            tooltip = tooltip.append(Component.newline())
                    .append(messages.component("dialog.territory.cell.detail",
                            Map.of("detail", cell.detail())));
        }
        return tooltip;
    }

    private static String nameKey(TerritoryCellState state) {
        return switch (state) {
            case CENTER -> "dialog.territory.cell.center";
            case OWNED -> "dialog.territory.cell.owned";
            case EXPANDABLE -> "dialog.territory.cell.expandable";
            case BLOCKED -> "dialog.territory.cell.blocked";
            case OTHER_TOWN -> "dialog.territory.cell.other-town";
        };
    }

    enum FooterKind {
        CONFIRM,
        CLEAR
    }

    record FooterAction(FooterKind kind, Component label, Component tooltip, DialogAction action) {
        FooterAction {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(tooltip, "tooltip");
            Objects.requireNonNull(action, "action");
        }
    }

    record MapButton(TerritoryService.TerritoryCell cell, boolean selected, int width) {
        MapButton {
            Objects.requireNonNull(cell, "cell");
        }
    }

    record FooterButton(FooterKind kind, int width) {
        FooterButton {
            Objects.requireNonNull(kind, "kind");
        }
    }

    record Layout(int columns, int cellSize, List<MapButton> mapButtons,
                  List<FooterButton> footerButtons) {
        Layout {
            mapButtons = List.copyOf(mapButtons);
            footerButtons = List.copyOf(footerButtons);
            if (columns != COLUMNS || cellSize != CELL_SIZE
                    || mapButtons.size() != COLUMNS * COLUMNS) {
                throw new IllegalArgumentException("领地地图布局必须保持 5×5 的 20px 格子");
            }
        }
    }
}

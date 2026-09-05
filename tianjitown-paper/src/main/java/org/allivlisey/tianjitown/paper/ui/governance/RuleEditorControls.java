package org.allivlisey.tianjitown.paper.ui.governance;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.home.RuleEditorDialogRenderer;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;


/** Shared rule input and deletion controls bound to dialog sessions. */
public final class RuleEditorControls {
    private final TownUiPresentation presentation;
    public RuleEditorControls(TownUiPresentation presentation) {
        this.presentation = presentation;
    }

    public void openRuleEditorAddDialog(Player player, String title, Component content,
                                         DialogRoute parent,
                                         int addWidth,
                                         int columns,
                                         Function<UUID, List<ActionButton>> afterAddActions,
                                         Function<UUID, List<ActionButton>> ruleActions,
                                         Consumer<DialogResponseView> addRule,
                                         Function<UUID, List<ActionButton>> trailingActions,
                                         Function<UUID, List<ActionButton>> postActions) {
        DialogInput input = DialogInput.text("rule_text", 400,
                presentation.dialogComponent("rules.input-label"), false, "", 300, null);
        presentation.openDialogPage(player, title, List.of(DialogBody.plainMessage(
                        content, 420)), List.of(input),
                DialogBase.DialogAfterAction.NONE, session -> {
                    List<ActionButton> actions = new ArrayList<>();
                    actions.add(ActionButton.create(presentation.dialogComponent("rules.add"),
                            presentation.dialogComponent("rules.add-tooltip"), addWidth,
                            presentation.dialogAction(player, session, addRule)));
                    actions.addAll(afterAddActions.apply(session));
                    actions.addAll(ruleActions.apply(session));
                    actions.addAll(trailingActions.apply(session));
                    actions.addAll(postActions.apply(session));
                    return DialogType.multiAction(actions)
                            .exitAction(presentation.returnButton(player, session, parent))
                            .columns(columns).build();
                }, parent);
    }

    public List<ActionButton> inlineRuleDeletionActions(Player player, UUID session,
                                                          RuleEditorDialogRenderer.Layout layout,
                                                          Consumer<RuleEditorDialogRenderer.DeleteTarget> deleteRule) {
        List<ActionButton> actions = new ArrayList<>();
        for (RuleEditorDialogRenderer.Row row : layout.rows()) {
            actions.add(ActionButton.create(presentation.dialogComponent("rules.item", Map.of(
                            "index", row.displayIndex(), "rule", TownUiPresentation.safeText(row.rule()))),
                    presentation.dialogComponent("rules.delete-tooltip", Map.of("index", row.displayIndex())),
                    RuleEditorDialogRenderer.INLINE_RULE_WIDTH,
                    presentation.dialogAction(player, session,
                            response -> deleteRule.accept(row.deleteTarget()))));
        }
        return actions;
    }

}

package org.allivlisey.tianjitown.paper.ui.governance;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.home.ReadOnlyRulesDialogRenderer;
import org.allivlisey.tianjitown.paper.ui.home.RuleEditorDialogRenderer;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
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
import java.util.function.Function;


/** Rule acknowledgement and versioned town rule editing. */
public final class TownRulesDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    private final RuleEditorControls controls;
    public TownRulesDialogs(TownUiLegacyFacade facade, RuleEditorControls controls) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
        this.controls = controls;
    }

    public void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        Component rules = presentation.dialogComponent("common.town", Map.of(
                        "town", TownUiLegacyFacade.safeText(governance.townName())))
                .append(Component.newline())
                .append(presentation.dialogComponent("rules.revision", Map.of(
                        "revision", governance.townRulesRevision())));
        for (int index = 0; index < governance.rules().size(); index++) {
            rules = rules.append(Component.newline()).append(Component.newline())
                    .append(presentation.dialogComponent("rules.item", Map.of(
                            "index", index + 1,
                            "rule", TownUiLegacyFacade.safeText(governance.rules().get(index)))));
        }
        rules = rules.append(Component.newline()).append(Component.newline())
                .append(presentation.dialogComponent("rules.locked-hint"));
        DialogInput acknowledged = DialogInput.bool("rules_acknowledged",
                presentation.dialogComponent("rules.acknowledgement"),
                false, "true", "false");
        presentation.openDialogPage(player, presentation.dialogText("rules.updated-title"),
                List.of(DialogBody.plainMessage(rules, 420)),
                List.of(acknowledged), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.multiAction(List.of(
                                ActionButton.create(presentation.dialogComponent("rules.confirm"),
                                        null, 170, presentation.dialogAction(player, session,
                                                response -> acknowledgeRulesDialog(player,
                                                        governance.townId(),
                                                        governance.townRulesRevision(), response))),
                                ActionButton.create(presentation.dialogComponent("rules.later"),
                                        presentation.dialogComponent("rules.later-tooltip"),
                                        170, presentation.dialogAction(player, session, "CLOSE", null))))
                        .exitAction(presentation.returnButton(player, session, DialogRoute.ROOT))
                        .columns(2).build(), DialogRoute.ROOT);
    }

    private void acknowledgeRulesDialog(Player player, UUID townId, long revision,
                                        DialogResponseView response) {
        if (!Boolean.TRUE.equals(response.getBoolean("rules_acknowledged"))) {
            presentation.openNotice(player, presentation.dialogText("rules.required-title"),
                    presentation.dialogText("rules.required-message"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        acknowledgeRules(player, townId, revision);
    }

    public void openTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town ->
                openReadOnlyTownRules(player, town,
                        new DialogRoute("TOWN", town.id().toString())));
    }

    public void openJoinTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-unavailable"))), town ->
                openReadOnlyTownRules(player, town,
                        new DialogRoute("JOIN_TOWN", town.id().toString())));
    }

    private void openReadOnlyTownRules(Player player, TownSnapshot town, DialogRoute returnRoute) {
        ReadOnlyRulesDialogRenderer.Layout layout = ReadOnlyRulesDialogRenderer.layout(
                town.profile().name(), town.profile().rules(), returnRoute);
        presentation.openDialogPage(player, presentation.dialogText("common.rules-title"), List.of(
                        DialogBody.plainMessage(ReadOnlyRulesDialogRenderer.content(
                                plugin.messages(), layout), ReadOnlyRulesDialogRenderer.CONTENT_WIDTH)),
                List.of(), DialogBase.DialogAfterAction.NONE,
                session -> DialogType.notice(presentation.returnButton(player, session, layout.returnRoute())),
                layout.returnRoute());
    }

    public void openTownRuleEditor(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(town.id(),
                    town.version(), town.profile().rules());
            DialogRoute parent = new DialogRoute("TOWN", town.id().toString());
            Component heading = presentation.dialogComponent("rules.edit-heading", Map.of(
                    "town", TownUiLegacyFacade.safeText(town.profile().name())));
            controls.openRuleEditorAddDialog(player, presentation.dialogText("rules.edit-title"),
                    heading, parent,
                    RuleEditorDialogRenderer.SINGLE_COLUMN_ACTION_WIDTH,
                    1,
                    session -> List.of(),
                    session -> controls.inlineRuleDeletionActions(player, session, layout,
                            deleteTarget -> deleteTownRule(player, deleteTarget)),
                    response -> addTownRule(player, town.id(), town.version(), response),
                    session -> List.of(), session -> List.of());
        });
    }

    private void addTownRule(Player player, UUID townId, long pageVersion,
                             DialogResponseView response) {
        String rule = TownUiLegacyFacade.responseText(response, "rule_text");
        if (rule.isBlank()) {
            presentation.openNotice(player, presentation.dialogText("rules.invalid-title"),
                    presentation.dialogText("rules.invalid-empty"), presentation.dialogText("common.back"),
                    "EDIT_TOWN_RULES", townId.toString());
            return;
        }
        updateTownRules(player, townId, pageVersion, rules -> {
            if (rules.size() >= 50) {
                throw new IllegalArgumentException(presentation.dialogText("rules.limit-reached"));
            }
            List<String> updated = new ArrayList<>(rules);
            updated.add(rule);
            return updated;
        });
    }

    private void deleteTownRule(Player player,
                                RuleEditorDialogRenderer.DeleteTarget deleteTarget) {
        updateTownRules(player, deleteTarget.pageId(), deleteTarget.pageVersion(), rules -> {
            if (rules.size() <= 1) {
                throw new IllegalArgumentException(presentation.dialogText("rules.minimum-one"));
            }
            if (deleteTarget.ruleIndex() < 0 || deleteTarget.ruleIndex() >= rules.size()) {
                throw new IllegalArgumentException(presentation.dialogText("rules.delete-missing"));
            }
            if (!rules.get(deleteTarget.ruleIndex()).equals(deleteTarget.expectedRule())) {
                throw new IllegalArgumentException(presentation.dialogText("rules.delete-conflict"));
            }
            List<String> updated = new ArrayList<>(rules);
            updated.remove(deleteTarget.ruleIndex());
            return updated;
        });
    }

    private void updateTownRules(Player player, UUID townId, long expectedVersion,
                                 Function<List<String>, List<String>> transform) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            if (town.version() != expectedVersion) {
                openTownRuleEditorRefreshNotice(player, town.id());
                return;
            }
            List<String> rules;
            try {
                rules = transform.apply(town.profile().rules());
            } catch (IllegalArgumentException exception) {
                presentation.openNotice(player, presentation.dialogText("rules.invalid-title"), exception.getMessage(),
                        presentation.dialogText("common.back"), "EDIT_TOWN_RULES", town.id().toString());
                return;
            }
            ApplicationText profile = new ApplicationText(town.profile().name(), town.profile().residenceName(),
                    town.profile().description(), rules);
            actions.updateTownProfile(player, town.id(), profile, expectedVersion, outcome -> {
                if (outcome.result().success()) {
                    openTownRuleEditor(player, outcome.value().id());
                } else if ("CONFLICT".equals(outcome.result().reason())) {
                    openTownRuleEditorRefreshNotice(player, town.id());
                } else {
                    facade.handleOutcome(player, outcome, ignored -> { });
                }
            });
        });
    }

    private void openTownRuleEditorRefreshNotice(Player player, UUID townId) {
        presentation.openNotice(player, presentation.dialogText("rules.refresh-title"),
                presentation.dialogText("rules.refresh-message"), presentation.dialogText("common.back"),
                "EDIT_TOWN_RULES", townId.toString());
    }

    public void acknowledgeRules(Player player, UUID townId, long revision) {
        actions.acknowledgeRules(player, townId, revision, outcome ->
                facade.handleOutcome(player, outcome, confirmed -> {
            presentation.openNotice(player, presentation.dialogText("notice.rules-confirmed-title"),
                    presentation.dialogText("notice.rules-confirmed-message", Map.of(
                            "revision", confirmed)),
                    presentation.dialogText("common.enter-town-service"), "MAIN", null);
        }));
    }

}

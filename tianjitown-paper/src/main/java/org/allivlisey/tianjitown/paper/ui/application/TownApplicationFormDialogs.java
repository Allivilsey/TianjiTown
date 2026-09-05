package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.message.ApplicationTextMessages;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.home.RuleEditorDialogRenderer;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.ApplicationField;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.ApplicationFormSession;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.FormPurpose;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Edits form fields and rules while checking the current player session. */
public final class TownApplicationFormDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;

    public TownApplicationFormDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
    }

    public void renderApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = facade.applicationFormUi().session(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            presentation.openNotice(player, presentation.dialogText("notice.edit-expired-title"),
                    presentation.dialogText("notice.edit-expired-message"), presentation.dialogText("common.reopen"),
                    "MAIN", null);
            return;
        }
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            renderTownProfileDialog(player, form);
        } else {
            renderApplicationBasicsDialog(player, form);
        }
    }

    public void renderApplicationFormStage(Player player, UUID formId, int stage) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        switch (stage) {
            case 1 -> renderApplicationBasicsDialog(player, form);
            case 2 -> renderApplicationContentDialog(player, form);
            case 3 -> renderApplicationMembersDialog(player, form);
            default -> throw new IllegalArgumentException(
                    plugin.messages().plainText("chat.application.invalid-form-step"));
        }
    }

    private void renderApplicationBasicsDialog(Player player, ApplicationFormSession form) {
        ApplicationText text = form.text();
        TextDialogInput.MultilineOptions descriptionLines = TextDialogInput.MultilineOptions
                .create(6, 90);
        List<DialogInput> inputs = List.of(
                DialogInput.text("town_name", 400,
                        presentation.dialogComponent("application.name-label"), true,
                        text.name(), 24, null),
                DialogInput.text("residence_name", 400,
                        presentation.dialogComponent("application.code-label"), true,
                        text.residenceName(), 12, null),
                DialogInput.text("description", 400,
                        presentation.dialogComponent("application.description-label"), true,
                        text.description(), 500, descriptionLines));
        Component guidance = presentation.dialogComponent("application.basics-heading")
                .append(Component.newline())
                .append(presentation.dialogComponent("application.basics-guidance"));
        presentation.openDialogPage(player, presentation.dialogText("application.title"), List.of(
                        DialogBody.plainMessage(guidance, 400)),
                inputs, DialogBase.DialogAfterAction.NONE, session -> DialogType.multiAction(List.of(
                                ActionButton.create(presentation.dialogComponent("common.next-step"),
                                        null, 170, presentation.dialogAction(player, session,
                                                response -> applyApplicationBasics(
                                                        player, form.id(), response))),
                                ActionButton.create(presentation.dialogComponent("application.save-draft"),
                                        presentation.dialogComponent("application.save-draft-tooltip"), 170,
                                        presentation.dialogAction(player, session, response ->
                                                saveApplicationStage(player, form.id(), 1, response)))))
                        .exitAction(presentation.returnButton(player, session,
                                new DialogRoute("MAIN", null)))
                        .columns(2).build(), new DialogRoute("MAIN", null));
    }

    private void applyApplicationBasics(Player player, UUID formId,
                                        DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        String townCode = responseText(response, "residence_name");
        ApplicationText updated = new ApplicationText(
                responseText(response, "town_name"), townCode, townCode,
                responseText(response, "description"), form.text().rules());
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        facade.applicationFormUi().putSession(player.getUniqueId(), candidate);
        List<String> errors = new ArrayList<>();
        errors.addAll(fieldErrors(player, candidate, ApplicationField.NAME));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.RESIDENCE_NAME));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.DESCRIPTION));
        facade.persistApplicationForm(player, candidate, 1, saved -> {
            if (!errors.isEmpty()) {
                presentation.openNotice(player, presentation.dialogText("notice.basics-invalid-title"),
                        String.join("\n", errors), presentation.dialogText("common.back"),
                        "APPLICATION_BASICS_FORM", form.id().toString());
            } else {
                renderApplicationContentDialog(player, candidate);
            }
        });
    }

    public void persistCurrentApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form != null) {
            facade.persistApplicationForm(player, form,
                    TownApplicationFormUi.FormStage.MEMBERS.step(), true);
        }
    }

    private void renderApplicationContentDialog(Player player, ApplicationFormSession form) {
        RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(form.id(),
                form.version(), form.text().rules());
        DialogRoute parent = new DialogRoute("APPLICATION_BASICS_FORM", form.id().toString());
        Component guidance = presentation.dialogComponent("application.content-heading")
                .append(Component.newline())
                .append(presentation.dialogComponent("application.content-guidance"));
        facade.openRuleEditorAddDialog(player, presentation.dialogText("application.title"), guidance, parent,
                RuleEditorDialogRenderer.SINGLE_COLUMN_ACTION_WIDTH,
                1,
                session -> List.of(ActionButton.create(presentation.dialogComponent("common.next-step"), null, 150,
                        presentation.dialogAction(player, session,
                                response -> applyApplicationContent(player, form.id())))),
                session -> facade.inlineRuleDeletionActions(player, session, layout,
                        deleteTarget -> deleteApplicationRule(player, deleteTarget)),
                response -> addApplicationRule(player, form.id(), response),
                session -> List.of(
                        ActionButton.create(presentation.dialogComponent("application.save-draft"),
                                presentation.dialogComponent("application.save-draft-tooltip"), 170,
                                presentation.dialogAction(player, session,
                                        response -> saveApplicationStage(player, form.id(), 2,
                                                response)))),
                session -> List.of());
    }

    private void addApplicationRule(Player player, UUID formId, DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        String rule = responseText(response, "rule_text");
        if (rule.isBlank()) {
            presentation.openNotice(player, presentation.dialogText("rules.invalid-title"),
                    presentation.dialogText("rules.invalid-empty"), presentation.dialogText("common.back"),
                    "APPLICATION_CONTENT_FORM", form.id().toString());
            return;
        }
        if (form.text().rules().size() >= 50) {
            presentation.openNotice(player, presentation.dialogText("rules.invalid-title"),
                    presentation.dialogText("rules.limit-reached"),
                    presentation.dialogText("common.back"), "APPLICATION_CONTENT_FORM", form.id().toString());
            return;
        }
        List<String> rules = new ArrayList<>(form.text().rules());
        rules.add(rule);
        updateApplicationRules(player, form, rules);
    }

    private void deleteApplicationRule(Player player,
                                       RuleEditorDialogRenderer.DeleteTarget deleteTarget) {
        ApplicationFormSession form = requireApplicationForm(player, deleteTarget.pageId());
        if (form == null) {
            return;
        }
        if (form.version() != deleteTarget.pageVersion()
                || deleteTarget.ruleIndex() < 0
                || deleteTarget.ruleIndex() >= form.text().rules().size()
                || !form.text().rules().get(deleteTarget.ruleIndex())
                .equals(deleteTarget.expectedRule())) {
            openApplicationRuleEditorRefreshNotice(player, form.id());
            return;
        }
        if (form.text().rules().size() <= 1) {
            presentation.openNotice(player, presentation.dialogText("rules.invalid-title"),
                    presentation.dialogText("rules.minimum-one"), presentation.dialogText("common.back"),
                    "APPLICATION_CONTENT_FORM", form.id().toString());
            return;
        }
        List<String> rules = new ArrayList<>(form.text().rules());
        rules.remove(deleteTarget.ruleIndex());
        updateApplicationRules(player, form, rules);
    }

    private void updateApplicationRules(Player player, ApplicationFormSession form,
                                        List<String> rules) {
        ApplicationText updated = new ApplicationText(form.text().name(), form.text().shortName(),
                form.text().residenceName(), form.text().description(), rules);
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        facade.applicationFormUi().putSession(player.getUniqueId(), candidate);
        facade.persistApplicationForm(player, candidate, 2,
                saved -> renderApplicationContentDialog(player, candidate));
    }

    private void applyApplicationContent(Player player, UUID formId) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        List<String> errors = new ArrayList<>();
        errors.addAll(fieldErrors(player, form, ApplicationField.RULES));
        facade.persistApplicationForm(player, form, 2, saved -> {
            if (!errors.isEmpty()) {
                presentation.openNotice(player, presentation.dialogText("notice.content-invalid-title"),
                        String.join("\n", errors), presentation.dialogText("common.back"),
                        "APPLICATION_CONTENT_FORM", form.id().toString());
            } else {
                renderApplicationMembersDialog(player, form);
            }
        });
    }

    private void saveApplicationStage(Player player, UUID formId, int step,
                                       DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        ApplicationFormSession updated = switch (step) {
            case 1 -> applicationBasicsCandidate(form, response);
            case 2 -> form;
            case 3 -> form;
            default -> throw new IllegalArgumentException(
                    plugin.messages().plainText("chat.application.invalid-form-step"));
        };
        facade.applicationFormUi().putSession(player.getUniqueId(), updated);
        facade.persistApplicationForm(player, updated, step, true);
    }

    private static ApplicationFormSession applicationBasicsCandidate(
            ApplicationFormSession form, DialogResponseView response) {
        String townCode = responseText(response, "residence_name");
        ApplicationText updated = new ApplicationText(
                responseText(response, "town_name"), townCode, townCode,
                responseText(response, "description"), form.text().rules());
        return new ApplicationFormSession(form.id(), form.purpose(), form.targetId(),
                form.version(), updated, form.initialMemberNames());
    }

    private void openApplicationRuleEditorRefreshNotice(Player player, UUID formId) {
        presentation.openNotice(player, presentation.dialogText("rules.refresh-title"),
                presentation.dialogText("rules.refresh-message"), presentation.dialogText("common.back"),
                "APPLICATION_CONTENT_FORM", formId.toString());
    }

    private void renderApplicationMembersDialog(Player player, ApplicationFormSession form) {
        String first = form.initialMemberNames().get(0);
        String second = form.initialMemberNames().get(1);
        Map<InitialMemberDialogLayout.Action, ItemStack> items = Map.of(
                InitialMemberDialogLayout.Action.FIRST_MEMBER, presentation.button(Material.PLAYER_HEAD,
                first.isBlank() ? presentation.dialogText("application.member-one-placeholder")
                        : "§a" + first,
                List.of(presentation.dialogText("common.application-member-select")),
                "SELECT_INITIAL_MEMBER",
                form.id() + ":0"),
                InitialMemberDialogLayout.Action.SECOND_MEMBER, presentation.button(Material.PLAYER_HEAD,
                second.isBlank() ? presentation.dialogText("application.member-two-placeholder")
                        : "§a" + second,
                List.of(presentation.dialogText("common.application-member-select")),
                "SELECT_INITIAL_MEMBER",
                form.id() + ":1"),
                InitialMemberDialogLayout.Action.COMPLETE, presentation.button(Material.WRITABLE_BOOK,
                presentation.dialogText("application.complete"),
                List.of(presentation.dialogText("common.application-member-save")),
                "SAVE_APPLICATION_DRAFT",
                form.id().toString()),
                InitialMemberDialogLayout.Action.SAVE_DRAFT, presentation.button(Material.CHEST,
                        presentation.dialogText("application.save-draft"),
                        List.of(presentation.dialogText("application.incomplete-members-hint")),
                        "SAVE_FORM_DRAFT", form.id().toString()),
                InitialMemberDialogLayout.Action.DISCARD_DRAFT, presentation.button(Material.BARRIER,
                        presentation.dialogText("application.discard-draft"),
                        List.of(presentation.dialogText("application.discard-draft-hint")),
                        "DISCARD_FORM_DRAFT", form.id().toString()));
        InitialMemberDialogLayout.Layout layout = InitialMemberDialogLayout.layout();
        DialogRoute parent = new DialogRoute("APPLICATION_CONTENT_FORM", form.id().toString());
        presentation.openDialogPage(player, presentation.dialogText("application.members-title"), List.of(), List.of(),
                DialogBase.DialogAfterAction.NONE, session -> DialogType.multiAction(
                                layout.actions().stream()
                                        .map(action -> presentation.dialogButton(player, items.get(action), session))
                                        .toList())
                        .exitAction(presentation.returnButton(player, session, parent))
                        .columns(InitialMemberDialogLayout.COLUMNS)
                        .build(), parent);
    }

    public void openInitialMemberOptions(Player player, UUID formId, int memberIndex) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                .filter(candidate -> !candidate.getName().equalsIgnoreCase(
                        form.initialMemberNames().get(1 - memberIndex)))
                .sorted(java.util.Comparator.comparing(Player::getName,
                        String.CASE_INSENSITIVE_ORDER)).toList();
        if (candidates.isEmpty()) {
            presentation.openNotice(player, presentation.dialogText("notice.no-candidates-title"),
                    presentation.dialogText("notice.no-candidates-message"),
                    presentation.dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                    formId.toString());
            return;
        }
        List<MenuItem> items = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Player candidate = candidates.get(index);
            items.add(new MenuItem(index, presentation.button(Material.PLAYER_HEAD, "§e" + candidate.getName(),
                    List.of(), "CHOOSE_INITIAL_MEMBER",
                    formId + ":" + memberIndex + ":" + candidate.getUniqueId())));
        }
        presentation.openMenu(player, 54, memberIndex == 0
                ? presentation.dialogText("application.select-member-one-title")
                : presentation.dialogText("application.select-member-two-title"),
                new DialogRoute("APPLICATION_MEMBERS_FORM", formId.toString()), items);
    }

    public void chooseInitialMember(Player player, String target) {
        String[] parts = target.split(":");
        UUID formId = UUID.fromString(parts[0]);
        int memberIndex = Integer.parseInt(parts[1]);
        UUID candidateId = UUID.fromString(parts[2]);
        ApplicationFormSession form = requireApplicationForm(player, formId);
        Player candidate = Bukkit.getPlayer(candidateId);
        if (form == null) {
            return;
        }
        if (candidate == null || candidate.getUniqueId().equals(player.getUniqueId())) {
            presentation.openNotice(player, presentation.dialogText("notice.player-unavailable-title"),
                    presentation.dialogText("notice.player-unavailable-message"),
                    presentation.dialogText("common.select-again"),
                    "SELECT_INITIAL_MEMBER", formId + ":" + memberIndex);
            return;
        }
        List<String> members = new ArrayList<>(form.initialMemberNames());
        members.set(memberIndex, candidate.getName());
        ApplicationFormSession updated = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), form.text(), members);
        facade.applicationFormUi().putSession(player.getUniqueId(), updated);
        facade.persistApplicationForm(player, updated, 3,
                saved -> renderApplicationMembersDialog(player, updated));
    }

    private void renderTownProfileDialog(Player player, ApplicationFormSession form) {
        List<DialogInput> inputs = List.of(
                DialogInput.text("description", 400,
                        presentation.dialogComponent("application.description-label"), true,
                        form.text().description(), 500,
                        TextDialogInput.MultilineOptions.create(6, 100)));
        Component guidance = presentation.dialogComponent("application.profile-name",
                        Map.of("name", form.text().name()))
                .append(Component.newline())
                .append(presentation.dialogComponent("application.profile-description-guidance"));
        presentation.openDialogPage(player, presentation.dialogText("application.profile-title"),
                List.of(DialogBody.plainMessage(guidance, 420)),
                inputs, DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.multiAction(List.of(
                                ActionButton.create(presentation.dialogComponent("common.save-changes"),
                                        null, 170, presentation.dialogAction(player, session,
                                                response -> applyTownProfileDialog(
                                                        player, form.id(), response)))))
                        .exitAction(presentation.returnButton(player, session,
                                new DialogRoute("TOWN", form.targetId().toString())))
                        .columns(1).build(), new DialogRoute("TOWN", form.targetId().toString()));
    }

    private void applyTownProfileDialog(Player player, UUID formId,
                                        DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        ApplicationText updated = updateField(form.text(), ApplicationField.DESCRIPTION,
                responseText(response, "description"));
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        facade.applicationFormUi().putSession(player.getUniqueId(), candidate);
        facade.saveApplicationForm(player, formId);
    }

    public ApplicationFormSession requireApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = facade.applicationFormUi().session(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            presentation.openNotice(player, presentation.dialogText("notice.edit-expired-title"),
                    presentation.dialogText("notice.edit-expired-message"), presentation.dialogText("common.reopen"),
                    "MAIN", null);
            return null;
        }
        return form;
    }

    public static String responseText(DialogResponseView response, String key) {
        return Objects.requireNonNullElse(response.getText(key), "").strip();
    }

    private static ApplicationText updateField(ApplicationText text, ApplicationField field,
                                               String value) {
        List<String> rules = field == ApplicationField.RULES
                ? java.util.Arrays.stream(value.split("[|｜\\r\\n]+", -1)).map(String::strip)
                .filter(rule -> !rule.isBlank()).toList() : text.rules();
        return new ApplicationText(
                field == ApplicationField.NAME ? value : text.name(),
                text.shortName(),
                field == ApplicationField.RESIDENCE_NAME ? value : text.residenceName(),
                field == ApplicationField.DESCRIPTION ? value : text.description(),
                rules);
    }

    private static List<String> updateInitialMemberNames(List<String> current,
                                                         ApplicationField field,
                                                         String value) {
        List<String> result = new ArrayList<>(TownUiLegacyFacade.normalizedMemberNames(current));
        if (field == ApplicationField.INITIAL_MEMBER_ONE) {
            result.set(0, value.strip());
        } else if (field == ApplicationField.INITIAL_MEMBER_TWO) {
            result.set(1, value.strip());
        }
        return List.copyOf(result);
    }

    private List<String> fieldErrors(Player player, ApplicationFormSession form,
                                     ApplicationField field) {
        if (field == ApplicationField.INITIAL_MEMBER_ONE
                || field == ApplicationField.INITIAL_MEMBER_TWO) {
            int index = field == ApplicationField.INITIAL_MEMBER_ONE ? 0 : 1;
            String name = form.initialMemberNames().get(index);
            List<String> errors = new ArrayList<>();
            if (name.isBlank()) {
                errors.add(presentation.dialogText("application.initial-member-required"));
            } else {
                Player candidate = Bukkit.getPlayerExact(name);
                if (candidate == null) {
                    errors.add(presentation.dialogText("application.initial-member-online-required"));
                } else if (candidate.getUniqueId().equals(player.getUniqueId())) {
                    errors.add(presentation.dialogText("application.initial-member-applicant-forbidden"));
                }
            }
            if (!name.isBlank() && form.initialMemberNames().stream()
                    .filter(name::equalsIgnoreCase).count() > 1) {
                errors.add(presentation.dialogText("application.initial-members-distinct-required"));
            }
            return List.copyOf(errors);
        }
        ApplicationText.ValidationIssue.Field validationField = switch (field) {
            case NAME -> ApplicationText.ValidationIssue.Field.NAME;
            case RESIDENCE_NAME -> ApplicationText.ValidationIssue.Field.RESIDENCE_NAME;
            case DESCRIPTION -> ApplicationText.ValidationIssue.Field.DESCRIPTION;
            case RULES -> ApplicationText.ValidationIssue.Field.RULES;
            case INITIAL_MEMBER_ONE, INITIAL_MEMBER_TWO -> null;
        };
        return form.text().validate().stream()
                .filter(issue -> issue.field() == validationField)
                .map(issue -> ApplicationTextMessages.render(plugin.messages(), issue))
                .toList();
    }
}

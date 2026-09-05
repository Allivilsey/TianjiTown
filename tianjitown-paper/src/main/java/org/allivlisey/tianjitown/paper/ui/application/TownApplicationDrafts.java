package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.message.ApplicationTextMessages;
import org.allivlisey.tianjitown.paper.runtime.TownActionOutcome;
import org.allivlisey.tianjitown.paper.runtime.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.ApplicationFormDraft;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.ApplicationFormSession;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.FormPurpose;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Loads, persists and submits founding drafts and town profile edits. */
public final class TownApplicationDrafts {
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownApplicationDrafts(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void loadApplicationForForm(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application ->
                startApplicationForm(player, application.id(), application.version(),
                        application.text(), application.initialMembers().stream()
                                .map(member -> facade.displayName(member.playerId())).toList()));
    }

    public void loadTownForForm(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            startTownProfileForm(player, town.id(), town.version(), town.profile());
        });
    }

    private void startApplicationForm(Player player, UUID targetId, long version,
                                      ApplicationText text, List<String> initialMemberNames) {
        startApplicationForm(player, targetId, version, text, initialMemberNames, 1);
    }

    private void startApplicationForm(Player player, UUID targetId, long version,
                                      ApplicationText text, List<String> initialMemberNames,
                                      int step) {
        if (facade.maintenanceMode()) {
            facade.openNotice(player, facade.dialogText("notice.maintenance-title"),
                    plugin.messages().text("system.maintenance"), facade.dialogText("common.close"),
                    "CLOSE", null);
            return;
        }
        UUID formId = UUID.randomUUID();
        facade.applicationFormUi().putSession(player.getUniqueId(),
                new ApplicationFormSession(formId, FormPurpose.APPLICATION, targetId, version, text,
                        normalizedMemberNames(initialMemberNames)));
        facade.closeUi(player);
        facade.renderApplicationFormStage(player, formId, step);
    }

    public void loadApplicationFormDraft(Player player) {
        runtime.read(player, () -> runtime.repository().findFormDraft(player.getUniqueId())
                .orElse(null), draft -> {
            if (draft == null) {
                startApplicationForm(player, null, 0,
                        new ApplicationText("", "", "", "", List.of()), List.of(), 1);
                return;
            }
            ApplicationText text = new ApplicationText(draft.name(), draft.shortName(),
                    draft.residenceName(), draft.description(), draft.rules());
            startApplicationForm(player, draft.applicationId(), draft.applicationVersion(), text,
                    List.of(draft.memberOneName(), draft.memberTwoName()), draft.currentStep());
        });
    }

    public void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        boolean exitAfterSave) {
        persistApplicationForm(player, form, step, ignored -> {
            if (exitAfterSave) {
                facade.applicationFormUi().removeSession(player.getUniqueId(), form);
                facade.openNotice(player, facade.dialogText("common.draft-saved-title"),
                        facade.dialogText("notice.form-draft-saved-message"),
                        facade.dialogText("common.back"), "MAIN", null);
            } else {
                facade.renderApplicationFormStage(player, form.id(), step);
            }
        });
    }

    public void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        Consumer<ApplicationFormDraft> afterSave) {
        List<String> names = normalizedMemberNames(form.initialMemberNames());
        UUID first = playerIdForDraft(names.get(0));
        UUID second = playerIdForDraft(names.get(1));
        ApplicationFormDraft draft = new ApplicationFormDraft(player.getUniqueId(),
                form.targetId(), form.version(), step, form.text().name(), form.text().shortName(),
                form.text().residenceName(), form.text().description(), form.text().rules(),
                first, names.get(0), second, names.get(1), null);
        runtime.write(player, () -> runtime.repository().saveFormDraft(draft), afterSave);
    }

    private static UUID playerIdForDraft(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        org.bukkit.OfflinePlayer cached = Bukkit.getOfflinePlayer(name);
        return cached.hasPlayedBefore() ? cached.getUniqueId() : null;
    }

    public void discardApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = facade.requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        facade.applicationFormUi().removeSession(player.getUniqueId(), form);
        runtime.write(player, () -> {
            runtime.repository().deleteFormDraft(player.getUniqueId());
            return true;
        }, ignored -> facade.openMain(player));
    }

    private void startTownProfileForm(Player player, UUID townId, long version,
                                      ApplicationText text) {
        if (facade.maintenanceMode()) {
            facade.openNotice(player, facade.dialogText("notice.maintenance-title"),
                    plugin.messages().text("system.maintenance"), facade.dialogText("common.close"),
                    "CLOSE", null);
            return;
        }
        UUID formId = UUID.randomUUID();
        facade.applicationFormUi().putSession(player.getUniqueId(), new ApplicationFormSession(formId,
                FormPurpose.TOWN_PROFILE, townId, version, text, List.of()));
        facade.closeUi(player);
        facade.renderApplicationForm(player, formId);
    }

    public void saveApplicationForm(Player player, UUID formId) {
        if (facade.maintenanceMode()) {
            facade.applicationFormUi().removeSession(player.getUniqueId());
            facade.openNotice(player, facade.dialogText("notice.draft-not-saved-title"),
                    plugin.messages().text("system.maintenance"), facade.dialogText("common.close"),
                    "CLOSE", null);
            return;
        }
        ApplicationFormSession form = facade.applicationFormUi().session(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            facade.openNotice(player, facade.dialogText("notice.edit-expired-title"),
                    facade.dialogText("notice.edit-expired-message"), facade.dialogText("common.reopen"),
                    "MAIN", null);
            return;
        }
        try {
            form.text().requireValid();
        } catch (ApplicationText.ValidationException exception) {
            facade.openNotice(player, facade.dialogText("notice.draft-incomplete-title"),
                    ApplicationTextMessages.join(plugin.messages(), exception.issues()),
                    facade.dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                    form.id().toString());
            return;
        }
        if (form.purpose() == FormPurpose.APPLICATION) {
            try {
                requireInitialMemberIds(player, form.initialMemberNames());
            } catch (IllegalArgumentException exception) {
                facade.openNotice(player, facade.dialogText("notice.draft-incomplete-title"),
                        exception.getMessage(), facade.dialogText("common.back"),
                        "APPLICATION_MEMBERS_FORM", form.id().toString());
                return;
            }
        }
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            actions.updateTownProfile(player, form.targetId(), form.text(), form.version(), outcome ->
                    facade.handleOutcome(player, outcome, town -> {
                facade.applicationFormUi().removeSession(player.getUniqueId(), form);
                facade.openNotice(player, facade.dialogText("notice.profile-saved-title"),
                        facade.dialogText("notice.profile-saved-message"),
                        facade.dialogText("common.back"), "TOWN", town.id().toString());
            }));
            return;
        }
        List<UUID> initialMemberIds = requireInitialMemberIds(player,
                form.initialMemberNames());
        runtime.readAction(player,
                () -> runtime.repository().initialMemberConflicts(initialMemberIds),
                conflicts -> {
                    if (!conflicts.isEmpty()) {
                        facade.openNotice(player, facade.dialogText(
                                        "application.initial-members-unavailable-title"),
                                conflicts.stream().map(conflict -> facade.dialogText(
                                                "application.initial-member-conflict", Map.of(
                                                        "player", TownUiLegacyFacade.safeText(facade.displayName(
                                                                conflict.playerId())),
                                                        "town", TownUiLegacyFacade.safeText(Objects.requireNonNullElse(
                                                                conflict.townName(),
                                                                plugin.messages().plainText(
                                                                        "dialog.application.unknown-town"))))))
                                        .collect(java.util.stream.Collectors.joining("\n")),
                                facade.dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                                form.id().toString());
                        return;
                    }
                    saveFormalApplication(player, form, initialMemberIds);
                }, exception -> facade.openNotice(player, facade.dialogText(
                                "application.initial-members-precheck-failed-title"),
                        TownUiLegacyFacade.safeText(TownUiLegacyFacade.safeMessage(exception)),
                        facade.dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                        form.id().toString()));
    }

    private void saveFormalApplication(Player player, ApplicationFormSession form,
                                       List<UUID> initialMemberIds) {
        if (form.targetId() == null) {
            actions.createApplication(player, form.text(), initialMemberIds, outcome ->
                    handleApplicationSaveOutcome(player, form, outcome, application -> {
                clearPersistedDraft(player);
                facade.notifyInitialMembers(application);
                facade.openNotice(player, facade.dialogText("common.draft-saved-title"),
                        plugin.messages().text("application.draft-saved"),
                        facade.dialogText("common.continue-processing"),
                        "APPLICATION", application.id().toString());
            }));
        } else {
            actions.updateApplication(player, form.targetId(), form.text(), initialMemberIds,
                    form.version(), outcome ->
                    handleApplicationSaveOutcome(player, form, outcome, application -> {
                clearPersistedDraft(player);
                facade.notifyInitialMembers(application);
                facade.openNotice(player, facade.dialogText("common.draft-saved-title"),
                        plugin.messages().text("application.draft-saved"),
                        facade.dialogText("common.continue-processing"),
                        "APPLICATION", application.id().toString());
            }));
        }
    }

    private void handleApplicationSaveOutcome(Player player, ApplicationFormSession form,
                                              TownActionOutcome<ApplicationSnapshot> outcome,
                                              Consumer<ApplicationSnapshot> success) {
        if (outcome.result().success()) {
            facade.applicationFormUi().removeSession(player.getUniqueId(), form);
            success.accept(outcome.value());
            return;
        }
        if (outcome.result().reason().equals("MEMBER_CONFLICT")) {
            String playerId = outcome.result().data().get("player_id");
            String townName = outcome.result().data().get("town_name");
            String display = playerId == null
                    ? plugin.messages().plainText("dialog.application.unknown-player")
                    : facade.displayName(UUID.fromString(playerId));
            String town = Objects.requireNonNullElse(townName,
                    plugin.messages().plainText("dialog.application.unknown-town"));
            facade.openNotice(player, facade.dialogText("application.initial-members-unavailable-title"),
                    facade.dialogText("application.initial-member-conflict-retry", Map.of(
                            "player", TownUiLegacyFacade.safeText(display), "town", TownUiLegacyFacade.safeText(town))),
                    facade.dialogText("common.back"), "APPLICATION_MEMBERS_FORM", form.id().toString());
            return;
        }
        facade.handleOutcome(player, outcome, success);
    }

    private void clearPersistedDraft(Player player) {
        plugin.runAsync(() -> {
            try {
                runtime.repository().deleteFormDraft(player.getUniqueId());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.application.draft-cleanup-failure", Map.of(
                                "player", TownUiLegacyFacade.safeText(player.getUniqueId()),
                                "detail", TownUiLegacyFacade.safeText(TownUiLegacyFacade.safeMessage(exception)))));
            }
        });
    }

    private void cancelApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = facade.applicationFormUi().session(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            facade.openNotice(player, facade.dialogText("notice.edit-expired-title"),
                    facade.dialogText("notice.edit-expired-message"), facade.dialogText("common.reopen"),
                    "MAIN", null);
            return;
        }
        facade.applicationFormUi().removeSession(player.getUniqueId(), form);
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            facade.openTown(player, form.targetId());
        } else if (form.targetId() == null) {
            facade.openMain(player);
        } else {
            facade.loadApplication(player, form.targetId());
        }
    }

    public static List<String> normalizedMemberNames(List<String> names) {
        List<String> result = new ArrayList<>(List.of("", ""));
        if (names != null) {
            for (int index = 0; index < Math.min(2, names.size()); index++) {
                result.set(index, Objects.requireNonNullElse(names.get(index), "").strip());
            }
        }
        return List.copyOf(result);
    }

    private List<UUID> requireInitialMemberIds(Player applicant, List<String> names) {
        List<String> normalized = normalizedMemberNames(names);
        List<UUID> ids = new ArrayList<>(2);
        for (String name : normalized) {
            Player member = Bukkit.getPlayerExact(name);
            if (member == null) {
                throw new IllegalArgumentException(facade.dialogText(
                        "application.initial-members-online-required"));
            }
            if (member.getUniqueId().equals(applicant.getUniqueId())) {
                throw new IllegalArgumentException(facade.dialogText(
                        "application.initial-members-applicant-forbidden"));
            }
            ids.add(member.getUniqueId());
        }
        if (ids.stream().distinct().count() != 2) {
            throw new IllegalArgumentException(facade.dialogText(
                    "application.initial-members-distinct-validation"));
        }
        return List.copyOf(ids);
    }
}

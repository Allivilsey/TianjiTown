package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.land.TerritoryPreviewService;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.InitialMemberConfirmation;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Displays founding applications and handles their submission lifecycle. */
public final class TownApplicationDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    private final TerritoryPreviewService territoryPreviews;

    public TownApplicationDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
        this.territoryPreviews = facade.territoryPreviews();
    }

    public void openApplication(Player player, ApplicationSnapshot application) {
        renderApplication(player, application);
    }

    public void renderApplication(Player player, ApplicationSnapshot application) {
        if (application.applicantId().equals(player.getUniqueId())
                && application.needsInitialMemberReselection()
                && (application.status() == ApplicationStatus.DRAFT
                    || application.status() == ApplicationStatus.SITE_SELECTED
                    || application.status() == ApplicationStatus.NEED_CHANGES)) {
            facade.loadApplicationForForm(player, application.id());
            return;
        }
        List<String> summary = new ArrayList<>(List.of(
                presentation.dialogText("common.applicant", Map.of(
                        "applicant", TownUiLegacyFacade.safeText(facade.displayName(application.applicantId())))),
                presentation.dialogText("common.application-status", Map.of(
                        "status", presentation.dialogText(ApplicationStatusText.messageKey(application.status())))),
                presentation.dialogText("common.name", Map.of("name", TownUiLegacyFacade.safeText(application.text().name()))),
                presentation.dialogText("common.residence-name", Map.of(
                        "residence", TownUiLegacyFacade.safeText(application.text().normalizedResidenceName()))),
                presentation.dialogText("common.town-description", Map.of(
                        "description", TownUiLegacyFacade.safeText(application.text().description())))));
        for (InitialMemberConfirmation member : application.initialMembers()) {
            summary.add(presentation.dialogText("application.initial-member", Map.of(
                    "player", TownUiLegacyFacade.safeText(facade.displayName(member.playerId())),
                    "status", initialMemberStatus(member.status()))));
        }
        for (int index = 0; index < application.text().rules().size(); index++) {
            summary.add(presentation.dialogText("application.rule", Map.of(
                    "index", index + 1,
                    "rule", TownUiLegacyFacade.safeText(application.text().rules().get(index)))));
        }
        if (application.territory() != null) {
            summary.add(presentation.dialogText("application.territory-center", Map.of(
                    "x", application.territory().center().x(),
                    "z", application.territory().center().z())));
        }
        if (application.reviewMessage() != null) {
            summary.add(presentation.dialogText("common.admin-review-message", Map.of(
                    "message", TownUiLegacyFacade.safeText(application.reviewMessage()))));
        }
        if (application.lastError() != null) {
            summary.add(presentation.dialogText("application.last-error", Map.of(
                    "error", TownUiLegacyFacade.safeText(application.lastError()))));
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(4, presentation.button(Material.PAPER,
                presentation.dialogText("application.summary-title"), summary, null, null)));
        if (application.status() == ApplicationStatus.DRAFT
                || application.status() == ApplicationStatus.SITE_SELECTED
                || application.status() == ApplicationStatus.NEED_CHANGES) {
            items.add(new MenuItem(10, presentation.button(Material.WRITABLE_BOOK,
                    presentation.dialogText("application.edit"),
                    List.of(presentation.dialogText("tooltip.application.edit")), "EDIT_APPLICATION",
                    application.id().toString())));
            if (application.initialMembers().stream().anyMatch(member ->
                    member.status() == InitialMemberConfirmation.Status.PENDING)) {
                items.add(new MenuItem(11, presentation.button(Material.BELL, presentation.dialogText("application.remind"),
                        List.of(presentation.dialogText("tooltip.application.remind"),
                                presentation.dialogText("tooltip.application.remind-cooldown")),
                        "REMIND_INITIAL_MEMBERS", application.id().toString())));
            }
            items.add(new MenuItem(12, presentation.button(Material.COMPASS,
                    presentation.dialogText(application.territory() == null ? "application.select-site" : "application.reselect-site"),
                    List.of(presentation.dialogText(application.territory() == null ? "tooltip.application.select-site" : "application.reselect-site")), "SELECT_SITE",
                    application.id().toString())));
            if (application.territory() != null) {
                items.add(new MenuItem(14, presentation.button(Material.ENDER_EYE,
                        presentation.dialogText("application.preview-site"),
                        List.of(presentation.dialogText("common.preview-site")), "PREVIEW_SITE",
                        application.id().toString())));
                boolean confirmed = application.initialMembersConfirmed();
                items.add(new MenuItem(16, presentation.button(confirmed ? Material.LIME_CONCRETE
                                : Material.GRAY_CONCRETE,
                        confirmed ? presentation.dialogText("application.submit")
                                : presentation.dialogText("application.waiting-members"),
                        ApplicationSubmissionDialogRenderer.submitTooltipKeys(confirmed).stream()
                                .map(facade.presentation()::dialogText).toList(),
                        confirmed ? "CONFIRM_SUBMIT" : null,
                        confirmed ? application.id().toString() : null)));
            }
            items.add(new MenuItem(22, presentation.button(Material.BARRIER, presentation.dialogText("application.cancel"),
                    List.of(presentation.dialogText("tooltip.application.cancel-draft")), "CONFIRM_CANCEL",
                    application.id().toString())));
        } else if (application.status() == ApplicationStatus.SUBMITTED
                || application.status() == ApplicationStatus.UNDER_REVIEW) {
            items.add(new MenuItem(22, presentation.button(Material.BARRIER, presentation.dialogText("application.cancel"),
                    List.of(presentation.dialogText("tooltip.application.cancel-submitted")), "CONFIRM_CANCEL",
                    application.id().toString())));
        }
        presentation.openMenu(player, 27, presentation.dialogText("application.summary-title"),
                new DialogRoute("MAIN", null), items);
    }

    private String initialMemberStatus(InitialMemberConfirmation.Status status) {
        return switch (status) {
            case PENDING -> presentation.dialogText("application.initial-member-status.pending");
            case CONFIRMED -> presentation.dialogText("application.initial-member-status.confirmed");
            case REJECTED -> presentation.dialogText("application.initial-member-status.rejected");
        };
    }

    public void loadApplication(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))),
                application -> openApplication(player, application));
    }

    public void selectApplicationSite(Player player, UUID applicationId) {
        actions.selectApplicationSite(player, applicationId, outcome ->
                facade.handleOutcome(player, outcome, application -> {
            territoryPreviews.previewSilently(player, application.territory());
            openApplication(player, application);
        }));
    }

    public void previewApplicationSite(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            if (application.territory() == null) {
                presentation.openNotice(player, presentation.dialogText("notice.site-missing-title"),
                        presentation.dialogText("notice.site-missing-message"),
                        presentation.dialogText("common.back"), "APPLICATION",
                        applicationId.toString());
            } else {
                territoryPreviews.teleportAndPreviewSilently(player, application.territory());
            }
        });
    }

    public void submitApplication(Player player, UUID applicationId) {
        actions.submitApplication(player, applicationId, outcome ->
                facade.handleOutcome(player, outcome, application -> {
                    presentation.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyApplicationSubmitted(application);
                    presentation.openNotice(player, presentation.dialogText("notice.application-submitted-title"),
                            presentation.dialogText("notice.application-submitted-message"),
                            presentation.dialogText("common.view-application"), "APPLICATION",
                            application.id().toString());
                }));
    }

    public void cancelApplication(Player player, UUID applicationId) {
        actions.cancelApplication(player, applicationId, outcome ->
                facade.handleOutcome(player, outcome, application -> {
            presentation.openNotice(player, presentation.dialogText("notice.application-cancelled-title"),
                    presentation.dialogText("notice.application-cancelled-message"),
                    presentation.dialogText("common.back"), "MAIN", null);
        }));
    }

    public void notifyApplicationSubmitted(ApplicationSnapshot application) {
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (!admin.hasPermission("tianjitown.admin")) {
                continue;
            }
            admin.sendMessage(plugin.messages().component("chat.notification.new-application", Map.of(
                            "town", application.text().name()))
                    .append(presentation.callbackButton(admin, "chat.buttons.review-join",
                            () -> facade.openAdminApplication(admin, application.id()))));
            presentation.playSound(admin, Sound.BLOCK_BELL_USE);
        }
    }
}

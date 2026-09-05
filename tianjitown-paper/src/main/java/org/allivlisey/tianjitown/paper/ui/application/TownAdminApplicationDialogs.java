package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.land.ProvisionResult;
import org.allivlisey.tianjitown.paper.land.TerritoryPreviewService;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Handles administrator review, recovery and application site previews. */
public final class TownAdminApplicationDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    private final TerritoryPreviewService territoryPreviews;

    public TownAdminApplicationDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
        this.territoryPreviews = facade.territoryPreviews();
    }

    public void openAdminApplications(Player admin) {
        openAdminApplications(admin, 0);
    }

    public void openAdminApplications(Player admin, int requestedPage) {
        if (!admin.hasPermission("tianjitown.admin")) {
            presentation.openNotice(admin, presentation.dialogText("notice.no-permission-title"),
                    presentation.dialogText("notice.review-list-forbidden"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        int page = Math.max(0, requestedPage);
        runtime.read(admin, () -> runtime.repository().listReviewQueue(45), applications -> {
            List<MenuItem> items = new ArrayList<>();
            List<ApplicationSnapshot> visible = TownUiLegacyFacade.page(applications, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                ApplicationSnapshot application = visible.get(index);
                items.add(new MenuItem(index, presentation.button(Material.WRITABLE_BOOK,
                        presentation.dialogText("common.town-entry-title", Map.of(
                                "town", TownUiLegacyFacade.safeText(application.text().name()))),
                        List.of(presentation.dialogText("common.town-code", Map.of(
                                        "code", TownUiLegacyFacade.safeText(application.text().residenceName()))),
                                presentation.dialogText("tooltip.admin.entry-review")),
                        "ADMIN_APPLICATION", application.id().toString())));
            }
            if (applications.isEmpty()) {
                items.add(new MenuItem(0, presentation.button(Material.BOOK, presentation.dialogText("admin.list-empty"),
                        List.of(presentation.dialogText("admin.list-empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, presentation.button(Material.ARROW, presentation.dialogText("common.previous"),
                        List.of(),
                        "ADMIN_APPLICATIONS_PAGE", String.valueOf(page - 1))));
            }
            if (TownUiLegacyFacade.hasNext(applications, page, 8)) {
                items.add(new MenuItem(53, presentation.button(Material.ARROW, presentation.dialogText("common.next"),
                        List.of(),
                        "ADMIN_APPLICATIONS_PAGE", String.valueOf(page + 1))));
            }
            presentation.openMenu(admin, 54, presentation.dialogText("admin.list-title", Map.of(
                            "count", applications.size(), "page", page + 1)),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void openAdminApplication(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            presentation.openNotice(admin, presentation.dialogText("notice.no-permission-title"),
                    presentation.dialogText("notice.review-detail-forbidden"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            String submittedAt = application.submittedAt() == null
                    ? presentation.dialogText("admin.not-submitted") : TownUiLegacyFacade.safeText(application.submittedAt());
            String rules = application.text().rules().stream().map(TownUiLegacyFacade::safeText)
                    .collect(java.util.stream.Collectors.joining(" | "));
            List<String> summary = new ArrayList<>(List.of(
                    presentation.dialogText("common.applicant", Map.of(
                            "applicant", TownUiLegacyFacade.safeText(facade.displayName(application.applicantId())))),
                    presentation.dialogText("common.application-status", Map.of("status",
                            presentation.dialogText(ApplicationStatusText.messageKey(application.status())))),
                    presentation.dialogText("admin.submitted-at", Map.of("time", submittedAt)),
                    presentation.dialogText("admin.updated-at", Map.of("time", TownUiLegacyFacade.safeText(application.updatedAt()))),
                    presentation.dialogText("common.name", Map.of("name", TownUiLegacyFacade.safeText(application.text().name()))),
                    presentation.dialogText("common.residence-name", Map.of(
                            "residence", TownUiLegacyFacade.safeText(application.text().normalizedResidenceName()))),
                    presentation.dialogText("common.town-description", Map.of(
                            "description", TownUiLegacyFacade.safeText(application.text().description()))),
                    presentation.dialogText("common.rules", Map.of("rules", rules))));
            if (application.territory() != null) {
                summary.add(presentation.dialogText("admin.territory", Map.of(
                        "world", TownUiLegacyFacade.safeText(application.territory().center().worldName()),
                        "x", application.territory().center().x(),
                        "z", application.territory().center().z())));
            }
            if (application.lastError() != null) {
                summary.add(presentation.dialogText("admin.creation-error", Map.of(
                        "error", TownUiLegacyFacade.safeText(application.lastError()))));
            }
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, presentation.button(Material.PAPER,
                    presentation.dialogText("admin.summary-title"), summary, null, null)));
            if (application.status() == ApplicationStatus.SUBMITTED
                    || application.status() == ApplicationStatus.UNDER_REVIEW) {
                items.add(new MenuItem(10, presentation.button(Material.LIME_CONCRETE,
                        presentation.dialogText("admin.approve"),
                        List.of(presentation.dialogText("tooltip.admin.approve")), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
                items.add(new MenuItem(12, presentation.button(Material.RED_CONCRETE,
                        presentation.dialogText("common.reject"),
                        List.of(presentation.dialogText("tooltip.admin.reject")), "CONFIRM_ADMIN_REJECT",
                        application.id().toString())));
                items.add(new MenuItem(14, presentation.button(Material.ORANGE_CONCRETE,
                        presentation.dialogText("admin.request-changes"),
                        List.of(presentation.dialogText("tooltip.admin.change")), "CONFIRM_ADMIN_CHANGE",
                        application.id().toString())));
            } else if (application.status() == ApplicationStatus.PROVISION_FAILED) {
                items.add(new MenuItem(10, presentation.button(Material.LIME_CONCRETE,
                        presentation.dialogText("admin.retry-approve"),
                        List.of(presentation.dialogText("tooltip.admin.retry")), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
                items.add(new MenuItem(11, presentation.button(Material.ORANGE_CONCRETE,
                        presentation.dialogText("admin.unlock-for-changes"),
                        List.of(presentation.dialogText("admin.unlock-for-changes-hint")),
                        "CONFIRM_FAILED_RECOVERY",
                        "UNLOCK_FOR_CHANGES:" + application.id())));
                items.add(new MenuItem(12, presentation.button(Material.GOLD_INGOT,
                        presentation.dialogText("admin.cancel-and-refund"),
                        List.of(presentation.dialogText("admin.cancel-and-refund-hint")),
                        "CONFIRM_FAILED_RECOVERY",
                        "CANCEL_AND_REFUND:" + application.id())));
                items.add(new MenuItem(14, presentation.button(Material.BARRIER,
                        presentation.dialogText("admin.force-cleanup"),
                        List.of(presentation.dialogText("admin.force-cleanup-hint")),
                        "CONFIRM_FAILED_RECOVERY", "FORCE_CLEANUP:" + application.id())));
            }
            if (application.territory() != null) {
                items.add(new MenuItem(16, presentation.button(Material.ENDER_EYE,
                        presentation.dialogText("admin.preview-site"),
                        List.of(presentation.dialogText("common.preview-site")), "ADMIN_PREVIEW_SITE",
                        application.id().toString())));
            }
            presentation.openMenu(admin, 27, presentation.dialogText("admin.detail-title", Map.of(
                            "town", TownUiLegacyFacade.safeText(application.text().name()))),
                    new DialogRoute("ADMIN_APPLICATIONS", null), items);
        });
    }

    public void adminApprove(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            presentation.openNotice(admin, presentation.dialogText("notice.no-permission-title"),
                    presentation.dialogText("notice.review-forbidden"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            String idempotencyKey = application.status() == ApplicationStatus.PROVISION_FAILED
                    ? "town:retry:" + application.id() + ":" + application.version()
                    : "town:approve:" + application.id();
            UUID progressSession = presentation.openDialogPage(admin, presentation.dialogText("provision.progress-title"),
                    List.of(DialogBody.plainMessage(
                            TownUiLegacyFacade.legacyComponent(presentation.dialogText("provision.progress-message")), 420)),
                    List.of(), DialogBase.DialogAfterAction.NONE,
                    session -> DialogType.notice(presentation.returnButton(admin, session,
                            new DialogRoute("ADMIN_APPLICATIONS", null))),
                    new DialogRoute("ADMIN_APPLICATIONS", null));
            java.util.concurrent.atomic.AtomicBoolean timedOut = new AtomicBoolean(false);
            plugin.runMainLater(() -> {
                if (facade.isCurrent(admin, progressSession) && timedOut.compareAndSet(false, true)) {
                    presentation.openNotice(admin, presentation.dialogText("provision.timeout-title"),
                            presentation.dialogText("provision.timeout-message"),
                            presentation.dialogText("provision.back-to-list"), "ADMIN_APPLICATIONS", null);
                }
            }, 20L * 30);
            runtime.provision(admin, application.id(), admin.getUniqueId(), admin.getName(),
                    plugin.messages().plainText("log.provision.admin-approval-reason"),
                    idempotencyKey, result -> {
                        ApplicationSnapshot completed = result.application();
                        if (completed != null) {
                            facade.notifyApplicationDecision(completed);
                        }
                        presentation.playSound(admin, result.status() == ProvisionResult.Status.SUCCESS
                                ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS);
                        if (timedOut.get() || !facade.isCurrent(admin, progressSession)) {
                            plugin.getLogger().info(plugin.messages().plainText(
                                    "log.provision.ui-callback-after-close",
                                    Map.of("application", applicationId, "time", Instant.now())));
                            return;
                        }
                        String message = result.status() == ProvisionResult.Status.SUCCESS
                                ? result.detail(plugin.messages())
                                : plugin.messages().rawText("dialog.provision.failure-with-recovery",
                                Map.of("detail", result.detail(plugin.messages()),
                                        "recovery", result.recoveryAction(plugin.messages())));
                        presentation.openNotice(admin,
                                result.status() == ProvisionResult.Status.SUCCESS
                                        ? presentation.dialogText("notice.application-created-title")
                                        : presentation.dialogText("provision.application-failed-title"),
                                message, presentation.dialogText("provision.back-to-list"),
                                "ADMIN_APPLICATIONS", null);
                    });
        });
    }

    public void recoverFailedApplication(Player admin, TownRepository.RecoveryMode mode,
                                  UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            presentation.openNotice(admin, presentation.dialogText("notice.no-permission-title"),
                    presentation.dialogText("notice.review-forbidden"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        presentation.openNotice(admin, presentation.dialogText("provision.recovery-title"),
                presentation.dialogText("provision.recovery-message"),
                presentation.dialogText("provision.back-to-list"), "ADMIN_APPLICATIONS", null);
        runtime.recoverFailedApplication(admin, applicationId, mode, result -> {
            if (result.application() != null) {
                facade.notifyApplicationDecision(result.application());
            }
            boolean success = result.status() == ProvisionResult.Status.SUCCESS;
            String message = success
                    ? mode == TownRepository.RecoveryMode.UNLOCK_FOR_CHANGES
                    ? presentation.dialogText("provision.recovery-unlocked-message")
                    : presentation.dialogText("provision.recovery-cancelled-message")
                    : plugin.messages().rawText("dialog.provision.failure-with-recovery",
                    Map.of("detail", result.detail(plugin.messages()),
                            "recovery", result.recoveryAction(plugin.messages())));
            presentation.openNotice(admin,
                    success ? presentation.dialogText("provision.recovery-completed-title")
                            : presentation.dialogText("provision.recovery-failed-title"),
                    message, presentation.dialogText("provision.back-to-list"),
                    "ADMIN_APPLICATIONS", null);
        });
    }

    public void beginAdminDecision(Player admin, UUID applicationId, boolean requestChanges) {
        openAdminDecisionDialog(admin, applicationId, requestChanges, "", null);
    }

    private void openAdminDecisionDialog(Player admin, UUID applicationId, boolean requestChanges,
                                         String initialReason, String error) {
        if (!admin.hasPermission("tianjitown.admin")) {
            presentation.openNotice(admin, presentation.dialogText("notice.no-permission-title"),
                    presentation.dialogText("notice.review-forbidden"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            Component explanation = presentation.dialogComponent(requestChanges
                            ? "review.change-heading" : "review.reject-heading")
                    .append(Component.newline())
                    .append(presentation.dialogComponent("common.town", Map.of(
                            "town", application.text().name())))
                    .append(Component.newline())
                    .append(presentation.dialogComponent(requestChanges
                            ? "review.change-guidance" : "review.reject-guidance"));
            if (error != null) {
                explanation = explanation.append(Component.newline()).append(Component.newline())
                        .append(presentation.dialogComponent("common.error", Map.of("error", error)));
            }
            DialogInput reasonInput = DialogInput.text("review_reason", 400,
                    presentation.dialogComponent(requestChanges ? "review.change-label" : "review.reject-label"),
                    true, initialReason, 500,
                    TextDialogInput.MultilineOptions.create(6, 110));
            presentation.openDialogPage(admin, requestChanges ? presentation.dialogText("review.change-title")
                            : presentation.dialogText("review.reject-title"),
                    List.of(DialogBody.plainMessage(explanation, 420)),
                    List.of(reasonInput), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.multiAction(List.of(
                                    ActionButton.create(presentation.dialogComponent(requestChanges
                                                    ? "review.send-change" : "review.confirm-reject"),
                                            presentation.dialogComponent("review.submit-tooltip"), 190,
                                            presentation.dialogAction(admin, session, response -> applyReviewReason(
                                                    admin, applicationId, requestChanges, response))),
                                    ActionButton.create(presentation.dialogComponent("common.cancel"),
                                            null, 150, presentation.dialogAction(admin, session,
                                                    "ADMIN_APPLICATION", applicationId.toString()))))
                            .exitAction(presentation.returnButton(admin, session,
                                    new DialogRoute("ADMIN_APPLICATION", applicationId.toString())))
                            .columns(2).build(),
                    new DialogRoute("ADMIN_APPLICATION", applicationId.toString()));
        });
    }

    private void applyReviewReason(Player admin, UUID applicationId, boolean requestChanges,
                                   DialogResponseView response) {
        if (!admin.hasPermission("tianjitown.admin")) {
            presentation.openNotice(admin, presentation.dialogText("notice.no-permission-title"),
                    presentation.dialogText("notice.review-not-executed"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        String reason = TownUiLegacyFacade.responseText(response, "review_reason");
        if (reason.isBlank() || reason.length() > 500) {
            openAdminDecisionDialog(admin, applicationId, requestChanges, reason,
                    reason.isBlank() ? presentation.dialogText("review.empty-error")
                            : presentation.dialogText("review.too-long-error"));
            return;
        }
        adminDecision(admin, applicationId, requestChanges, reason);
    }

    private void adminDecision(Player admin, UUID applicationId, boolean requestChanges,
                               String reason) {
        actions.reviewApplication(admin, applicationId, requestChanges, reason, outcome ->
                facade.handleOutcome(admin, outcome, application -> {
            facade.notifyApplicationDecision(application);
            presentation.openNotice(admin, requestChanges
                            ? presentation.dialogText("notice.review-change-sent-title")
                            : presentation.dialogText("notice.review-rejected-title"),
                    requestChanges ? presentation.dialogText("notice.review-change-sent-message")
                            : presentation.dialogText("notice.review-rejected-message"),
                    presentation.dialogText("common.back"), "ADMIN_APPLICATIONS", null);
        }));
    }

    public void adminPreviewSite(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            presentation.openNotice(admin, presentation.dialogText("notice.no-permission-title"),
                    presentation.dialogText("notice.preview-forbidden"), presentation.dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            if (application.territory() == null) {
                presentation.openNotice(admin, presentation.dialogText("notice.review-site-missing-title"),
                        presentation.dialogText("notice.review-site-missing-message"),
                        presentation.dialogText("common.back"), "ADMIN_APPLICATION",
                        applicationId.toString());
                return;
            }
            facade.closeUi(admin);
            territoryPreviews.teleportAndPreviewSilently(admin, application.territory());
        });
    }
}

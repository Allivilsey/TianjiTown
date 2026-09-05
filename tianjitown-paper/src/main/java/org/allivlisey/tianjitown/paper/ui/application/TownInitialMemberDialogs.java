package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.InitialMemberConfirmation;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sends initial-member invitations and handles replies and reminder cooldowns. */
public final class TownInitialMemberDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownInitialMemberDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void notifyInitialMembers(ApplicationSnapshot application) {
        for (InitialMemberConfirmation confirmation : application.initialMembers()) {
            if (confirmation.status() != InitialMemberConfirmation.Status.PENDING) {
                continue;
            }
            Player member = Bukkit.getPlayer(confirmation.playerId());
            if (member == null) {
                continue;
            }
            sendInitialMemberReminder(member, application);
        }
    }

    public void remindInitialMembers(Player applicant, UUID applicationId) {
        Instant now = Instant.now();
        Instant availableAt = facade.applicationFormUi().reminderAvailableAt(applicationId);
        if (availableAt != null && availableAt.isAfter(now)) {
            long remaining = Math.max(1, Duration.between(now, availableAt).toSeconds());
            presentation.openNotice(applicant, presentation.dialogText("notice.reminder-cooldown-title"), plugin.messages().text(
                            "application.reminder-cooldown", Map.of("seconds", remaining)),
                    presentation.dialogText("common.back"), "APPLICATION",
                    applicationId.toString());
            return;
        }
        runtime.read(applicant, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            if (!application.applicantId().equals(applicant.getUniqueId())) {
                presentation.openNotice(applicant, presentation.dialogText("notice.reminder-forbidden-title"),
                        presentation.dialogText("notice.reminder-forbidden-message"),
                        presentation.dialogText("common.back"), "MAIN", null);
                return;
            }
            if (application.initialMembers().stream().noneMatch(member ->
                    member.status() == InitialMemberConfirmation.Status.PENDING)) {
                presentation.openNotice(applicant, presentation.dialogText("notice.reminder-unneeded-title"),
                        presentation.dialogText("notice.reminder-unneeded-message"),
                        presentation.dialogText("common.back"), "APPLICATION",
                        applicationId.toString());
                return;
            }
            notifyInitialMembers(application);
            facade.applicationFormUi().setReminderAvailableAt(applicationId, applicant.getUniqueId(),
                    now.plus(Duration.ofMinutes(5)));
            presentation.openNotice(applicant, presentation.dialogText("notice.reminder-sent-title"),
                    plugin.messages().text("application.reminder-sent"),
                    presentation.dialogText("common.back"),
                    "APPLICATION", applicationId.toString());
        });
    }

    public void sendInitialMemberReminder(Player member, ApplicationSnapshot application) {
        Component message = presentation.dialogComponent("invitation.message", Map.of(
                "player", facade.displayName(application.applicantId()),
                "town", TownUiLegacyFacade.safeText(application.text().name())));
        presentation.openDialogPage(member, presentation.dialogText("invitation.title"),
                List.of(DialogBody.plainMessage(message, 400)), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.multiAction(List.of(
                                ActionButton.create(presentation.dialogComponent("invitation.accept"),
                                        null, 170, presentation.dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                        application.id(), true))),
                                ActionButton.create(presentation.dialogComponent("invitation.reject"),
                                        null, 170, presentation.dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                         application.id(), false)))))
                                .exitAction(presentation.returnButton(member, session, DialogRoute.ROOT))
                                .columns(2).build(), DialogRoute.ROOT);
    }

    private void respondInitialMember(Player member, UUID applicationId, boolean confirm) {
        actions.respondInitialMember(member, applicationId, confirm, outcome ->
                facade.handleOutcome(member, outcome, application -> {
                    presentation.openNotice(member, confirm
                                    ? presentation.dialogText("notice.invitation-accepted-title")
                                    : presentation.dialogText("notice.invitation-rejected-title"),
                            confirm ? presentation.dialogText("notice.invitation-accepted-message")
                                    : presentation.dialogText("notice.invitation-rejected-message"),
                            presentation.dialogText("common.close"), "CLOSE", null);
                    Player applicant = Bukkit.getPlayer(application.applicantId());
                    if (applicant != null) {
                        plugin.messages().send(applicant, confirm
                                        ? "chat.notification.initial-member-response-confirmed"
                                        : "chat.notification.initial-member-response-rejected",
                                Map.of("member", TownUiLegacyFacade.safeText(member.getName())));
                    }
                }));
    }
}

package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.runtime.TownActions;
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
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownInitialMemberDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
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
            facade.openNotice(applicant, facade.dialogText("notice.reminder-cooldown-title"), plugin.messages().text(
                            "application.reminder-cooldown", Map.of("seconds", remaining)),
                    facade.dialogText("common.back"), "APPLICATION",
                    applicationId.toString());
            return;
        }
        runtime.read(applicant, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            if (!application.applicantId().equals(applicant.getUniqueId())) {
                facade.openNotice(applicant, facade.dialogText("notice.reminder-forbidden-title"),
                        facade.dialogText("notice.reminder-forbidden-message"),
                        facade.dialogText("common.back"), "MAIN", null);
                return;
            }
            if (application.initialMembers().stream().noneMatch(member ->
                    member.status() == InitialMemberConfirmation.Status.PENDING)) {
                facade.openNotice(applicant, facade.dialogText("notice.reminder-unneeded-title"),
                        facade.dialogText("notice.reminder-unneeded-message"),
                        facade.dialogText("common.back"), "APPLICATION",
                        applicationId.toString());
                return;
            }
            notifyInitialMembers(application);
            facade.applicationFormUi().setReminderAvailableAt(applicationId, applicant.getUniqueId(),
                    now.plus(Duration.ofMinutes(5)));
            facade.openNotice(applicant, facade.dialogText("notice.reminder-sent-title"),
                    plugin.messages().text("application.reminder-sent"),
                    facade.dialogText("common.back"),
                    "APPLICATION", applicationId.toString());
        });
    }

    public void sendInitialMemberReminder(Player member, ApplicationSnapshot application) {
        Component message = facade.dialogComponent("invitation.message", Map.of(
                "player", facade.displayName(application.applicantId()),
                "town", TownUiLegacyFacade.safeText(application.text().name())));
        facade.openDialogPage(member, facade.dialogText("invitation.title"),
                List.of(DialogBody.plainMessage(message, 400)), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.multiAction(List.of(
                                ActionButton.create(facade.dialogComponent("invitation.accept"),
                                        null, 170, facade.dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                        application.id(), true))),
                                ActionButton.create(facade.dialogComponent("invitation.reject"),
                                        null, 170, facade.dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                         application.id(), false)))))
                                .exitAction(facade.returnButton(member, session, DialogRoute.ROOT))
                                .columns(2).build(), DialogRoute.ROOT);
    }

    private void respondInitialMember(Player member, UUID applicationId, boolean confirm) {
        actions.respondInitialMember(member, applicationId, confirm, outcome ->
                facade.handleOutcome(member, outcome, application -> {
                    facade.openNotice(member, confirm
                                    ? facade.dialogText("notice.invitation-accepted-title")
                                    : facade.dialogText("notice.invitation-rejected-title"),
                            confirm ? facade.dialogText("notice.invitation-accepted-message")
                                    : facade.dialogText("notice.invitation-rejected-message"),
                            facade.dialogText("common.close"), "CLOSE", null);
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

package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns administrator application-review routes, recovery targets, and notifications. */
public final class TownAdminApplicationUi {
    private final TownUiLegacyFacade facade;

    public TownAdminApplicationUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    public void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "ADMIN_APPLICATIONS" -> facade.openAdminApplications(player);
                case "ADMIN_APPLICATIONS_PAGE" -> facade.openAdminApplications(player,
                        Page.parse(target).page());
                case "ADMIN_APPLICATION" -> facade.openAdminApplication(player, uuid(target));
                case "CONFIRM_ADMIN_APPROVE" -> confirmApprove(player, uuid(target));
                case "CONFIRM_ADMIN_REJECT" -> facade.beginAdminDecision(player, uuid(target), false);
                case "CONFIRM_ADMIN_CHANGE" -> facade.beginAdminDecision(player, uuid(target), true);
                case "ADMIN_APPROVE" -> facade.adminApprove(player, uuid(target));
                case "CONFIRM_FAILED_RECOVERY" -> confirmRecovery(player, RecoveryTarget.parse(target));
                case "RECOVER_FAILED" -> recover(player, RecoveryTarget.parse(target));
                case "ADMIN_PREVIEW_SITE" -> facade.adminPreviewSite(player, uuid(target));
                default -> throw new IllegalArgumentException("unsupported admin application action: " + action);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    public void showList(CommandSender sender, List<ApplicationSnapshot> applications) {
        facade.plugin().messages().send(sender, "chat.admin.pending-applications",
                Map.of("count", applications.size()));
        for (ApplicationSnapshot application : applications) {
            facade.plugin().messages().send(sender, "chat.admin.application-entry", Map.of(
                    "town", application.text().name(), "applicant", application.applicantId()));
            if (sender instanceof Player admin) {
                admin.sendMessage(facade.callbackButton(admin, "chat.buttons.review-application",
                        () -> facade.openAdminApplication(admin, application.id())));
            }
        }
        if (sender instanceof Player player && !applications.isEmpty()) {
            facade.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
        }
    }

    public void notifyDecision(ApplicationSnapshot application) {
        Player applicant = Bukkit.getPlayer(application.applicantId());
        if (applicant == null) {
            return;
        }
        switch (application.status()) {
            case ACTIVE -> {
                applicant.sendMessage(facade.plugin().messages().component(
                        "chat.notification.application-approved",
                        Map.of("town", application.text().name())));
                facade.playSound(applicant, Sound.ENTITY_PLAYER_LEVELUP);
            }
            case NEED_CHANGES -> {
                applicant.sendMessage(facade.plugin().messages().component(
                                "chat.notification.application-needs-changes", Map.of(
                                        "reason", TownUiLegacyFacade.safeText(Objects.requireNonNullElse(
                                                application.reviewMessage(), facade.plugin().messages().plainText(
                                                        "chat.notification.application-needs-changes-default-reason")))))
                        .append(facade.callbackButton(applicant, "chat.buttons.edit-application",
                                () -> facade.loadApplication(applicant, application.id()))));
                facade.playSound(applicant, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            case REJECTED -> {
                applicant.sendMessage(facade.plugin().messages().component(
                                "chat.notification.application-rejected", Map.of(
                                        "reason", TownUiLegacyFacade.safeText(Objects.requireNonNullElse(
                                                application.reviewMessage(), facade.plugin().messages().plainText(
                                                        "chat.notification.application-rejected-default-reason")))))
                        .append(facade.callbackButton(applicant, "chat.buttons.open-system",
                                () -> facade.openMain(applicant))));
                facade.playSound(applicant, Sound.ENTITY_VILLAGER_NO);
            }
            case PROVISION_FAILED -> {
                applicant.sendMessage(facade.plugin().messages().component(
                                "chat.notification.application-provision-failed")
                        .append(facade.callbackButton(applicant, "chat.buttons.open-system",
                                () -> facade.openMain(applicant))));
                facade.playSound(applicant, Sound.BLOCK_NOTE_BLOCK_BASS);
            }
            default -> {
                // Other statuses are not review decisions requiring a notification.
            }
        }
    }

    private void confirmApprove(Player player, UUID applicationId) {
        facade.openConfirmation(player, facade.dialogText("confirmation.admin-approve-title"),
                "ADMIN_APPROVE", applicationId.toString(),
                facade.dialogText("confirmation.admin-approve-consequence"),
                "ADMIN_APPLICATION", applicationId.toString());
    }

    private void confirmRecovery(Player player, RecoveryTarget recovery) {
        facade.openConfirmation(player, facade.dialogText("confirmation.failed-recovery-title"),
                "RECOVER_FAILED", recovery.encode(),
                facade.dialogText("confirmation.failed-recovery-consequence"),
                "ADMIN_APPLICATION", recovery.applicationId().toString());
    }

    private void recover(Player player, RecoveryTarget recovery) {
        facade.recoverFailedApplication(player, recovery.mode(), recovery.applicationId());
    }

    private static UUID uuid(String target) {
        if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid UUID target");
        }
        return UUID.fromString(target);
    }

    public record Page(int page) {
        static Page parse(String target) {
            int page = Integer.parseInt(target);
            if (page < 0) {
                throw new IllegalArgumentException("negative page");
            }
            return new Page(page);
        }
    }

    public record RecoveryTarget(TownRepository.RecoveryMode mode, UUID applicationId) {
        static RecoveryTarget parse(String target) {
            String[] values = target == null ? new String[0] : target.split(":", -1);
            if (values.length != 2) {
                throw new IllegalArgumentException("invalid recovery target");
            }
            return new RecoveryTarget(TownRepository.RecoveryMode.valueOf(values[0]),
                    UUID.fromString(values[1]));
        }

        String encode() {
            return mode + ":" + applicationId;
        }
    }
}

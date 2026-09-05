package org.allivlisey.tianjitown.paper.action;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.land.SitePolicy;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.paper.action.TownActionSupport.*;
import static org.allivlisey.tianjitown.paper.action.TownActionSupport.*;

final class TownApplicationActions {
    private final TownActionSupport support;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownApplicationActions(TownActionSupport support) {
        this.support = support;
        this.plugin = support.plugin;
        this.runtime = support.runtime;
    }

    public void createApplication(Player actor, ApplicationText text, List<UUID> initialMemberIds,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        if (!support.validateApplicationText("APPLICATION_CREATE", text, completion)) {
            return;
        }
        Duration cooldown = Duration.ofHours(plugin.getConfig()
                .getLong("town.application.cooldown-hours", 24));
        support.write("APPLICATION_CREATE", actor,
                () -> runtime.repository().createDraft(actor.getUniqueId(), text,
                        initialMemberIds, cooldown),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

    public void updateApplication(Player actor, UUID applicationId, ApplicationText text,
                           List<UUID> initialMemberIds, long expectedVersion,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        if (!support.validateApplicationText("APPLICATION_UPDATE", text, completion)) {
            return;
        }
        support.write("APPLICATION_UPDATE", actor,
                () -> runtime.repository().updateApplicationText(applicationId,
                        actor.getUniqueId(), text, initialMemberIds, expectedVersion),
                application -> Map.of("application_id", application.id(),
                        "status", application.status(), "version", application.version()),
                completion);
    }

    public void respondInitialMember(Player actor, UUID applicationId, boolean confirm,
                              Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        support.write("INITIAL_MEMBER_RESPONSE", actor,
                () -> runtime.repository().respondInitialMember(applicationId,
                        actor.getUniqueId(), confirm),
                application -> Map.of("application_id", application.id(),
                        "confirmed", confirm), completion);
    }

    public void selectApplicationSite(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        String action = "APPLICATION_SELECT_SITE";
        if (support.rejectBeforeWrite(action, completion)) {
            return;
        }
        SitePolicy.Validation validation = runtime.sitePolicy().validate(actor);
        if (!validation.valid()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "SITE_INVALID", Map.of("detail", validation.error()))));
            return;
        }
        long minutes = plugin.getConfig().getLong("town.application.reservation-minutes", 60);
        int buffer = plugin.getConfig().getInt("town.site.minimum-buffer-chunks", 1);
        runtime.readAction(actor, () -> runtime.repository().findApplication(applicationId)
                        .orElseThrow(ApplicationNotFoundException::new),
                application -> {
                    LandProtectionService.Collision nameCollision = runtime.landProtection()
                            .findNameCollision(application.text().normalizedResidenceName());
                    if (nameCollision.code() != null) {
                        completion.accept(TownActionOutcome.failure(TownActionResult.failure(
                                action, "RESIDENCE_UNAVAILABLE", Map.of("detail",
                                        LandProtectionMessages.detail(plugin.messages(),
                                                nameCollision)))));
                        return;
                    }
                    if (nameCollision.occupied()) {
                        completion.accept(TownActionOutcome.failure(TownActionResult.failure(
                                action, "RESIDENCE_NAME_CONFLICT", Map.of("detail",
                                        plugin.messages().plainText(RESIDENCE_NAME_CONFLICT)))));
                        return;
                    }
                    support.writeUnchecked(action, actor,
                            () -> runtime.repository().selectSite(applicationId,
                                    actor.getUniqueId(), validation.territory(),
                                    Instant.now().plusSeconds(minutes * 60), buffer),
                            selected -> Map.of("application_id", selected.id(),
                                    "status", selected.status(), "expires_at",
                                    selected.reservationExpiresAt()), completion);
                }, exception -> completion.accept(TownActionOutcome.failure(
                        support.actionFailure(action, exception))));
    }

    public void submitApplication(Player actor, UUID applicationId,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        support.write("APPLICATION_SUBMIT", actor,
                () -> runtime.repository().submit(applicationId, actor.getUniqueId()),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

    public void cancelApplication(Player actor, UUID applicationId,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        support.write("APPLICATION_CANCEL", actor,
                () -> runtime.repository().cancel(applicationId, actor.getUniqueId(),
                        "玩家通过共享业务入口撤回"),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

    public void reviewApplication(Player actor, UUID applicationId, boolean requestChanges,
                           String reason,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        String action = requestChanges ? "ADMIN_APPLICATION_REQUEST_CHANGES"
                : "ADMIN_APPLICATION_REJECT";
        if (!actor.hasPermission("tianjitown.admin")) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FORBIDDEN")));
            return;
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            String reasonMessage = reason == null || reason.isBlank()
                    ? plugin.messages().plainText(REVIEW_REASON_EMPTY)
                    : plugin.messages().plainText(REVIEW_REASON_TOO_LONG);
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("detail", reasonMessage))));
            return;
        }
        support.write(action, actor, () -> requestChanges
                        ? runtime.repository().requestChanges(applicationId, actor.getUniqueId(),
                        actor.getName(), reason)
                        : runtime.repository().reject(applicationId, actor.getUniqueId(),
                        actor.getName(), reason),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

}

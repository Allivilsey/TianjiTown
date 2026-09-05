package org.allivlisey.tianjitown.paper.runtime;
import org.allivlisey.tianjitown.paper.message.ApplicationTextMessages;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.TransferSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class TownActionSupport {
    static final String APPLICATION_NOT_FOUND = "chat.application.not-found";
    static final String RESIDENCE_NAME_CONFLICT =
            "chat.site-validation.residence-name-conflict";
    static final String REVIEW_REASON_EMPTY = "dialog.review.empty-error";
    static final String REVIEW_REASON_TOO_LONG = "dialog.review.too-long-error";
    static final String DONATION_AMOUNT_POSITIVE =
            "validation.vault.donation-amount-positive";
    static final String RESIDENCE_MEMBER_SYNC_FAILURE =
            "log.residence.member-sync-failure";

    final TianjiTownPlugin plugin;
    final TownRuntime runtime;

    TownActionSupport(TianjiTownPlugin plugin, TownRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    Duration durationHours(String name, long fallback) {
        return Duration.ofHours(plugin.getConfig().getLong(
                "town.membership." + name, fallback));
    }

    void syncResidence(Player actor, UUID townId) {
        runtime.readAction(actor, () -> new TownMembers(runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在")),
                        runtime.repository().listLandAccessIds(townId)), state ->
                        runtime.reconcileAction(actor, state.town(), state.members(), true,
                                result -> {
                                    if (!result.success()) {
                                        plugin.getLogger().warning(residenceSyncFailure(townId,
                                                LandProtectionMessages.detail(
                                                        plugin.messages(), result)));
                                    }
                                }, exception -> plugin.getLogger().warning(
                                        residenceSyncFailure(townId,
                                                TownActionFailures.safeMessage(exception)))),
                exception -> plugin.getLogger().warning(residenceSyncFailure(townId,
                        TownActionFailures.safeMessage(exception))));
    }

    <T> void write(String action, Player actor, Supplier<T> operation,
                           Function<T, Map<String, ?>> resultData,
                           Consumer<TownActionOutcome<T>> completion) {
        if (rejectBeforeWrite(action, completion)) {
            return;
        }
        writeUnchecked(action, actor, operation, resultData, completion);
    }

    <T> void writeUnchecked(String action, Player actor, Supplier<T> operation,
                                    Function<T, Map<String, ?>> resultData,
                                    Consumer<TownActionOutcome<T>> completion) {
        runtime.writeAction(actor, operation, value -> completion.accept(
                        TownActionOutcome.success(TownActionResult.success(action,
                                resultData.apply(value)), value)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception, plugin.messages()))));
    }

    <T> boolean rejectBeforeWrite(String action,
                                          Consumer<TownActionOutcome<T>> completion) {
        if (plugin.getConfig().getBoolean("town.maintenance-mode", false)) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "MAINTENANCE_MODE")));
            return true;
        }
        if (!runtime.databaseAvailable()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "STORAGE_UNAVAILABLE")));
            return true;
        }
        return false;
    }

    static <T> void failNow(String action, RuntimeException exception,
                                    Consumer<TownActionOutcome<T>> completion) {
        completion.accept(TownActionOutcome.failure(TownActionFailures.from(action, exception)));
    }

    TownActionResult actionFailure(String action, RuntimeException exception) {
        if (exception instanceof ApplicationNotFoundException) {
            return TownActionResult.failure(action, "NOT_FOUND",
                    Map.of("detail", plugin.messages().plainText(APPLICATION_NOT_FOUND)));
        }
        return TownActionFailures.from(action, exception, plugin.messages());
    }

    String residenceSyncFailure(UUID townId, String detail) {
        return plugin.messages().plainText(RESIDENCE_MEMBER_SYNC_FAILURE,
                Map.of("town", townId, "detail", safeText(detail)));
    }

    static String safeText(String value) {
        return value == null ? "" : value.replace('&', '＆').replace('§', '�');
    }

    <T> boolean validateApplicationText(String action, ApplicationText text,
                                                Consumer<TownActionOutcome<T>> completion) {
        List<ApplicationText.ValidationIssue> issues = text.validate();
        if (issues.isEmpty()) {
            return true;
        }
        completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                "VALIDATION_FAILED", Map.of("detail",
                        ApplicationTextMessages.join(plugin.messages(), issues)))));
        return false;
    }


    static Map<String, ?> joinData(JoinApplicationSnapshot application) {
        return Map.of("application_id", application.id(), "town_id", application.townId(),
                "applicant_id", application.applicantId(), "status", application.status());
    }

    static Map<String, ?> transferData(TransferSnapshot transfer) {
        return Map.of("transfer_id", transfer.id(), "town_id", transfer.townId(),
                "candidate_id", transfer.candidateId(), "status", transfer.status());
    }

    static Map<String, ?> voteData(VoteSnapshot vote) {
        return Map.of("vote_id", vote.id(), "town_id", vote.townId(), "status", vote.status(),
                "yes_votes", vote.yesVotes(), "no_votes", vote.noVotes(),
                "required_yes", vote.requiredYes());
    }

    static final class ApplicationNotFoundException extends RuntimeException {
    }

    @FunctionalInterface
    interface ExpansionExecutor {
        void execute(Consumer<EconomyRepository.ExpansionOperation> success,
                     Consumer<RuntimeException> failure);
    }

    record TownMembers(TownSnapshot town, List<UUID> members) {
    }
}

package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.core.governance.VoteType;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.storage.town.ApplicationSnapshot;
import cn.tianji.town.storage.town.JoinApplicationSnapshot;
import cn.tianji.town.storage.town.TownRepository;
import cn.tianji.town.storage.town.TownSnapshot;
import cn.tianji.town.storage.governance.MemberGovernanceSnapshot;
import cn.tianji.town.storage.governance.TransferSnapshot;
import cn.tianji.town.storage.governance.VoteSnapshot;
import cn.tianji.town.storage.economy.EconomyRepository;
import cn.tianji.town.storage.commerce.CommerceRepository;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 玩家界面的共享业务入口。此类不负责页面跳转或玩家可见文案。
 */
final class TownActions {
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;

    TownActions(TianjiTownPlugin plugin, TownRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
    }

    void createApplication(Player actor, ApplicationText text, List<UUID> initialMemberIds,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        if (!validateText("APPLICATION_CREATE", text, completion)) {
            return;
        }
        Duration cooldown = Duration.ofHours(plugin.getConfig()
                .getLong("town.application.cooldown-hours", 24));
        write("APPLICATION_CREATE", actor,
                () -> runtime.repository().createDraft(actor.getUniqueId(), text,
                        initialMemberIds, cooldown),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

    void updateApplication(Player actor, UUID applicationId, ApplicationText text,
                           List<UUID> initialMemberIds, long expectedVersion,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        if (!validateText("APPLICATION_UPDATE", text, completion)) {
            return;
        }
        write("APPLICATION_UPDATE", actor,
                () -> runtime.repository().updateApplicationText(applicationId,
                        actor.getUniqueId(), text, initialMemberIds, expectedVersion),
                application -> Map.of("application_id", application.id(),
                        "status", application.status(), "version", application.version()),
                completion);
    }

    void respondInitialMember(Player actor, UUID applicationId, boolean confirm,
                              Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        write("INITIAL_MEMBER_RESPONSE", actor,
                () -> runtime.repository().respondInitialMember(applicationId,
                        actor.getUniqueId(), confirm),
                application -> Map.of("application_id", application.id(),
                        "confirmed", confirm), completion);
    }

    void selectApplicationSite(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        String action = "APPLICATION_SELECT_SITE";
        if (rejectBeforeWrite(action, completion)) {
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
        writeUnchecked(action, actor, () -> runtime.repository().selectSite(applicationId,
                        actor.getUniqueId(), validation.territory(),
                        Instant.now().plusSeconds(minutes * 60), buffer),
                application -> Map.of("application_id", application.id(),
                        "status", application.status(), "expires_at",
                        application.reservationExpiresAt()), completion);
    }

    void submitApplication(Player actor, UUID applicationId,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        write("APPLICATION_SUBMIT", actor,
                () -> runtime.repository().submit(applicationId, actor.getUniqueId()),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

    void cancelApplication(Player actor, UUID applicationId,
                           Consumer<TownActionOutcome<ApplicationSnapshot>> completion) {
        write("APPLICATION_CANCEL", actor,
                () -> runtime.repository().cancel(applicationId, actor.getUniqueId(),
                        "玩家通过共享业务入口撤回"),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

    void reviewApplication(Player actor, UUID applicationId, boolean requestChanges,
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
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("detail", "审核原因必须为 1-500 个字符"))));
            return;
        }
        write(action, actor, () -> requestChanges
                        ? runtime.repository().requestChanges(applicationId, actor.getUniqueId(),
                        actor.getName(), reason)
                        : runtime.repository().reject(applicationId, actor.getUniqueId(),
                        actor.getName(), reason),
                application -> Map.of("application_id", application.id(),
                        "status", application.status()), completion);
    }

    void updateTownProfile(Player actor, UUID townId, ApplicationText profile,
                           long expectedVersion,
                           Consumer<TownActionOutcome<TownSnapshot>> completion) {
        if (!validateText("TOWN_PROFILE_UPDATE", profile, completion)) {
            return;
        }
        write("TOWN_PROFILE_UPDATE", actor,
                () -> runtime.repository().updateTownProfile(townId, profile, expectedVersion,
                        actor.getUniqueId(), actor.getName(), "管理组通过共享业务入口修改资料"),
                town -> Map.of("town_id", town.id(), "version", town.version(),
                        "rules_revision", town.rulesRevision()), completion);
    }

    void applyToTown(Player actor, UUID townId,
                     Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        Duration lifetime = durationHours("application-lifetime-hours", 48);
        Duration rejectionCooldown = durationHours("rejection-cooldown-hours", 24);
        Duration leaveCooldown = durationHours("leave-cooldown-hours", 24);
        int maximumPending = plugin.getConfig().getInt(
                "town.membership.maximum-pending-applications", 3);
        write("JOIN_APPLY", actor, () -> runtime.repository().applyToTown(townId,
                        actor.getUniqueId(), lifetime, rejectionCooldown, leaveCooldown,
                        maximumPending),
                application -> joinData(application), completion);
    }

    void cancelJoinApplication(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        write("JOIN_CANCEL", actor,
                () -> runtime.repository().cancelJoinApplication(applicationId,
                        actor.getUniqueId()), TownActions::joinData, completion);
    }

    void approveJoinApplication(Player actor, UUID applicationId,
                                Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        write("JOIN_APPROVE", actor,
                () -> runtime.repository().approveJoinApplication(applicationId,
                        actor.getUniqueId()), application -> {
                    syncResidence(actor, application.townId());
                    return joinData(application);
                }, completion);
    }

    void rejectJoinApplication(Player actor, UUID applicationId,
                               Consumer<TownActionOutcome<JoinApplicationSnapshot>> completion) {
        write("JOIN_REJECT", actor,
                () -> runtime.repository().rejectJoinApplication(applicationId,
                        actor.getUniqueId()), TownActions::joinData, completion);
    }

    void changeMemberRole(Player actor, UUID townId, UUID targetId, MemberRole role,
                          Consumer<TownActionOutcome<MemberRole>> completion) {
        write("MEMBER_ROLE_CHANGE", actor,
                () -> runtime.governance().changeRoleByMayor(townId, targetId, role,
                        actor.getUniqueId(), actor.getName()), changed -> {
                    syncResidence(actor, townId);
                    return Map.of("town_id", townId, "target_id", targetId, "role", changed);
                }, completion);
    }

    void kickMember(Player actor, UUID townId, UUID targetId,
                    Consumer<TownActionOutcome<UUID>> completion) {
        write("MEMBER_KICK", actor, () -> {
            runtime.governance().removeMemberByMayor(townId, targetId, actor.getUniqueId(),
                    actor.getName());
            return townId;
        }, changedTown -> {
            Player removed = plugin.getServer().getPlayer(targetId);
            if (removed != null) {
                runtime.buffs().refreshPlayer(removed);
            }
            syncResidence(actor, changedTown);
            return Map.of("town_id", changedTown, "target_id", targetId);
        }, completion);
    }

    void addVisitor(Player actor, UUID townId, UUID targetId,
                    Consumer<TownActionOutcome<TownSnapshot.Visitor>> completion) {
        write("VISITOR_ADD", actor,
                () -> runtime.repository().addVisitor(townId, targetId,
                        actor.getUniqueId(), actor.getName()), visitor -> {
                    syncResidence(actor, townId);
                    return Map.of("town_id", townId, "target_id", targetId);
                }, completion);
    }

    void removeVisitor(Player actor, UUID townId, UUID targetId,
                       Consumer<TownActionOutcome<UUID>> completion) {
        write("VISITOR_REMOVE", actor,
                () -> runtime.repository().removeVisitor(townId, targetId,
                        actor.getUniqueId(), actor.getName()), removed -> {
                    syncResidence(actor, townId);
                    return Map.of("town_id", townId, "target_id", removed);
                }, completion);
    }

    void requestMayorTransfer(Player actor, UUID townId, UUID candidateId,
                              Consumer<TownActionOutcome<TransferSnapshot>> completion) {
        GovernanceSettings settings;
        try {
            settings = GovernanceSettings.load(plugin.getConfig());
        } catch (IllegalArgumentException exception) {
            failNow("MAYOR_TRANSFER_REQUEST", exception, completion);
            return;
        }
        write("MAYOR_TRANSFER_REQUEST", actor,
                () -> runtime.governance().requestMayorTransfer(townId, candidateId,
                        actor.getUniqueId(), settings.transferConfirmation()),
                transfer -> transferData(transfer), completion);
    }

    void decideMayorTransfer(Player actor, UUID transferId, boolean accept,
                             Consumer<TownActionOutcome<TransferSnapshot>> completion) {
        write("MAYOR_TRANSFER_DECIDE", actor,
                () -> runtime.governance().decideMayorTransfer(transferId,
                        actor.getUniqueId(), accept), transfer -> {
                    if (accept) {
                        syncResidence(actor, transfer.townId());
                    }
                    return transferData(transfer);
                }, completion);
    }

    void acknowledgeRules(Player actor, UUID townId, long revision,
                          Consumer<TownActionOutcome<Long>> completion) {
        write("RULES_ACKNOWLEDGE", actor, () -> {
            runtime.governance().acknowledgeRules(townId, actor.getUniqueId(), revision);
            return revision;
        }, confirmed -> Map.of("town_id", townId, "revision", confirmed), completion);
    }

    void createVote(Player actor, UUID townId, VoteType type, UUID targetId,
                    Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        GovernanceSettings settings;
        try {
            settings = GovernanceSettings.load(plugin.getConfig());
        } catch (IllegalArgumentException exception) {
            failNow("VOTE_CREATE", exception, completion);
            return;
        }
        write("VOTE_CREATE", actor,
                () -> runtime.governance().createVote(townId, type, targetId,
                        actor.getUniqueId(), settings.activeMemberWindow(),
                        settings.minimumMembership(), settings.voteDuration(), false),
                TownActions::voteData, completion);
    }

    void castVote(Player actor, UUID voteId, boolean approve,
                  Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        write("VOTE_CAST", actor,
                () -> runtime.governance().castVote(voteId, actor.getUniqueId(), approve),
                vote -> {
                    if (vote.passed() && vote.type() == VoteType.KICK_MEMBER) {
                        syncResidence(actor, vote.townId());
                    }
                    return voteData(vote);
                }, completion);
    }

    void cancelOwnVote(Player actor, UUID voteId,
                       Consumer<TownActionOutcome<VoteSnapshot>> completion) {
        write("VOTE_CANCEL", actor,
                () -> runtime.governance().cancelOwnVote(voteId, actor.getUniqueId(),
                        actor.getName()), TownActions::voteData, completion);
    }

    void leaveTown(Player actor, UUID townId,
                   Consumer<TownActionOutcome<UUID>> completion) {
        write("TOWN_LEAVE", actor, () -> {
            runtime.repository().leaveTown(actor.getUniqueId());
            return townId;
        }, changedTown -> {
            runtime.buffs().refreshPlayer(actor);
            syncResidence(actor, changedTown);
            return Map.of("town_id", changedTown, "player_id", actor.getUniqueId());
        }, completion);
    }

    void disbandTown(Player actor, UUID townId, long expectedVersion,
                     Consumer<TownActionOutcome<TownSnapshot>> completion) {
        String action = "TOWN_DISBAND";
        if (rejectBeforeWrite(action, completion)) {
            return;
        }
        writeUnchecked(action, actor,
                () -> runtime.repository().disbandTown(townId, actor.getUniqueId(),
                        expectedVersion), town -> Map.of(), archived -> {
                    if (!archived.result().success()) {
                        completion.accept(archived);
                        return;
                    }
                    TownSnapshot town = archived.value();
                    LandProtectionService.Result removed;
                    try {
                        removed = runtime.landProtection().remove(town.residenceName(),
                                town.territory());
                    } catch (RuntimeException | LinkageError exception) {
                        completion.accept(TownActionOutcome.failure(TownActionResult.failure(
                                action, "LAND_PROTECTION_FAILED",
                                Map.of("town_id", town.id(), "archived", true, "detail",
                                        TownActionFailures.safeMessage(exception)))));
                        return;
                    }
                    if (!removed.success()) {
                        completion.accept(TownActionOutcome.failure(TownActionResult.failure(
                                action, "LAND_PROTECTION_FAILED", Map.of("town_id", town.id(),
                                        "archived", true, "detail", removed.message()))));
                        return;
                    }
                    writeUnchecked(action, actor, () -> {
                        runtime.repository().completeTownDeletion(town.id(), actor.getUniqueId(),
                                actor.getName(), "镇长通过共享业务入口解散");
                        return town;
                    }, completed -> {
                        runtime.buffs().refreshAllPlayers();
                        return Map.of("town_id", completed.id(), "status", "DELETED");
                    }, completion);
                });
    }

    void changeTaxRate(Player actor, UUID townId, int basisPoints,
                       Consumer<TownActionOutcome<EconomyRepository.TaxChange>> completion) {
        String action = "TAX_RATE_CHANGE";
        if (!runtime.taxEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        if (!runtime.economySettings().allowsTaxRate(basisPoints)) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("maximum_bps",
                            runtime.economySettings().maximumTaxBps()))));
            return;
        }
        write(action, actor, () -> {
            EconomyRepository.TaxChange change = runtime.finance().changeTaxRate(townId,
                    actor.getUniqueId(), basisPoints, actor.getName(),
                    "镇长通过共享业务入口修改");
            runtime.refreshTaxPolicies();
            return change;
        }, change -> Map.of("town_id", change.townId(), "basis_points",
                change.basisPoints(), "revision", change.revision()), completion);
    }

    void acknowledgeTaxRevision(Player actor, int revision,
                                Consumer<TownActionOutcome<Integer>> completion) {
        write("TAX_REVISION_ACKNOWLEDGE", actor, () -> {
            runtime.finance().acknowledgeTaxRevision(actor.getUniqueId(), revision);
            return revision;
        }, confirmed -> Map.of("player_id", actor.getUniqueId(), "revision", confirmed),
                completion);
    }

    void donate(Player actor, long amountMinor,
                Consumer<TownActionOutcome<EconomyRepository.LedgerMutation>> completion) {
        String action = "TOWN_DONATE";
        if (rejectBeforeWrite(action, completion)) {
            return;
        }
        if (amountMinor <= 0) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("detail", "捐款金额必须大于 0"))));
            return;
        }
        if (!runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        runtime.donateAction(actor, amountMinor, mutation -> completion.accept(
                        TownActionOutcome.success(TownActionResult.success(action,
                                Map.of("town_id", mutation.townId(), "amount_minor", amountMinor,
                                        "balance_minor", mutation.balanceAfterMinor())), mutation)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

    void expandTown(Player actor, ExpansionDirection direction,
                    Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        expandTown(actor, Map.of("direction", direction),
                (success, failure) -> runtime.expandAction(actor, direction, success, failure),
                completion);
    }

    void expandTown(Player actor, int gridX, int gridZ,
                    Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        expandTown(actor, Map.of("grid_x", gridX, "grid_z", gridZ),
                (success, failure) -> runtime.expandAction(
                        actor, gridX, gridZ, success, failure), completion);
    }

    private void expandTown(Player actor, Map<String, ?> targetData,
                            ExpansionExecutor executor,
                            Consumer<TownActionOutcome<EconomyRepository.ExpansionOperation>> completion) {
        String action = "TOWN_EXPAND";
        if (rejectBeforeWrite(action, completion)) {
            return;
        }
        if (!runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        executor.execute(operation -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("town_id", operation.townId());
            result.put("expansion_id", operation.expansionId());
            result.putAll(targetData);
            result.put("price_minor", operation.priceMinor());
            completion.accept(TownActionOutcome.success(
                    TownActionResult.success(action, result), operation));
        },
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

    void buyBuff(Player actor, String buffKey, BuffDurationOption duration,
                 Consumer<TownActionOutcome<CommerceRepository.BuffPurchase>> completion) {
        String action = "BUFF_BUY";
        if (rejectBeforeWrite(action, completion)) {
            return;
        }
        if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        runtime.buffs().buyBuffAction(actor, buffKey, duration, purchase -> completion.accept(
                        TownActionOutcome.success(TownActionResult.success(action,
                                Map.of("buff_key", buffKey, "buff_id", purchase.buff().buffId(),
                                        "level", purchase.buff().level(), "expires_at",
                                        purchase.buff().expiresAt(), "balance_minor",
                                        purchase.balanceAfterMinor())), purchase)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

    void buyBuff(Player actor, String buffKey, int weeks, int level,
                 Consumer<TownActionOutcome<CommerceRepository.BuffPurchase>> completion) {
        String action = "BUFF_BUY";
        if (rejectBeforeWrite(action, completion)) {
            return;
        }
        if (!runtime.buffs().buffShopEnabled() || !runtime.consumptionEnabled()) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "FEATURE_DISABLED")));
            return;
        }
        runtime.buffs().buyBuffAction(actor, buffKey, weeks, level,
                purchase -> completion.accept(TownActionOutcome.success(
                        TownActionResult.success(action, Map.of("buff_key", buffKey,
                                "buff_id", purchase.buff().buffId(), "level", level,
                                "weeks", weeks, "expires_at", purchase.buff().expiresAt(),
                                "balance_minor", purchase.balanceAfterMinor())), purchase)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

    void queryActor(Player actor, Consumer<TownActionOutcome<Void>> completion) {
        String action = "QUERY_ACTOR";
        runtime.readAction(actor, () -> {
            TownRepository.PlayerDashboard dashboard = runtime.repository()
                    .dashboard(actor.getUniqueId());
            MemberGovernanceSnapshot governance = runtime.governance()
                    .dashboard(actor.getUniqueId()).orElse(null);
            EconomyRepository.TownFinance finance = runtime.finance()
                    .findFinanceByPlayer(actor.getUniqueId()).orElse(null);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("player_id", actor.getUniqueId());
            values.put("town_id", dashboard.town() == null ? "NONE" : dashboard.town().id());
            values.put("town_status", dashboard.town() == null
                    ? "NONE" : dashboard.town().status());
            values.put("role", governance == null ? "NONE" : governance.role());
            values.put("application_id", dashboard.application() == null
                    ? "NONE" : dashboard.application().id());
            values.put("application_status", dashboard.application() == null
                    ? "NONE" : dashboard.application().status());
            values.put("pending_join_count", dashboard.joinApplications().size());
            values.put("balance_minor", finance == null ? "NONE" : finance.balanceMinor());
            return values;
        }, values -> completion.accept(TownActionOutcome.success(
                TownActionResult.success(action, values), null)), exception ->
                completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

    private Duration durationHours(String name, long fallback) {
        return Duration.ofHours(plugin.getConfig().getLong(
                "town.membership." + name, fallback));
    }

    private void syncResidence(Player actor, UUID townId) {
        runtime.readAction(actor, () -> new TownMembers(runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在")),
                        runtime.repository().listLandAccessIds(townId)), state ->
                        runtime.reconcileAction(actor, state.town(), state.members(), true,
                                result -> {
                                    if (!result.success()) {
                                        plugin.getLogger().warning(
                                                "同步 Residence 成员失败 town=" + townId
                                                        + ": " + result.message());
                                    }
                                }, exception -> plugin.getLogger().warning(
                                        "同步 Residence 成员失败 town=" + townId + ": "
                                                + TownActionFailures.safeMessage(exception))),
                exception -> plugin.getLogger().warning("同步 Residence 成员失败 town=" + townId
                        + ": " + TownActionFailures.safeMessage(exception)));
    }

    private <T> void write(String action, Player actor, Supplier<T> operation,
                           Function<T, Map<String, ?>> resultData,
                           Consumer<TownActionOutcome<T>> completion) {
        if (rejectBeforeWrite(action, completion)) {
            return;
        }
        writeUnchecked(action, actor, operation, resultData, completion);
    }

    private <T> void writeUnchecked(String action, Player actor, Supplier<T> operation,
                                    Function<T, Map<String, ?>> resultData,
                                    Consumer<TownActionOutcome<T>> completion) {
        runtime.writeAction(actor, operation, value -> completion.accept(
                        TownActionOutcome.success(TownActionResult.success(action,
                                resultData.apply(value)), value)),
                exception -> completion.accept(TownActionOutcome.failure(
                        TownActionFailures.from(action, exception))));
    }

    private <T> boolean rejectBeforeWrite(String action,
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

    private static <T> void failNow(String action, RuntimeException exception,
                                    Consumer<TownActionOutcome<T>> completion) {
        completion.accept(TownActionOutcome.failure(TownActionFailures.from(action, exception)));
    }

    static <T> boolean validateText(String action, ApplicationText text,
                                    Consumer<TownActionOutcome<T>> completion) {
        try {
            text.requireValid();
            return true;
        } catch (IllegalArgumentException exception) {
            completion.accept(TownActionOutcome.failure(TownActionResult.failure(action,
                    "VALIDATION_FAILED", Map.of("detail",
                            TownActionFailures.safeMessage(exception)))));
            return false;
        }
    }

    private static Map<String, ?> joinData(JoinApplicationSnapshot application) {
        return Map.of("application_id", application.id(), "town_id", application.townId(),
                "applicant_id", application.applicantId(), "status", application.status());
    }

    private static Map<String, ?> transferData(TransferSnapshot transfer) {
        return Map.of("transfer_id", transfer.id(), "town_id", transfer.townId(),
                "candidate_id", transfer.candidateId(), "status", transfer.status());
    }

    private static Map<String, ?> voteData(VoteSnapshot vote) {
        return Map.of("vote_id", vote.id(), "town_id", vote.townId(), "status", vote.status(),
                "yes_votes", vote.yesVotes(), "no_votes", vote.noVotes(),
                "required_yes", vote.requiredYes());
    }

    @FunctionalInterface
    private interface ExpansionExecutor {
        void execute(Consumer<EconomyRepository.ExpansionOperation> success,
                     Consumer<RuntimeException> failure);
    }

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }
}

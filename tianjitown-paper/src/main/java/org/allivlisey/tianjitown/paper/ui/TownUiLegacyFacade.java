package org.allivlisey.tianjitown.paper.ui;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.action.TownActionOutcome;
import org.allivlisey.tianjitown.paper.land.TerritoryPreviewService;
import org.allivlisey.tianjitown.paper.runtime.*;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.application.*;
import org.allivlisey.tianjitown.paper.ui.buff.TownBuffShopDialogs;
import org.allivlisey.tianjitown.paper.ui.finance.TownFinanceDialogs;
import org.allivlisey.tianjitown.paper.ui.governance.TownGovernanceDialogs;
import org.allivlisey.tianjitown.paper.ui.home.*;
import org.allivlisey.tianjitown.paper.ui.membership.*;


import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.storage.town.*;
import org.allivlisey.tianjitown.storage.governance.*;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.ApplicationFormSession;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Compatibility entry points for feature routes, lifecycle callbacks and shared UI services.
 * Feature implementations own their queries, rendering and action completion callbacks.
 */
public final class TownUiLegacyFacade implements Listener {
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    private final TerritoryPreviewService territoryPreviews;
    private final TownDialogService dialogs;
    private TownHomeDialogs townHomeDialogs;
    private TownFinanceDialogs townFinanceDialogs;
    private TownBuffShopDialogs townBuffShopDialogs;
    private TownMembershipDialogs townMembershipDialogs;
    private TownGovernanceDialogs townGovernanceDialogs;
    private TownJoinApplicationDialogs townJoinApplicationDialogs;
    private TownAdminApplicationDialogs townAdminApplicationDialogs;
    private TownApplicationDialogs townApplicationDialogs;
    private TownApplicationFormDialogs townApplicationFormDialogs;
    private TownApplicationDrafts townApplicationDrafts;
    private TownInitialMemberDialogs townInitialMemberDialogs;
    private final TownUiPresentation townUiPresentation;
    private TownUiActionRouter router;
    private TownApplicationFormUi applicationFormUi;
    private Consumer<ApplicationSnapshot> applicationDecisionNotifier = application -> { };

    public void setApplicationDecisionNotifier(Consumer<ApplicationSnapshot> notifier) {
        applicationDecisionNotifier = Objects.requireNonNull(notifier, "notifier");
    }

    public void notifyApplicationDecision(ApplicationSnapshot application) {
        applicationDecisionNotifier.accept(application);
    }

    public TownUiLegacyFacade(TianjiTownPlugin plugin, TownRuntime runtime, TownActions actions) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.actions = actions;
        this.territoryPreviews = runtime.territoryPreviews();
        this.dialogs = new TownDialogService(plugin, this::routeAction);
        this.townUiPresentation = new TownUiPresentation(plugin, dialogs);
    }

    void bindDialogs(TownHomeDialogs townHomeDialogs,
            TownFinanceDialogs townFinanceDialogs,
            TownBuffShopDialogs townBuffShopDialogs,
            TownMembershipDialogs townMembershipDialogs,
            TownGovernanceDialogs townGovernanceDialogs,
            TownJoinApplicationDialogs townJoinApplicationDialogs,
            TownAdminApplicationDialogs townAdminApplicationDialogs,
            TownApplicationDialogs townApplicationDialogs,
            TownApplicationFormDialogs townApplicationFormDialogs,
            TownApplicationDrafts townApplicationDrafts,
            TownInitialMemberDialogs townInitialMemberDialogs) {
        this.townHomeDialogs = Objects.requireNonNull(townHomeDialogs);
        this.townFinanceDialogs = Objects.requireNonNull(townFinanceDialogs);
        this.townBuffShopDialogs = Objects.requireNonNull(townBuffShopDialogs);
        this.townMembershipDialogs = Objects.requireNonNull(townMembershipDialogs);
        this.townGovernanceDialogs = Objects.requireNonNull(townGovernanceDialogs);
        this.townJoinApplicationDialogs = Objects.requireNonNull(townJoinApplicationDialogs);
        this.townAdminApplicationDialogs = Objects.requireNonNull(townAdminApplicationDialogs);
        this.townApplicationDialogs = Objects.requireNonNull(townApplicationDialogs);
        this.townApplicationFormDialogs = Objects.requireNonNull(townApplicationFormDialogs);
        this.townApplicationDrafts = Objects.requireNonNull(townApplicationDrafts);
        this.townInitialMemberDialogs = Objects.requireNonNull(townInitialMemberDialogs);
    }

    public TownUiPresentation presentation() {
        return townUiPresentation;
    }

    public final void setRouter(TownUiActionRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    public final void setApplicationFormUi(TownApplicationFormUi applicationFormUi) {
        this.applicationFormUi = Objects.requireNonNull(applicationFormUi, "applicationFormUi");
    }

    public TownApplicationFormUi applicationFormUi() {
        return applicationFormUi;
    }

    public TianjiTownPlugin plugin() {
        return plugin;
    }

    public TownRuntime runtime() {
        return runtime;
    }

    public TownActions actions() {
        return actions;
    }

    public final TownDialogService dialogs() {
        return dialogs;
    }

    public final List<UUID> closeDialogs() {
        return dialogs.close();
    }

    public TerritoryPreviewService territoryPreviews() {
        return territoryPreviews;
    }

    public void openMain(Player player) {
        townHomeDialogs.openMain(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        townHomeDialogs.onJoin(event);
    }

    public final void clearPlayerSession(Player player) {
        dialogs.clear(player);
        applicationFormUi.clearPlayer(player.getUniqueId());
        territoryPreviews.stopPreview(player.getUniqueId());
    }

    public void openGovernanceCenter(Player player) {
        townGovernanceDialogs.openGovernanceCenter(player);
    }

    public void openVisitorCenter(Player player, UUID townId) {
        townMembershipDialogs.openVisitorCenter(player, townId);
    }

    public void openVisitorList(Player player, UUID townId, int page) {
        townMembershipDialogs.openVisitorList(player, townId, page);
    }

    public void openVisitorInvite(Player player, UUID townId, int page) {
        townMembershipDialogs.openVisitorInvite(player, townId, page);
    }

    public void openPendingCenter(Player player) {
        townHomeDialogs.openPendingCenter(player);
    }

    public void openPersonalCenter(Player player) {
        townHomeDialogs.openPersonalCenter(player);
    }

    public void openFinance(Player player, int page) {
        townFinanceDialogs.openFinance(player, page);
    }

    public void openTaxMenu(Player player) {
        townFinanceDialogs.openTaxMenu(player);
    }

    public void openLedger(Player player, int page) {
        townFinanceDialogs.openLedger(player, page);
    }

    public void openBuffShop(Player player) {
        townBuffShopDialogs.openBuffShop(player);
    }

    public void openBuffDurations(Player player, String buffKey) {
        townBuffShopDialogs.openBuffDurations(player, buffKey);
    }

    public void buyBuff(Player player, String target) {
        townBuffShopDialogs.buyBuff(player, target);
    }

    public void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        townGovernanceDialogs.renderRulesConfirmation(player, governance);
    }

    public void openApplication(Player player, ApplicationSnapshot application) {
        townApplicationDialogs.openApplication(player, application);
    }

    public void renderApplication(Player player, ApplicationSnapshot application) {
        townApplicationDialogs.renderApplication(player, application);
    }

    public void openTown(Player player, UUID townId) {
        townHomeDialogs.openTown(player, townId);
    }

    public void openTownRules(Player player, UUID townId) {
        townGovernanceDialogs.openTownRules(player, townId);
    }

    public void openJoinTownRules(Player player, UUID townId) {
        townGovernanceDialogs.openJoinTownRules(player, townId);
    }

    public void openTownRuleEditor(Player player, UUID townId) {
        townGovernanceDialogs.openTownRuleEditor(player, townId);
    }

    public void openRuleEditorAddDialog(Player player, String title, Component content,
                                         DialogRoute parent,
                                         int addWidth,
                                         int columns,
                                         Function<UUID, List<ActionButton>> afterAddActions,
                                         Function<UUID, List<ActionButton>> ruleActions,
                                         Consumer<DialogResponseView> addRule,
                                         Function<UUID, List<ActionButton>> trailingActions,
                                         Function<UUID, List<ActionButton>> postActions) {
        townGovernanceDialogs.openRuleEditorAddDialog(player, title, content, parent, addWidth, columns, afterAddActions, ruleActions, addRule, trailingActions, postActions);
    }

    /**
     * A rule is its own delete control. This keeps both rule editors readable while making the
     * destructive action discoverable from the hover text.
     */
    public List<ActionButton> inlineRuleDeletionActions(Player player, UUID session,
                                                          RuleEditorDialogRenderer.Layout layout,
                                                          Consumer<RuleEditorDialogRenderer.DeleteTarget> deleteRule) {
        return townGovernanceDialogs.inlineRuleDeletionActions(player, session, layout, deleteRule);
    }

    public void openTownMemberOverview(Player player, UUID townId, int page) {
        townMembershipDialogs.openTownMemberOverview(player, townId, page);
    }

    public void openMembers(Player player, UUID townId, int page) {
        townMembershipDialogs.openMembers(player, townId, page);
    }

    public void openMemberDetail(Player player, UUID townId, UUID targetId, int page) {
        townMembershipDialogs.openMemberDetail(player, townId, targetId, page);
    }

    public void openTransferRequest(Player player, UUID transferId) {
        townGovernanceDialogs.openTransferRequest(player, transferId);
    }

    public void openVotes(Player player, UUID townId) {
        townGovernanceDialogs.openVotes(player, townId);
    }

    public void openVotes(Player player, UUID townId, int requestedPage) {
        townGovernanceDialogs.openVotes(player, townId, requestedPage);
    }

    public void openVote(Player player, UUID voteId) {
        townGovernanceDialogs.openVote(player, voteId);
    }

    public String displayName(UUID playerId) {
        if (playerId == null) {
            return plugin.messages().plainText("dialog.common.unknown");
        }
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        if (name != null && !name.isBlank()) {
            return safeText(name);
        }
        String compactId = playerId.toString().replace("-", "");
        String suffix = compactId.substring(Math.max(0, compactId.length() - 8));
        return plugin.messages().plainText("dialog.common.unknown-player",
                Map.of("playerId", suffix));
    }

    public void openJoinTowns(Player player) {
        townJoinApplicationDialogs.openJoinTowns(player);
    }

    public void openJoinTowns(Player player, int requestedPage) {
        townJoinApplicationDialogs.openJoinTowns(player, requestedPage);
    }

    public void openJoinTown(Player player, UUID townId) {
        townJoinApplicationDialogs.openJoinTown(player, townId);
    }

    public void openMyJoinApplications(Player player) {
        townJoinApplicationDialogs.openMyJoinApplications(player);
    }

    public void openTownJoinApplications(Player mayor, UUID townId) {
        townJoinApplicationDialogs.openTownJoinApplications(mayor, townId);
    }

    public void openTownJoinApplications(Player mayor, UUID townId, int requestedPage) {
        townJoinApplicationDialogs.openTownJoinApplications(mayor, townId, requestedPage);
    }

    public void openTownJoinApplication(Player mayor, UUID applicationId) {
        townJoinApplicationDialogs.openTownJoinApplication(mayor, applicationId);
    }

    public void openAdminApplications(Player admin) {
        townAdminApplicationDialogs.openAdminApplications(admin);
    }

    public void openAdminApplications(Player admin, int requestedPage) {
        townAdminApplicationDialogs.openAdminApplications(admin, requestedPage);
    }

    public void openAdminApplication(Player admin, UUID applicationId) {
        townAdminApplicationDialogs.openAdminApplication(admin, applicationId);
    }

    private void routeAction(Player player, String action, String target) {
        router.route(player, action, target);
    }

    public boolean blockForMaintenance(Player player, String action) {
        if (maintenanceMode() && !"CLOSE".equals(action)) {
            townUiPresentation.openNotice(player, townUiPresentation.dialogText("notice.maintenance-title"),
                    plugin.messages().text("system.maintenance"), townUiPresentation.dialogText("common.close"),
                    "CLOSE", null);
            return true;
        }
        return false;
    }

    public void openStaleMenu(Player player) {
        townUiPresentation.openNotice(player, townUiPresentation.dialogText("notice.stale-data-title"),
                plugin.messages().text("system.invalid-menu-data"),
                townUiPresentation.dialogText("common.reopen"), "MAIN", null);
    }

    public void changeMemberRole(Player mayor, UUID townId, UUID playerId, MemberRole role, int page) {
        townMembershipDialogs.changeMemberRole(mayor, townId, playerId, role, page);
    }

    public void kickMember(Player mayor, UUID townId, UUID playerId, int page) {
        townMembershipDialogs.kickMember(mayor, townId, playerId, page);
    }

    public void addVisitor(Player manager, UUID townId, UUID playerId) {
        townMembershipDialogs.addVisitor(manager, townId, playerId);
    }

    public void removeVisitor(Player manager, UUID townId, UUID playerId, int page) {
        townMembershipDialogs.removeVisitor(manager, townId, playerId, page);
    }

    public void requestMayorTransfer(Player mayor, UUID townId, UUID candidateId) {
        townGovernanceDialogs.requestMayorTransfer(mayor, townId, candidateId);
    }

    public void decideMayorTransfer(Player candidate, UUID transferId, boolean accept) {
        townGovernanceDialogs.decideMayorTransfer(candidate, transferId, accept);
    }

    public void acknowledgeRules(Player player, UUID townId, long revision) {
        townGovernanceDialogs.acknowledgeRules(player, townId, revision);
    }

    public void createVote(Player player, UUID townId, VoteType type, UUID targetId) {
        townGovernanceDialogs.createVote(player, townId, type, targetId);
    }

    public void sendVoteReminder(Player player, VoteSnapshot vote) {
        townGovernanceDialogs.sendVoteReminder(player, vote);
    }

    public void castVote(Player player, UUID voteId, boolean approve) {
        townGovernanceDialogs.castVote(player, voteId, approve);
    }

    public void cancelVote(Player player, UUID voteId) {
        townGovernanceDialogs.cancelVote(player, voteId);
    }

    public void loadApplication(Player player, UUID applicationId) {
        townApplicationDialogs.loadApplication(player, applicationId);
    }

    public void loadApplicationForForm(Player player, UUID applicationId) {
        townApplicationDrafts.loadApplicationForForm(player, applicationId);
    }

    public void loadTownForForm(Player player, UUID townId) {
        townApplicationDrafts.loadTownForForm(player, townId);
    }

    public void selectApplicationSite(Player player, UUID applicationId) {
        townApplicationDialogs.selectApplicationSite(player, applicationId);
    }

    public void previewApplicationSite(Player player, UUID applicationId) {
        townApplicationDialogs.previewApplicationSite(player, applicationId);
    }

    public void submitApplication(Player player, UUID applicationId) {
        townApplicationDialogs.submitApplication(player, applicationId);
    }

    public void cancelApplication(Player player, UUID applicationId) {
        townApplicationDialogs.cancelApplication(player, applicationId);
    }

    public void applyJoin(Player player, UUID townId) {
        townJoinApplicationDialogs.applyJoin(player, townId);
    }

    public void cancelJoin(Player player, UUID applicationId) {
        townJoinApplicationDialogs.cancelJoin(player, applicationId);
    }

    public void approveJoin(Player mayor, UUID applicationId) {
        townJoinApplicationDialogs.approveJoin(mayor, applicationId);
    }

    public void rejectJoin(Player mayor, UUID applicationId) {
        townJoinApplicationDialogs.rejectJoin(mayor, applicationId);
    }

    public void notifyMayorJoinApplication(JoinApplicationSnapshot application) {
        townJoinApplicationDialogs.notifyMayorJoinApplication(application);
    }

    public void adminApprove(Player admin, UUID applicationId) {
        townAdminApplicationDialogs.adminApprove(admin, applicationId);
    }

    public void recoverFailedApplication(Player admin, TownRepository.RecoveryMode mode,
                                  UUID applicationId) {
        townAdminApplicationDialogs.recoverFailedApplication(admin, mode, applicationId);
    }

    public void beginAdminDecision(Player admin, UUID applicationId, boolean requestChanges) {
        townAdminApplicationDialogs.beginAdminDecision(admin, applicationId, requestChanges);
    }

    public void adminPreviewSite(Player admin, UUID applicationId) {
        townAdminApplicationDialogs.adminPreviewSite(admin, applicationId);
    }

    public void notifyApplicationSubmitted(ApplicationSnapshot application) {
        townApplicationDialogs.notifyApplicationSubmitted(application);
    }

    public void leave(Player player, UUID townId) {
        townHomeDialogs.leave(player, townId);
    }

    public void disband(Player mayor, String target) {
        townHomeDialogs.disband(mayor, target);
    }

    public void loadApplicationFormDraft(Player player) {
        townApplicationDrafts.loadApplicationFormDraft(player);
    }

    public void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        boolean exitAfterSave) {
        townApplicationDrafts.persistApplicationForm(player, form, step, exitAfterSave);
    }

    public void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        Consumer<ApplicationFormDraft> afterSave) {
        townApplicationDrafts.persistApplicationForm(player, form, step, afterSave);
    }

    public void discardApplicationForm(Player player, UUID formId) {
        townApplicationDrafts.discardApplicationForm(player, formId);
    }

    public void renderApplicationForm(Player player, UUID formId) {
        townApplicationFormDialogs.renderApplicationForm(player, formId);
    }

    public void renderApplicationFormStage(Player player, UUID formId, int stage) {
        townApplicationFormDialogs.renderApplicationFormStage(player, formId, stage);
    }

    public void persistCurrentApplicationForm(Player player, UUID formId) {
        townApplicationFormDialogs.persistCurrentApplicationForm(player, formId);
    }

    public void openInitialMemberOptions(Player player, UUID formId, int memberIndex) {
        townApplicationFormDialogs.openInitialMemberOptions(player, formId, memberIndex);
    }

    public void chooseInitialMember(Player player, String target) {
        townApplicationFormDialogs.chooseInitialMember(player, target);
    }

    public ApplicationFormSession requireApplicationForm(Player player, UUID formId) {
        return townApplicationFormDialogs.requireApplicationForm(player, formId);
    }

    public static String responseText(DialogResponseView response, String key) {
        return TownApplicationFormDialogs.responseText(response, key);
    }

    public void startDonationInput(Player player) {
        townFinanceDialogs.startDonationInput(player);
    }

    public void saveApplicationForm(Player player, UUID formId) {
        townApplicationDrafts.saveApplicationForm(player, formId);
    }

    public static List<String> normalizedMemberNames(List<String> names) {
        return TownApplicationDrafts.normalizedMemberNames(names);
    }

    public void notifyInitialMembers(ApplicationSnapshot application) {
        townInitialMemberDialogs.notifyInitialMembers(application);
    }

    public void remindInitialMembers(Player applicant, UUID applicationId) {
        townInitialMemberDialogs.remindInitialMembers(applicant, applicationId);
    }

    public void sendInitialMemberReminder(Player member, ApplicationSnapshot application) {
        townInitialMemberDialogs.sendInitialMemberReminder(member, application);
    }

    public static String preview(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    public static <T> List<T> page(List<T> values, int page, int pageSize) {
        int start = Math.min(values.size(), Math.max(0, page) * pageSize);
        int end = Math.min(values.size(), start + pageSize);
        return values.subList(start, end);
    }

    public TownSnapshot.Page memberPage(List<TownSnapshot.Member> members, int requestedPage) {
        int page = Math.max(0, requestedPage);
        List<TownSnapshot.Member> sorted = MemberDisplayOrder.sort(members, this::displayName);
        return new TownSnapshot.Page(page(sorted, page, 8), hasNext(sorted, page, 8));
    }

    public String memberRoleText(MemberRole role) {
        return townUiPresentation.dialogText(MemberRoleText.messageKey(role));
    }

    public static boolean hasNext(List<?> values, int page, int pageSize) {
        return (Math.max(0, page) + 1) * pageSize < values.size();
    }

    public <T> void handleOutcome(Player player, TownActionOutcome<T> outcome,
                                   Consumer<T> success) {
        if (outcome.result().success()) {
            success.accept(outcome.value());
            return;
        }
        String detail = outcome.result().data().get("detail");
        String friendly = detail == null || detail.isBlank()
                ? outcomeFailureDetail(outcome.result().reason()) : safeText(detail);
        townUiPresentation.openNotice(player, townUiPresentation.dialogText("notice.operation-failed-title"),
                plugin.messages().text("system.operation-failed", Map.of("detail", friendly)),
                townUiPresentation.dialogText("common.back"), "MAIN", null);
    }

    private String outcomeFailureDetail(String reason) {
        return switch (reason) {
            case "FEATURE_DISABLED" -> townUiPresentation.dialogText("notice.operation-failed-feature-disabled");
            case "STORAGE_UNAVAILABLE" -> townUiPresentation.dialogText(
                    "notice.operation-failed-storage-unavailable");
            case "INSUFFICIENT_BALANCE" -> townUiPresentation.dialogText(
                    "notice.operation-failed-insufficient-balance");
            case "FORBIDDEN" -> townUiPresentation.dialogText("notice.operation-failed-forbidden");
            case "NOT_FOUND" -> townUiPresentation.dialogText("notice.operation-failed-not-found");
            default -> townUiPresentation.dialogText("notice.operation-failed-default");
        };
    }

    public void closeUi(Player player) {
        player.closeDialog();
    }

    public static Component legacyComponent(String value) {
        return TownUiPresentation.legacyComponent(value);
    }

    public boolean isCurrent(Player player, UUID session) {
        return dialogs.isCurrent(player, session);
    }

    public boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("town.maintenance-mode", false);
    }

    public static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    public static String safeText(Object value) {
        return TownUiPresentation.safeText(value);
    }

    public record GovernanceCenterView(TownRepository.PlayerDashboard dashboard,
                                        MemberGovernanceSnapshot governance) {
    }

}

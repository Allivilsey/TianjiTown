package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.paper.TownUiPresentation.MenuItem;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.ApplicationFormDraft;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.allivlisey.tianjitown.paper.TownApplicationFormUi.ApplicationFormSession;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

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
final class TownUiLegacyFacade implements Listener {
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    private final SitePolicy sitePolicy;
    private final TownDialogService dialogs;
    private final TownHomeDialogs townHomeDialogs;
    private final TownFinanceDialogs townFinanceDialogs;
    private final TownBuffShopDialogs townBuffShopDialogs;
    private final TownMembershipDialogs townMembershipDialogs;
    private final TownGovernanceDialogs townGovernanceDialogs;
    private final TownJoinApplicationDialogs townJoinApplicationDialogs;
    private final TownAdminApplicationDialogs townAdminApplicationDialogs;
    private final TownApplicationDialogs townApplicationDialogs;
    private final TownApplicationFormDialogs townApplicationFormDialogs;
    private final TownApplicationDrafts townApplicationDrafts;
    private final TownInitialMemberDialogs townInitialMemberDialogs;
    private final TownUiPresentation townUiPresentation;
    private TownUiActionRouter router;
    private TownApplicationFormUi applicationFormUi;
    private Consumer<ApplicationSnapshot> applicationDecisionNotifier = application -> { };

    void setApplicationDecisionNotifier(Consumer<ApplicationSnapshot> notifier) {
        applicationDecisionNotifier = Objects.requireNonNull(notifier, "notifier");
    }

    void notifyApplicationDecision(ApplicationSnapshot application) {
        applicationDecisionNotifier.accept(application);
    }

    TownUiLegacyFacade(TianjiTownPlugin plugin, TownRuntime runtime, TownActions actions) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.actions = actions;
        this.sitePolicy = runtime.sitePolicy();
        this.dialogs = new TownDialogService(plugin, this::routeAction);
        this.townUiPresentation = new TownUiPresentation(plugin, dialogs);
        this.townHomeDialogs = new TownHomeDialogs(this);
        this.townFinanceDialogs = new TownFinanceDialogs(this);
        this.townBuffShopDialogs = new TownBuffShopDialogs(this);
        this.townMembershipDialogs = new TownMembershipDialogs(this);
        this.townGovernanceDialogs = new TownGovernanceDialogs(this);
        this.townJoinApplicationDialogs = new TownJoinApplicationDialogs(this);
        this.townAdminApplicationDialogs = new TownAdminApplicationDialogs(this);
        this.townApplicationDialogs = new TownApplicationDialogs(this);
        this.townApplicationFormDialogs = new TownApplicationFormDialogs(this);
        this.townApplicationDrafts = new TownApplicationDrafts(this);
        this.townInitialMemberDialogs = new TownInitialMemberDialogs(this);
    }

    final void setRouter(TownUiActionRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    final void setApplicationFormUi(TownApplicationFormUi applicationFormUi) {
        this.applicationFormUi = Objects.requireNonNull(applicationFormUi, "applicationFormUi");
    }

    TownApplicationFormUi applicationFormUi() {
        return applicationFormUi;
    }

    TianjiTownPlugin plugin() {
        return plugin;
    }

    TownRuntime runtime() {
        return runtime;
    }

    TownActions actions() {
        return actions;
    }

    final TownDialogService dialogs() {
        return dialogs;
    }

    final List<UUID> closeDialogs() {
        return dialogs.close();
    }

    SitePolicy sitePolicy() {
        return sitePolicy;
    }

    void openMain(Player player) {
        townHomeDialogs.openMain(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        townHomeDialogs.onJoin(event);
    }

    final void clearPlayerSession(Player player) {
        dialogs.clear(player);
        applicationFormUi.clearPlayer(player.getUniqueId());
        sitePolicy.stopPreview(player.getUniqueId());
    }

    void openGovernanceCenter(Player player) {
        townGovernanceDialogs.openGovernanceCenter(player);
    }

    void openVisitorCenter(Player player, UUID townId) {
        townMembershipDialogs.openVisitorCenter(player, townId);
    }

    void openVisitorList(Player player, UUID townId, int page) {
        townMembershipDialogs.openVisitorList(player, townId, page);
    }

    void openVisitorInvite(Player player, UUID townId, int page) {
        townMembershipDialogs.openVisitorInvite(player, townId, page);
    }

    void openPendingCenter(Player player) {
        townHomeDialogs.openPendingCenter(player);
    }

    void openPersonalCenter(Player player) {
        townHomeDialogs.openPersonalCenter(player);
    }

    void openFinance(Player player, int page) {
        townFinanceDialogs.openFinance(player, page);
    }

    void openTaxMenu(Player player) {
        townFinanceDialogs.openTaxMenu(player);
    }

    void openLedger(Player player, int page) {
        townFinanceDialogs.openLedger(player, page);
    }

    void openBuffShop(Player player) {
        townBuffShopDialogs.openBuffShop(player);
    }

    void openBuffDurations(Player player, String buffKey) {
        townBuffShopDialogs.openBuffDurations(player, buffKey);
    }

    void buyBuff(Player player, String target) {
        townBuffShopDialogs.buyBuff(player, target);
    }

    void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        townGovernanceDialogs.renderRulesConfirmation(player, governance);
    }

    void openApplication(Player player, ApplicationSnapshot application) {
        townApplicationDialogs.openApplication(player, application);
    }

    void renderApplication(Player player, ApplicationSnapshot application) {
        townApplicationDialogs.renderApplication(player, application);
    }

    void openTown(Player player, UUID townId) {
        townHomeDialogs.openTown(player, townId);
    }

    void openTownRules(Player player, UUID townId) {
        townGovernanceDialogs.openTownRules(player, townId);
    }

    void openJoinTownRules(Player player, UUID townId) {
        townGovernanceDialogs.openJoinTownRules(player, townId);
    }

    void openTownRuleEditor(Player player, UUID townId) {
        townGovernanceDialogs.openTownRuleEditor(player, townId);
    }

    void openRuleEditorAddDialog(Player player, String title, Component content,
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
    List<ActionButton> inlineRuleDeletionActions(Player player, UUID session,
                                                          RuleEditorDialogRenderer.Layout layout,
                                                          Consumer<RuleEditorDialogRenderer.DeleteTarget> deleteRule) {
        return townGovernanceDialogs.inlineRuleDeletionActions(player, session, layout, deleteRule);
    }

    void openTownMemberOverview(Player player, UUID townId, int page) {
        townMembershipDialogs.openTownMemberOverview(player, townId, page);
    }

    void openMembers(Player player, UUID townId, int page) {
        townMembershipDialogs.openMembers(player, townId, page);
    }

    void openMemberDetail(Player player, UUID townId, UUID targetId, int page) {
        townMembershipDialogs.openMemberDetail(player, townId, targetId, page);
    }

    void openTransferRequest(Player player, UUID transferId) {
        townGovernanceDialogs.openTransferRequest(player, transferId);
    }

    void openVotes(Player player, UUID townId) {
        townGovernanceDialogs.openVotes(player, townId);
    }

    void openVotes(Player player, UUID townId, int requestedPage) {
        townGovernanceDialogs.openVotes(player, townId, requestedPage);
    }

    void openVote(Player player, UUID voteId) {
        townGovernanceDialogs.openVote(player, voteId);
    }

    String displayName(UUID playerId) {
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

    void openJoinTowns(Player player) {
        townJoinApplicationDialogs.openJoinTowns(player);
    }

    void openJoinTowns(Player player, int requestedPage) {
        townJoinApplicationDialogs.openJoinTowns(player, requestedPage);
    }

    void openJoinTown(Player player, UUID townId) {
        townJoinApplicationDialogs.openJoinTown(player, townId);
    }

    void openMyJoinApplications(Player player) {
        townJoinApplicationDialogs.openMyJoinApplications(player);
    }

    void openTownJoinApplications(Player mayor, UUID townId) {
        townJoinApplicationDialogs.openTownJoinApplications(mayor, townId);
    }

    void openTownJoinApplications(Player mayor, UUID townId, int requestedPage) {
        townJoinApplicationDialogs.openTownJoinApplications(mayor, townId, requestedPage);
    }

    void openTownJoinApplication(Player mayor, UUID applicationId) {
        townJoinApplicationDialogs.openTownJoinApplication(mayor, applicationId);
    }

    void openAdminApplications(Player admin) {
        townAdminApplicationDialogs.openAdminApplications(admin);
    }

    void openAdminApplications(Player admin, int requestedPage) {
        townAdminApplicationDialogs.openAdminApplications(admin, requestedPage);
    }

    void openAdminApplication(Player admin, UUID applicationId) {
        townAdminApplicationDialogs.openAdminApplication(admin, applicationId);
    }

    private void routeAction(Player player, String action, String target) {
        router.route(player, action, target);
    }

    boolean blockForMaintenance(Player player, String action) {
        if (maintenanceMode() && !"CLOSE".equals(action)) {
            openNotice(player, dialogText("notice.maintenance-title"),
                    plugin.messages().text("system.maintenance"), dialogText("common.close"),
                    "CLOSE", null);
            return true;
        }
        return false;
    }

    void openStaleMenu(Player player) {
        openNotice(player, dialogText("notice.stale-data-title"),
                plugin.messages().text("system.invalid-menu-data"),
                dialogText("common.reopen"), "MAIN", null);
    }

    void changeMemberRole(Player mayor, UUID townId, UUID playerId, MemberRole role, int page) {
        townMembershipDialogs.changeMemberRole(mayor, townId, playerId, role, page);
    }

    void kickMember(Player mayor, UUID townId, UUID playerId, int page) {
        townMembershipDialogs.kickMember(mayor, townId, playerId, page);
    }

    void addVisitor(Player manager, UUID townId, UUID playerId) {
        townMembershipDialogs.addVisitor(manager, townId, playerId);
    }

    void removeVisitor(Player manager, UUID townId, UUID playerId, int page) {
        townMembershipDialogs.removeVisitor(manager, townId, playerId, page);
    }

    void requestMayorTransfer(Player mayor, UUID townId, UUID candidateId) {
        townGovernanceDialogs.requestMayorTransfer(mayor, townId, candidateId);
    }

    void decideMayorTransfer(Player candidate, UUID transferId, boolean accept) {
        townGovernanceDialogs.decideMayorTransfer(candidate, transferId, accept);
    }

    void acknowledgeRules(Player player, UUID townId, long revision) {
        townGovernanceDialogs.acknowledgeRules(player, townId, revision);
    }

    void createVote(Player player, UUID townId, VoteType type, UUID targetId) {
        townGovernanceDialogs.createVote(player, townId, type, targetId);
    }

    void sendVoteReminder(Player player, VoteSnapshot vote) {
        townGovernanceDialogs.sendVoteReminder(player, vote);
    }

    void castVote(Player player, UUID voteId, boolean approve) {
        townGovernanceDialogs.castVote(player, voteId, approve);
    }

    void cancelVote(Player player, UUID voteId) {
        townGovernanceDialogs.cancelVote(player, voteId);
    }

    void loadApplication(Player player, UUID applicationId) {
        townApplicationDialogs.loadApplication(player, applicationId);
    }

    void loadApplicationForForm(Player player, UUID applicationId) {
        townApplicationDrafts.loadApplicationForForm(player, applicationId);
    }

    void loadTownForForm(Player player, UUID townId) {
        townApplicationDrafts.loadTownForForm(player, townId);
    }

    void selectApplicationSite(Player player, UUID applicationId) {
        townApplicationDialogs.selectApplicationSite(player, applicationId);
    }

    void previewApplicationSite(Player player, UUID applicationId) {
        townApplicationDialogs.previewApplicationSite(player, applicationId);
    }

    void submitApplication(Player player, UUID applicationId) {
        townApplicationDialogs.submitApplication(player, applicationId);
    }

    void cancelApplication(Player player, UUID applicationId) {
        townApplicationDialogs.cancelApplication(player, applicationId);
    }

    void applyJoin(Player player, UUID townId) {
        townJoinApplicationDialogs.applyJoin(player, townId);
    }

    void cancelJoin(Player player, UUID applicationId) {
        townJoinApplicationDialogs.cancelJoin(player, applicationId);
    }

    void approveJoin(Player mayor, UUID applicationId) {
        townJoinApplicationDialogs.approveJoin(mayor, applicationId);
    }

    void rejectJoin(Player mayor, UUID applicationId) {
        townJoinApplicationDialogs.rejectJoin(mayor, applicationId);
    }

    void notifyMayorJoinApplication(JoinApplicationSnapshot application) {
        townJoinApplicationDialogs.notifyMayorJoinApplication(application);
    }

    void adminApprove(Player admin, UUID applicationId) {
        townAdminApplicationDialogs.adminApprove(admin, applicationId);
    }

    void recoverFailedApplication(Player admin, TownRepository.RecoveryMode mode,
                                  UUID applicationId) {
        townAdminApplicationDialogs.recoverFailedApplication(admin, mode, applicationId);
    }

    void beginAdminDecision(Player admin, UUID applicationId, boolean requestChanges) {
        townAdminApplicationDialogs.beginAdminDecision(admin, applicationId, requestChanges);
    }

    void adminPreviewSite(Player admin, UUID applicationId) {
        townAdminApplicationDialogs.adminPreviewSite(admin, applicationId);
    }

    void notifyApplicationSubmitted(ApplicationSnapshot application) {
        townApplicationDialogs.notifyApplicationSubmitted(application);
    }

    void leave(Player player, UUID townId) {
        townHomeDialogs.leave(player, townId);
    }

    void disband(Player mayor, String target) {
        townHomeDialogs.disband(mayor, target);
    }

    void loadApplicationFormDraft(Player player) {
        townApplicationDrafts.loadApplicationFormDraft(player);
    }

    void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        boolean exitAfterSave) {
        townApplicationDrafts.persistApplicationForm(player, form, step, exitAfterSave);
    }

    void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        Consumer<ApplicationFormDraft> afterSave) {
        townApplicationDrafts.persistApplicationForm(player, form, step, afterSave);
    }

    void discardApplicationForm(Player player, UUID formId) {
        townApplicationDrafts.discardApplicationForm(player, formId);
    }

    void renderApplicationForm(Player player, UUID formId) {
        townApplicationFormDialogs.renderApplicationForm(player, formId);
    }

    void renderApplicationFormStage(Player player, UUID formId, int stage) {
        townApplicationFormDialogs.renderApplicationFormStage(player, formId, stage);
    }

    void persistCurrentApplicationForm(Player player, UUID formId) {
        townApplicationFormDialogs.persistCurrentApplicationForm(player, formId);
    }

    void openInitialMemberOptions(Player player, UUID formId, int memberIndex) {
        townApplicationFormDialogs.openInitialMemberOptions(player, formId, memberIndex);
    }

    void chooseInitialMember(Player player, String target) {
        townApplicationFormDialogs.chooseInitialMember(player, target);
    }

    ApplicationFormSession requireApplicationForm(Player player, UUID formId) {
        return townApplicationFormDialogs.requireApplicationForm(player, formId);
    }

    static String responseText(DialogResponseView response, String key) {
        return TownApplicationFormDialogs.responseText(response, key);
    }

    void startDonationInput(Player player) {
        townFinanceDialogs.startDonationInput(player);
    }

    void saveApplicationForm(Player player, UUID formId) {
        townApplicationDrafts.saveApplicationForm(player, formId);
    }

    static List<String> normalizedMemberNames(List<String> names) {
        return TownApplicationDrafts.normalizedMemberNames(names);
    }

    void notifyInitialMembers(ApplicationSnapshot application) {
        townInitialMemberDialogs.notifyInitialMembers(application);
    }

    void remindInitialMembers(Player applicant, UUID applicationId) {
        townInitialMemberDialogs.remindInitialMembers(applicant, applicationId);
    }

    void sendInitialMemberReminder(Player member, ApplicationSnapshot application) {
        townInitialMemberDialogs.sendInitialMemberReminder(member, application);
    }

    static String preview(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    static <T> List<T> page(List<T> values, int page, int pageSize) {
        int start = Math.min(values.size(), Math.max(0, page) * pageSize);
        int end = Math.min(values.size(), start + pageSize);
        return values.subList(start, end);
    }

    TownSnapshot.Page memberPage(List<TownSnapshot.Member> members, int requestedPage) {
        int page = Math.max(0, requestedPage);
        List<TownSnapshot.Member> sorted = MemberDisplayOrder.sort(members, this::displayName);
        return new TownSnapshot.Page(page(sorted, page, 8), hasNext(sorted, page, 8));
    }

    String memberRoleText(MemberRole role) {
        return dialogText(MemberRoleText.messageKey(role));
    }

    static boolean hasNext(List<?> values, int page, int pageSize) {
        return (Math.max(0, page) + 1) * pageSize < values.size();
    }

    <T> void handleOutcome(Player player, TownActionOutcome<T> outcome,
                                   Consumer<T> success) {
        if (outcome.result().success()) {
            success.accept(outcome.value());
            return;
        }
        String detail = outcome.result().data().get("detail");
        String friendly = detail == null || detail.isBlank()
                ? outcomeFailureDetail(outcome.result().reason()) : safeText(detail);
        openNotice(player, dialogText("notice.operation-failed-title"),
                plugin.messages().text("system.operation-failed", Map.of("detail", friendly)),
                dialogText("common.back"), "MAIN", null);
    }

    private String outcomeFailureDetail(String reason) {
        return switch (reason) {
            case "FEATURE_DISABLED" -> dialogText("notice.operation-failed-feature-disabled");
            case "STORAGE_UNAVAILABLE" -> dialogText(
                    "notice.operation-failed-storage-unavailable");
            case "INSUFFICIENT_BALANCE" -> dialogText(
                    "notice.operation-failed-insufficient-balance");
            case "FORBIDDEN" -> dialogText("notice.operation-failed-forbidden");
            case "NOT_FOUND" -> dialogText("notice.operation-failed-not-found");
            default -> dialogText("notice.operation-failed-default");
        };
    }

    void openConfirmation(Player player, String title, String confirmedAction,
                                  String target, String consequence, String returnAction,
                                  String returnTarget) {
        townUiPresentation.openConfirmation(player, title, confirmedAction, target, consequence, returnAction, returnTarget);
    }

    UUID openMenu(Player player, int size, String title, DialogRoute parent,
                          List<MenuItem> items) {
        return townUiPresentation.openMenu(player, size, title, parent, items);
    }

    UUID openDialogPage(Player player, String title, List<? extends DialogBody> bodies,
                                List<? extends DialogInput> inputs,
                                DialogBase.DialogAfterAction afterAction,
        Function<UUID, DialogType> typeFactory, DialogRoute parent) {
        return townUiPresentation.openDialogPage(player, title, bodies, inputs, afterAction, typeFactory, parent);
    }

    void openNotice(Player player, String title, String message, String actionLabel,
                            String action, String target) {
        townUiPresentation.openNotice(player, title, message, actionLabel, action, target);
    }

    DialogBody dialogTextBody(ItemStack item) {
        return townUiPresentation.dialogTextBody(item);
    }

    ActionButton returnButton(Player player, UUID session, DialogRoute parent) {
        return townUiPresentation.returnButton(player, session, parent);
    }

    ActionButton dialogButton(Player player, ItemStack item, UUID session) {
        return townUiPresentation.dialogButton(player, item, session);
    }

    DialogAction dialogAction(Player recipient, UUID session, String action,
                                      String target) {
        return townUiPresentation.dialogAction(recipient, session, action, target);
    }

    DialogAction dialogAction(Player recipient, UUID session,
                                      Consumer<DialogResponseView> handler) {
        return townUiPresentation.dialogAction(recipient, session, handler);
    }

    void closeUi(Player player) {
        player.closeDialog();
    }

    String dialogText(String key) {
        return townUiPresentation.dialogText(key);
    }

    String dialogText(String key, Map<String, ?> placeholders) {
        return townUiPresentation.dialogText(key, placeholders);
    }

    String dialogFormat(String key) {
        return townUiPresentation.dialogFormat(key);
    }

    Component dialogComponent(String key) {
        return townUiPresentation.dialogComponent(key);
    }

    Component dialogComponent(String key, Map<String, ?> placeholders) {
        return townUiPresentation.dialogComponent(key, placeholders);
    }

    static Component legacyComponent(String value) {
        return TownUiPresentation.legacyComponent(value);
    }

    Component callbackButton(Player recipient, String labelKey, Runnable action) {
        return townUiPresentation.callbackButton(recipient, labelKey, action);
    }

    void playSound(Player player, Sound sound) {
        townUiPresentation.playSound(player, sound);
    }

    ItemStack button(Material material, String name, List<String> lore,
                             String action, String target) {
        return townUiPresentation.button(material, name, lore, action, target);
    }

    boolean isCurrent(Player player, UUID session) {
        return dialogs.isCurrent(player, session);
    }

    boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("town.maintenance-mode", false);
    }

    static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    record GovernanceCenterView(TownRepository.PlayerDashboard dashboard,
                                        MemberGovernanceSnapshot governance) {
    }

}

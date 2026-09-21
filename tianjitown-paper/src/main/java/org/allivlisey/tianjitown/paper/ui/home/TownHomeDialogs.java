package org.allivlisey.tianjitown.paper.ui.home;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.territory.TownTerritoryUi;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Loads dashboards and town details, and handles leaving or disbanding a town. */
public final class TownHomeDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownHomeDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openMain(Player player) {
        if (facade.maintenanceMode()) {
            plugin.messages().send(player, "system.maintenance");
            return;
        }
        UUID request = presentation.openMenu(player, 9, presentation.dialogText("main.loading-title"), DialogRoute.ROOT,
                List.of());
        runtime.read(player, () -> {
            runtime.governance().recordActivity(player.getUniqueId());
            TownRepository.PlayerDashboard dashboard = runtime.repository()
                    .dashboard(player.getUniqueId());
            EconomyRepository.TownFinance finance = dashboard.town() == null ? null
                    : runtime.finance().findFinanceByPlayer(player.getUniqueId()).orElse(null);
            return new MainView(dashboard,
                    runtime.governance().dashboard(player.getUniqueId()).orElse(null), finance,
                    player.hasPermission("tianjitown.admin")
                            ? runtime.repository().listReviewQueue(100) : List.of(),
                    runtime.repository().listPendingInitialMemberApplications(player.getUniqueId()));
        }, view -> {
            if (facade.isCurrent(player, request)) {
                renderMain(player, view);
            }
        });
    }

    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        runtime.write(player, () -> {
            runtime.governance().recordActivity(player.getUniqueId());
            return runtime.governance().dashboard(player.getUniqueId()).orElse(null);
        }, governance -> {
            if (governance == null) {
                return;
            }
            if (governance.requiresRulesConfirmation()) {
                player.sendMessage(plugin.messages().component("chat.notification.rules-updated")
                        .append(presentation.callbackButton(player, "chat.buttons.view-rules",
                                () -> openMain(player))));
            }
            if (governance.pendingTransfer() != null) {
                player.sendMessage(plugin.messages().component("chat.notification.mayor-transfer",
                                Map.of("town", governance.townName()))
                        .append(presentation.callbackButton(player, "chat.buttons.handle-transfer",
                                () -> openMain(player))));
            }
            List<VoteSnapshot> pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).toList();
            if (!pendingVotes.isEmpty()) {
                player.sendMessage(plugin.messages().component("chat.notification.pending-votes",
                                Map.of("count", pendingVotes.size()))
                        .append(presentation.callbackButton(player, "chat.buttons.view-votes",
                                () -> openMain(player))));
                pendingVotes.forEach(vote -> facade.sendVoteReminder(player, vote));
            }
        });
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElse(null), finance -> {
            if (runtime.taxEnabled() && finance != null && finance.hasUnreadTaxChange()) {
                player.sendMessage(plugin.messages().component("chat.notification.tax-updated", Map.of(
                                "rate", TownRuntime.percent(finance.taxRateBps())))
                        .append(presentation.callbackButton(player, "chat.buttons.view-finance",
                                () -> facade.openFinance(player, 0))));
            }
        });
        runtime.read(player, () -> runtime.repository()
                .listPendingInitialMemberApplications(player.getUniqueId()), applications ->
                applications.stream().findFirst().ifPresent(application -> facade.sendInitialMemberReminder(
                        player, application)));
    }

    private void renderMain(Player player, MainView view) {
        TownRepository.PlayerDashboard dashboard = view.dashboard();
        MemberGovernanceSnapshot governance = view.governance();
        if (governance != null && governance.requiresRulesConfirmation()) {
            facade.renderRulesConfirmation(player, governance);
            return;
        }
        List<MenuItem> items = new ArrayList<>();
        int pendingVotes = governance == null ? 0 : (int) governance.votes().stream()
                .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
        int pendingJoins = governance != null && governance.canReviewApplications()
                ? dashboard.incomingJoinApplications().size() : 0;
        int pendingTransfer = governance != null && governance.pendingTransfer() != null ? 1 : 0;
        int pendingTotal = pendingVotes + pendingJoins + pendingTransfer + view.invitations().size();
        if (dashboard.town() != null) {
            TownSnapshot town = dashboard.town();
            EconomyRepository.TownFinance finance = view.finance();
            List<String> summary = new ArrayList<>();
            summary.add(presentation.dialogText("member-role.identity", Map.of("role", governance == null
                    ? presentation.dialogText("member-role.member") : facade.memberRoleText(governance.role()))));
            if (finance != null) {
                summary.add(presentation.dialogText("main.finance-summary", Map.of(
                        "balance", runtime.money(finance.balanceMinor()),
                        "taxRate", TownRuntime.percent(finance.taxRateBps()))));
                summary.add(presentation.dialogText("common.territory-units", Map.of(
                        "count", finance.unitCount(),
                        "maximum", runtime.economySettings().maximumUnits())));
            }
            summary.add(pendingTotal > 0
                    ? presentation.dialogText("pending.summary", Map.of(
                    "joins", pendingJoins, "votes", pendingVotes, "transfers", pendingTransfer,
                    "invitations", view.invitations().size()))
                    : presentation.dialogText("main.no-pending"));
            items.add(new MenuItem(0, presentation.button(Material.BELL, presentation.dialogText("common.town-name", Map.of(
                    "town", town.profile().name())),
                    summary, null, null)));
            items.add(new MenuItem(10, presentation.button(Material.WRITTEN_BOOK, presentation.dialogText("main.town-info"),
                    List.of(presentation.dialogText("tooltip.main.town")), "TOWN", town.id().toString())));
            items.add(new MenuItem(12, presentation.button(Material.EMERALD_BLOCK, presentation.dialogText("common.finance-title"),
                    List.of(presentation.dialogText("tooltip.main.finance")), "FINANCE", "0")));
            items.add(new MenuItem(14, presentation.button(Material.GOLDEN_HELMET, presentation.dialogText("main.governance"),
                    List.of(presentation.dialogText("tooltip.main.governance")), "GOVERNANCE_CENTER", null)));
            items.add(new MenuItem(18, presentation.button(Material.PLAYER_HEAD, presentation.dialogText("main.personal"),
                    List.of(presentation.dialogText("tooltip.main.personal")), "PERSONAL_CENTER", null)));
        } else if (dashboard.application() != null) {
            ApplicationSnapshot application = dashboard.application();
            items.add(new MenuItem(0, presentation.button(Material.PAPER, presentation.dialogText("main.application-title"),
                    List.of(application.reviewMessage() == null
                            ? presentation.dialogText("main.application-incomplete")
                            : presentation.dialogText("common.admin-review-message", Map.of(
                                    "message", TownUiLegacyFacade.safeText(application.reviewMessage())))), null, null)));
            items.add(new MenuItem(11, presentation.button(Material.MAP, presentation.dialogText("main.application-continue"),
                    List.of(application.reviewMessage() == null
                            ? presentation.dialogText("tooltip.main.application-summary")
                            : presentation.dialogText("common.admin-review-message",
                            Map.of("message", TownUiLegacyFacade.safeText(application.reviewMessage())))),
                    "APPLICATION", application.id().toString())));
        } else {
            items.add(new MenuItem(0, presentation.button(Material.BELL, presentation.dialogText("main.title"),
                    List.of(presentation.dialogText("main.no-town"), presentation.dialogText("main.no-town-actions")),
                    null, null)));
            if (dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(11, presentation.button(Material.WRITABLE_BOOK,
                        presentation.dialogText("main.create-application"),
                        List.of(presentation.dialogText("tooltip.main.create-application")),
                        "CREATE_APPLICATION", null)));
            }
            items.add(new MenuItem(13, presentation.button(Material.COMPASS, presentation.dialogText("main.join-application"),
                    List.of(presentation.dialogText("tooltip.main.join-towns")), "JOIN_TOWNS", null)));
            if (!dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(15, presentation.button(Material.PAPER,
                        presentation.dialogText("main.my-join-applications"),
                        List.of(presentation.dialogText("tooltip.main.my-applications-count",
                                        Map.of("count", dashboard.joinApplications().size())),
                                presentation.dialogText("common.join-application-limit")),
                        "MY_JOIN_APPLICATIONS", null)));
            }
            items.add(new MenuItem(31, presentation.button(Material.WRITTEN_BOOK, presentation.dialogText("common.handbook"),
                    List.of(presentation.dialogText("tooltip.main.handbook")), "GIVE_HANDBOOK", null)));
        }
        items.add(new MenuItem(16, presentation.button(pendingTotal > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                pendingTotal > 0 ? presentation.dialogText("main.pending-count",
                        Map.of("count", pendingTotal)) : presentation.dialogText("main.pending"),
                List.of(presentation.dialogText("tooltip.main.pending")), "PENDING_CENTER", null)));
        if (player.hasPermission("tianjitown.admin")) {
            boolean pending = !view.reviewQueue().isEmpty();
            items.add(new MenuItem(30, presentation.button(pending ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pending ? presentation.dialogText("main.admin-review-count",
                            Map.of("count", view.reviewQueue().size()))
                            : presentation.dialogText("main.admin-review"),
                    pending ? List.of(presentation.dialogText("tooltip.main.admin-review-pending"))
                            : List.of(presentation.dialogText("tooltip.main.admin-review-empty")),
                    "ADMIN_APPLICATIONS", null)));
        }
        presentation.openMenu(player, 36, presentation.dialogText("main.title"), DialogRoute.ROOT, items);
    }

    public void openPendingCenter(Player player) {
        openPendingCenter(player, 0);
    }

    public void openPendingCenter(Player player, int requestedPage) {
        runtime.read(player, () -> new PendingView(
                runtime.repository().dashboard(player.getUniqueId()),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null),
                runtime.repository().listPendingInitialMemberApplications(player.getUniqueId())), view -> {
            if (!player.isOnline()) return;
            TownSnapshot town = view.dashboard().town();
            MemberGovernanceSnapshot governance = view.governance();
            int pendingJoins = town != null && governance != null && governance.canReviewApplications()
                    ? view.dashboard().incomingJoinApplications().size() : 0;
            int pendingVotes = governance == null ? 0 : (int) governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            int pendingTransfer = governance == null || governance.pendingTransfer() == null ? 0 : 1;
            int total = pendingJoins + pendingVotes + pendingTransfer + view.invitations().size();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, presentation.button(total > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    total > 0 ? presentation.dialogText("pending.count-title", Map.of("count", total))
                            : presentation.dialogText("pending.empty-title"),
                    total > 0 ? List.of(presentation.dialogText("pending.decisions-only"),
                                    presentation.dialogText("pending.summary", Map.of("joins", pendingJoins,
                                    "votes", pendingVotes, "transfers", pendingTransfer,
                                    "invitations", view.invitations().size())))
                            : List.of(presentation.dialogText("votes.pending-empty-hint")), null, null)));
            if (pendingJoins > 0) {
                items.add(new MenuItem(10, presentation.button(Material.ENCHANTED_BOOK,
                        presentation.dialogText("common.applications-count", Map.of("count", pendingJoins)),
                        List.of(presentation.dialogText("tooltip.pending.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            if (pendingVotes > 0 && town != null) {
                items.add(new MenuItem(12, presentation.button(Material.ENCHANTED_BOOK,
                        presentation.dialogText("votes.pending-title"),
                        List.of(presentation.dialogText("tooltip.pending.votes")),
                        "VOTES", town.id().toString())));
            }
            if (pendingTransfer > 0) {
                items.add(new MenuItem(14, presentation.button(Material.NETHER_STAR,
                        presentation.dialogText("pending.transfer"),
                        List.of(presentation.dialogText("tooltip.pending.transfer")),
                        "TRANSFER_REQUEST", governance.pendingTransfer().id().toString())));
            }
            int page = Math.max(0, Math.min(requestedPage, (view.invitations().size() - 1) / 8));
            int slot = 27;
            for (ApplicationSnapshot invitation : TownUiLegacyFacade.page(view.invitations(), page, 8)) {
                items.add(new MenuItem(slot++, presentation.button(Material.WRITABLE_BOOK,
                        presentation.dialogText("pending.invitation", Map.of("town",
                                TownUiLegacyFacade.safeText(invitation.text().name()))),
                        List.of(presentation.dialogText("invitation.message", Map.of("player",
                                TownUiLegacyFacade.safeText(facade.displayName(invitation.applicantId())),
                                "town", TownUiLegacyFacade.safeText(invitation.text().name())))),
                        "INITIAL_MEMBER_INVITATION", invitation.id().toString())));
            }
            if (page > 0) {
                items.add(new MenuItem(45, presentation.button(Material.ARROW,
                        presentation.dialogText("common.previous"), List.of(), "PENDING_CENTER",
                        Integer.toString(page - 1))));
            }
            if (TownUiLegacyFacade.hasNext(view.invitations(), page, 8)) {
                items.add(new MenuItem(53, presentation.button(Material.ARROW,
                        presentation.dialogText("common.next"), List.of(), "PENDING_CENTER",
                        Integer.toString(page + 1))));
            }
            presentation.openMenu(player, 54, presentation.dialogText("pending.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void openPersonalCenter(Player player) {
        runtime.read(player, () -> runtime.repository().dashboard(player.getUniqueId()), dashboard -> {
            TownSnapshot town = dashboard.town();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, presentation.button(Material.PLAYER_HEAD,
                    presentation.dialogText("personal.player", Map.of("player", TownUiLegacyFacade.safeText(player.getName()))),
                    List.of(town == null ? presentation.dialogText("personal.no-town")
                            : presentation.dialogText("personal.town", Map.of(
                                    "town", TownUiLegacyFacade.safeText(town.profile().name()))),
                            presentation.dialogText("personal.handbook-hint")), null, null)));
            items.add(new MenuItem(10, presentation.button(Material.WRITTEN_BOOK,
                    presentation.dialogText("common.handbook"),
                    List.of(presentation.dialogText("tooltip.personal.handbook")),
                    "GIVE_HANDBOOK", null)));
            if (town != null && town.mayorId().equals(player.getUniqueId())) {
                items.add(new MenuItem(16, presentation.button(Material.TNT, presentation.dialogText("personal.disband"),
                        List.of(presentation.dialogText("tooltip.personal.disband-only"),
                                presentation.dialogText("common.irreversible")),
                        "CONFIRM_DISBAND", town.id() + ":" + town.version())));
            } else if (town != null) {
                items.add(new MenuItem(16, presentation.button(Material.OAK_DOOR, presentation.dialogText("personal.leave"),
                        List.of(presentation.dialogText("tooltip.personal.leave")),
                        "CONFIRM_LEAVE", town.id().toString())));
            }
            presentation.openMenu(player, 27, presentation.dialogText("personal.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void openTown(Player player, UUID townId) {
        runtime.read(player, () -> new TownDetailsView(runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                plugin.messages().plainText("chat.runtime.town-not-found"))),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.town();
            MemberGovernanceSnapshot governance = view.governance();
            TownDetailsMenuModel townDetails = TownDetailsMenuModel.create(
                    plugin.messages(), town);
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, presentation.button(Material.BELL, presentation.dialogText("common.town-name", Map.of(
                            "town", TownUiLegacyFacade.safeText(town.profile().name()))),
                    townDetails.summaryLore(), null, null)));
            TownDetailsMenuModel.RulesEntry rulesEntry = townDetails.rulesEntry();
            items.add(new MenuItem(rulesEntry.slot(), presentation.button(Material.WRITTEN_BOOK,
                    presentation.dialogText(rulesEntry.labelKey()), List.of(presentation.dialogText(rulesEntry.tooltipKey())),
                    rulesEntry.action(), rulesEntry.target())));
            items.add(new MenuItem(12, presentation.button(Material.PLAYER_HEAD, presentation.dialogText("town.members"),
                    List.of(presentation.dialogText("tooltip.town.members")), "TOWN_MEMBER_OVERVIEW",
                    town.id() + ":0")));
            if (governance != null && governance.role().isLeader()) {
                items.add(new MenuItem(13, presentation.button(Material.WRITABLE_BOOK,
                        presentation.dialogText("town.edit-description"),
                        List.of(presentation.dialogText("tooltip.town.edit-description")),
                        "EDIT_TOWN_DESCRIPTION", town.id().toString())));
                items.add(new MenuItem(14, presentation.button(Material.PAPER, presentation.dialogText("town.edit-rules"),
                        List.of(presentation.dialogText("tooltip.town.edit-rules")),
                        "EDIT_TOWN_RULES", town.id().toString())));
            }
            if (town.territory() != null) {
                items.add(new MenuItem(16, presentation.button(Material.MAP, presentation.dialogText("town.territory"),
                        TownTerritoryUi.townTerritoryLore(plugin.messages(), town.territory()),
                        "PREVIEW_TOWN",
                        town.id().toString())));
            }
            if (governance != null && governance.role() == MemberRole.MAYOR) {
                items.add(new MenuItem(17, presentation.button(Material.ENDER_PEARL,
                        presentation.dialogText("town.set-teleport"),
                        List.of(presentation.dialogText("tooltip.town.set-teleport")),
                        "SET_TOWN_TELEPORT", town.id().toString())));
            }
            presentation.openMenu(player, 27, presentation.dialogText("town.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void leave(Player player, UUID townId) {
        actions.leaveTown(player, townId, outcome -> facade.handleOutcome(player, outcome, result -> {
            presentation.openNotice(player, presentation.dialogText("notice.left-town-title"),
                    presentation.dialogText("notice.left-town-message"),
                    presentation.dialogText("common.back"), "MAIN", null);
        }));
    }

    public void disband(Player mayor, String target) {
        String[] parts = target.split(":", 2);
        UUID townId = UUID.fromString(parts[0]);
        long expectedVersion = Long.parseLong(parts[1]);
        actions.disbandTown(mayor, townId, expectedVersion, outcome ->
                facade.handleOutcome(mayor, outcome, completed -> {
                presentation.playSound(mayor, Sound.BLOCK_ANVIL_LAND);
                presentation.openNotice(mayor, presentation.dialogText("notice.disbanded-title"),
                        presentation.dialogText("notice.disbanded-message", Map.of(
                                "town", completed.profile().name())),
                        presentation.dialogText("common.back"), "MAIN", null);
            }));
    }

    private record MainView(TownRepository.PlayerDashboard dashboard,
                            MemberGovernanceSnapshot governance,
                            EconomyRepository.TownFinance finance,
                            List<ApplicationSnapshot> reviewQueue,
                            List<ApplicationSnapshot> invitations) {
    }

    private record PendingView(TownRepository.PlayerDashboard dashboard,
                               MemberGovernanceSnapshot governance,
                               List<ApplicationSnapshot> invitations) {
    }

    private record TownDetailsView(TownSnapshot town, MemberGovernanceSnapshot governance) {
    }
}

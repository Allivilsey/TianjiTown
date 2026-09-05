package org.allivlisey.tianjitown.paper.ui.home;
import org.allivlisey.tianjitown.paper.runtime.TownActions;
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

import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade.GovernanceCenterView;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Loads dashboards and town details, and handles leaving or disbanding a town. */
public final class TownHomeDialogs {
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownHomeDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openMain(Player player) {
        if (facade.maintenanceMode()) {
            plugin.messages().send(player, "system.maintenance");
            return;
        }
        UUID request = facade.openMenu(player, 9, facade.dialogText("main.loading-title"), DialogRoute.ROOT,
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
                            ? runtime.repository().listReviewQueue(100) : List.of());
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
                        .append(facade.callbackButton(player, "chat.buttons.view-rules",
                                () -> openMain(player))));
            }
            if (governance.pendingTransfer() != null) {
                player.sendMessage(plugin.messages().component("chat.notification.mayor-transfer",
                                Map.of("town", governance.townName()))
                        .append(facade.callbackButton(player, "chat.buttons.handle-transfer",
                                () -> openMain(player))));
            }
            List<VoteSnapshot> pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).toList();
            if (!pendingVotes.isEmpty()) {
                player.sendMessage(plugin.messages().component("chat.notification.pending-votes",
                                Map.of("count", pendingVotes.size()))
                        .append(facade.callbackButton(player, "chat.buttons.view-votes",
                                () -> openMain(player))));
                pendingVotes.forEach(vote -> facade.sendVoteReminder(player, vote));
            }
        });
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElse(null), finance -> {
            if (runtime.taxEnabled() && finance != null && finance.hasUnreadTaxChange()) {
                player.sendMessage(plugin.messages().component("chat.notification.tax-updated", Map.of(
                                "rate", TownRuntime.percent(finance.taxRateBps())))
                        .append(facade.callbackButton(player, "chat.buttons.view-finance",
                                () -> facade.openFinance(player, 0))));
            }
        });
        runtime.read(player, () -> runtime.repository()
                .listPendingInitialMemberApplications(player.getUniqueId()), applications ->
                applications.forEach(application -> facade.sendInitialMemberReminder(
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
        if (dashboard.town() != null) {
            TownSnapshot town = dashboard.town();
            EconomyRepository.TownFinance finance = view.finance();
            int pendingVotes = governance == null ? 0 : (int) governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            int pendingJoins = governance != null && governance.canReviewApplications()
                    ? dashboard.incomingJoinApplications().size() : 0;
            int pendingTransfer = governance != null && governance.pendingTransfer() != null ? 1 : 0;
            int pendingTotal = pendingVotes + pendingJoins + pendingTransfer;
            List<String> summary = new ArrayList<>();
            summary.add(facade.dialogText("member-role.identity", Map.of("role", governance == null
                    ? facade.dialogText("member-role.member") : facade.memberRoleText(governance.role()))));
            if (finance != null) {
                summary.add(facade.dialogText("main.finance-summary", Map.of(
                        "balance", runtime.money(finance.balanceMinor()),
                        "taxRate", TownRuntime.percent(finance.taxRateBps()))));
                summary.add(facade.dialogText("common.territory-units", Map.of(
                        "count", finance.unitCount(),
                        "maximum", runtime.economySettings().maximumUnits())));
            }
            summary.add(pendingTotal > 0
                    ? facade.dialogText("votes.main-pending", Map.of("total", pendingTotal,
                    "joins", pendingJoins, "votes", pendingVotes, "transfers", pendingTransfer))
                    : facade.dialogText("main.no-pending"));
            items.add(new MenuItem(0, facade.button(Material.BELL, facade.dialogText("common.town-name", Map.of(
                    "town", town.profile().name())),
                    summary, null, null)));
            items.add(new MenuItem(10, facade.button(Material.WRITTEN_BOOK, facade.dialogText("main.town-info"),
                    List.of(facade.dialogText("tooltip.main.town")), "TOWN", town.id().toString())));
            items.add(new MenuItem(12, facade.button(Material.EMERALD_BLOCK, facade.dialogText("common.finance-title"),
                    List.of(facade.dialogText("tooltip.main.finance")), "FINANCE", "0")));
            items.add(new MenuItem(14, facade.button(Material.GOLDEN_HELMET, facade.dialogText("main.governance"),
                    List.of(facade.dialogText("tooltip.main.governance")), "GOVERNANCE_CENTER", null)));
            items.add(new MenuItem(16, facade.button(pendingTotal > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pendingTotal > 0 ? facade.dialogText("main.pending-count",
                            Map.of("count", pendingTotal)) : facade.dialogText("main.pending"),
                    List.of(facade.dialogText("tooltip.main.pending")), "PENDING_CENTER", null)));
            items.add(new MenuItem(18, facade.button(Material.PLAYER_HEAD, facade.dialogText("main.personal"),
                    List.of(facade.dialogText("tooltip.main.personal")), "PERSONAL_CENTER", null)));
        } else if (dashboard.application() != null) {
            ApplicationSnapshot application = dashboard.application();
            items.add(new MenuItem(0, facade.button(Material.PAPER, facade.dialogText("main.application-title"),
                    List.of(application.reviewMessage() == null
                            ? facade.dialogText("main.application-incomplete")
                            : facade.dialogText("common.admin-review-message", Map.of(
                                    "message", TownUiLegacyFacade.safeText(application.reviewMessage())))), null, null)));
            items.add(new MenuItem(11, facade.button(Material.MAP, facade.dialogText("main.application-continue"),
                    List.of(application.reviewMessage() == null
                            ? facade.dialogText("tooltip.main.application-summary")
                            : facade.dialogText("common.admin-review-message",
                            Map.of("message", TownUiLegacyFacade.safeText(application.reviewMessage())))),
                    "APPLICATION", application.id().toString())));
        } else {
            items.add(new MenuItem(0, facade.button(Material.BELL, facade.dialogText("main.title"),
                    List.of(facade.dialogText("main.no-town"), facade.dialogText("main.no-town-actions")),
                    null, null)));
            if (dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(11, facade.button(Material.WRITABLE_BOOK,
                        facade.dialogText("main.create-application"),
                        List.of(facade.dialogText("tooltip.main.create-application")),
                        "CREATE_APPLICATION", null)));
            }
            items.add(new MenuItem(13, facade.button(Material.COMPASS, facade.dialogText("main.join-application"),
                    List.of(facade.dialogText("tooltip.main.join-towns")), "JOIN_TOWNS", null)));
            if (!dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(15, facade.button(Material.PAPER,
                        facade.dialogText("main.my-join-applications"),
                        List.of(facade.dialogText("tooltip.main.my-applications-count",
                                        Map.of("count", dashboard.joinApplications().size())),
                                facade.dialogText("common.join-application-limit")),
                        "MY_JOIN_APPLICATIONS", null)));
            }
            items.add(new MenuItem(31, facade.button(Material.WRITTEN_BOOK, facade.dialogText("common.handbook"),
                    List.of(facade.dialogText("tooltip.main.handbook")), "GIVE_HANDBOOK", null)));
        }
        if (player.hasPermission("tianjitown.admin")) {
            boolean pending = !view.reviewQueue().isEmpty();
            items.add(new MenuItem(30, facade.button(pending ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pending ? facade.dialogText("main.admin-review-count",
                            Map.of("count", view.reviewQueue().size()))
                            : facade.dialogText("main.admin-review"),
                    pending ? List.of(facade.dialogText("tooltip.main.admin-review-pending"))
                            : List.of(facade.dialogText("tooltip.main.admin-review-empty")),
                    "ADMIN_APPLICATIONS", null)));
        }
        facade.openMenu(player, 36, facade.dialogText("main.title"), DialogRoute.ROOT, items);
    }

    public void openPendingCenter(Player player) {
        runtime.read(player, () -> new GovernanceCenterView(
                runtime.repository().dashboard(player.getUniqueId()),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.dashboard().town();
            MemberGovernanceSnapshot governance = view.governance();
            if (town == null || governance == null) {
                openMain(player);
                return;
            }
            int pendingJoins = governance.canReviewApplications()
                    ? view.dashboard().incomingJoinApplications().size() : 0;
            int pendingVotes = (int) governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            int pendingTransfer = governance.pendingTransfer() == null ? 0 : 1;
            int total = pendingJoins + pendingVotes + pendingTransfer;
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, facade.button(total > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    total > 0 ? facade.dialogText("pending.count-title", Map.of("count", total))
                            : facade.dialogText("pending.empty-title"),
                    total > 0 ? List.of(facade.dialogText("pending.decisions-only"),
                            facade.dialogText("votes.pending-summary", Map.of("joins", pendingJoins,
                                    "votes", pendingVotes, "transfers", pendingTransfer)))
                            : List.of(facade.dialogText("votes.pending-empty-hint")), null, null)));
            if (pendingJoins > 0) {
                items.add(new MenuItem(10, facade.button(Material.ENCHANTED_BOOK,
                        facade.dialogText("common.applications-count", Map.of("count", pendingJoins)),
                        List.of(facade.dialogText("tooltip.pending.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            if (pendingVotes > 0) {
                items.add(new MenuItem(12, facade.button(Material.ENCHANTED_BOOK,
                        facade.dialogText("votes.pending-title", Map.of("count", pendingVotes)),
                        List.of(facade.dialogText("tooltip.pending.votes")),
                        "VOTES", town.id().toString())));
            }
            if (governance.pendingTransfer() != null) {
                items.add(new MenuItem(14, facade.button(Material.NETHER_STAR,
                        facade.dialogText("pending.transfer"),
                        List.of(facade.dialogText("tooltip.pending.transfer")),
                        "TRANSFER_REQUEST", governance.pendingTransfer().id().toString())));
            }
            facade.openMenu(player, 27, facade.dialogText("pending.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void openPersonalCenter(Player player) {
        runtime.read(player, () -> runtime.repository().dashboard(player.getUniqueId()), dashboard -> {
            TownSnapshot town = dashboard.town();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, facade.button(Material.PLAYER_HEAD,
                    facade.dialogText("personal.player", Map.of("player", TownUiLegacyFacade.safeText(player.getName()))),
                    List.of(town == null ? facade.dialogText("personal.no-town")
                            : facade.dialogText("personal.town", Map.of(
                                    "town", TownUiLegacyFacade.safeText(town.profile().name()))),
                            facade.dialogText("personal.handbook-hint")), null, null)));
            items.add(new MenuItem(10, facade.button(Material.WRITTEN_BOOK,
                    facade.dialogText("common.handbook"),
                    List.of(facade.dialogText("tooltip.personal.handbook")),
                    "GIVE_HANDBOOK", null)));
            if (town != null && town.mayorId().equals(player.getUniqueId())) {
                items.add(new MenuItem(16, facade.button(Material.TNT, facade.dialogText("personal.disband"),
                        List.of(facade.dialogText("tooltip.personal.disband-only"),
                                facade.dialogText("common.irreversible")),
                        "CONFIRM_DISBAND", town.id() + ":" + town.version())));
            } else if (town != null) {
                items.add(new MenuItem(16, facade.button(Material.OAK_DOOR, facade.dialogText("personal.leave"),
                        List.of(facade.dialogText("tooltip.personal.leave")),
                        "CONFIRM_LEAVE", town.id().toString())));
            }
            facade.openMenu(player, 27, facade.dialogText("personal.title"),
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
            items.add(new MenuItem(4, facade.button(Material.BELL, facade.dialogText("common.town-name", Map.of(
                            "town", TownUiLegacyFacade.safeText(town.profile().name()))),
                    townDetails.summaryLore(), null, null)));
            TownDetailsMenuModel.RulesEntry rulesEntry = townDetails.rulesEntry();
            items.add(new MenuItem(rulesEntry.slot(), facade.button(Material.WRITTEN_BOOK,
                    facade.dialogText(rulesEntry.labelKey()), List.of(facade.dialogText(rulesEntry.tooltipKey())),
                    rulesEntry.action(), rulesEntry.target())));
            items.add(new MenuItem(12, facade.button(Material.PLAYER_HEAD, facade.dialogText("town.members"),
                    List.of(facade.dialogText("tooltip.town.members")), "TOWN_MEMBER_OVERVIEW",
                    town.id() + ":0")));
            if (governance != null && governance.role().isLeader()) {
                items.add(new MenuItem(13, facade.button(Material.WRITABLE_BOOK,
                        facade.dialogText("town.edit-description"),
                        List.of(facade.dialogText("tooltip.town.edit-description")),
                        "EDIT_TOWN_DESCRIPTION", town.id().toString())));
                items.add(new MenuItem(14, facade.button(Material.PAPER, facade.dialogText("town.edit-rules"),
                        List.of(facade.dialogText("tooltip.town.edit-rules")),
                        "EDIT_TOWN_RULES", town.id().toString())));
            }
            if (town.territory() != null) {
                items.add(new MenuItem(16, facade.button(Material.MAP, facade.dialogText("town.territory"),
                        TownTerritoryUi.townTerritoryLore(plugin.messages(), town.territory()),
                        "PREVIEW_TOWN",
                        town.id().toString())));
            }
            if (governance != null && governance.role() == MemberRole.MAYOR) {
                items.add(new MenuItem(17, facade.button(Material.ENDER_PEARL,
                        facade.dialogText("town.set-teleport"),
                        List.of(facade.dialogText("tooltip.town.set-teleport")),
                        "SET_TOWN_TELEPORT", town.id().toString())));
            }
            facade.openMenu(player, 27, facade.dialogText("town.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void leave(Player player, UUID townId) {
        actions.leaveTown(player, townId, outcome -> facade.handleOutcome(player, outcome, result -> {
            facade.openNotice(player, facade.dialogText("notice.left-town-title"),
                    facade.dialogText("notice.left-town-message"),
                    facade.dialogText("common.back"), "MAIN", null);
        }));
    }

    public void disband(Player mayor, String target) {
        String[] parts = target.split(":", 2);
        UUID townId = UUID.fromString(parts[0]);
        long expectedVersion = Long.parseLong(parts[1]);
        actions.disbandTown(mayor, townId, expectedVersion, outcome ->
                facade.handleOutcome(mayor, outcome, completed -> {
                facade.playSound(mayor, Sound.BLOCK_ANVIL_LAND);
                facade.openNotice(mayor, facade.dialogText("notice.disbanded-title"),
                        facade.dialogText("notice.disbanded-message", Map.of(
                                "town", completed.profile().name())),
                        facade.dialogText("common.back"), "MAIN", null);
            }));
    }

    private record MainView(TownRepository.PlayerDashboard dashboard,
                            MemberGovernanceSnapshot governance,
                            EconomyRepository.TownFinance finance,
                            List<ApplicationSnapshot> reviewQueue) {
    }

    private record TownDetailsView(TownSnapshot town, MemberGovernanceSnapshot governance) {
    }
}

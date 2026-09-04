package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Composition root and compatibility facade for the town UI. */
final class TownUiController implements Listener {
    private final TianjiTownPlugin plugin;
    private final TownUiLegacyFacade facade;
    private final ServiceStationController serviceStations;
    private final TownTerritoryUi territoryUi;
    private final TownApplicationFormUi applicationFormUi;
    private final TownAdminApplicationUi adminApplicationUi;

    TownUiController(TianjiTownPlugin plugin, TownRuntime runtime, TownActions actions) {
        this.plugin = plugin;
        facade = new TownUiLegacyFacade(plugin, runtime, actions);
        serviceStations = new ServiceStationController(plugin, runtime, facade::openMain,
                facade.dialogs()::isActive);
        TownHomeUi homeUi = new TownHomeUi(facade, serviceStations);
        TownBuffShopUi buffShop = new TownBuffShopUi(facade);
        TownFinanceUi financeUi = new TownFinanceUi(facade);
        territoryUi = new TownTerritoryUi(facade);
        TownMembershipUi membershipUi = new TownMembershipUi(facade);
        TownGovernanceUi governanceUi = new TownGovernanceUi(facade);
        TownJoinApplicationUi joinApplicationUi = new TownJoinApplicationUi(facade);
        adminApplicationUi = new TownAdminApplicationUi(facade);
        TownApplicationUi applicationUi = new TownApplicationUi(facade);
        applicationFormUi = new TownApplicationFormUi(facade);
        facade.setApplicationFormUi(applicationFormUi);
        facade.setApplicationDecisionNotifier(adminApplicationUi::notifyDecision);
        facade.setRouter(TownUiActionRouter.builder(facade::blockForMaintenance, facade::openStaleMenu)
                .register("MAIN", (player, target) -> homeUi.open(player))
                .register("CLOSE", (player, target) -> facade.closeUi(player))
                .registerAll(homeUi::route, "GIVE_HANDBOOK", "TOWN", "PERSONAL_CENTER",
                        "CONFIRM_LEAVE", "LEAVE", "CONFIRM_DISBAND", "DISBAND")
                .registerAll(buffShop::route, "BUFF_SHOP", "BUFF_DURATIONS", "BUY_BUFF")
                .registerAll(financeUi::route, "FINANCE", "TAX_MENU", "LEDGER", "DONATION_INPUT")
                .registerAll(territoryUi::route, "EXPANSION_MENU", "TOGGLE_EXPANSION",
                        "CLEAR_EXPANSION_SELECTION", "CONFIRM_EXPANSION_BATCH", "PREVIEW_EXPANSION",
                        "EXPAND", "PREVIEW_TOWN", "SET_TOWN_TELEPORT")
                .registerAll(membershipUi::route, "TOWN_MEMBER_OVERVIEW", "VISITOR_CENTER",
                        "VISITOR_LIST", "VISITOR_INVITE", "CONFIRM_ADD_VISITOR", "ADD_VISITOR",
                        "CONFIRM_REMOVE_VISITOR", "REMOVE_VISITOR", "MEMBERS", "MEMBER_DETAIL",
                        "CONFIRM_ROLE", "SET_ROLE", "CONFIRM_KICK_MEMBER", "KICK_MEMBER",
                        "CONFIRM_TRANSFER_MAYOR", "REQUEST_TRANSFER_MAYOR", "TRANSFER_REQUEST",
                        "CONFIRM_TRANSFER_DECISION", "TRANSFER_DECISION")
                .registerAll(governanceUi::route, "GOVERNANCE_CENTER", "PENDING_CENTER", "TOWN_RULES",
                        "JOIN_TOWN_RULES", "EDIT_TOWN_RULES", "VOTES", "VOTES_PAGE", "VOTE_DETAIL",
                        "CONFIRM_CREATE_VOTE", "CREATE_VOTE", "CAST_VOTE", "CONFIRM_CANCEL_VOTE",
                        "CANCEL_VOTE")
                .registerAll(joinApplicationUi::route, "JOIN_TOWNS", "JOIN_TOWNS_PAGE", "JOIN_TOWN",
                        "CONFIRM_APPLY_JOIN", "APPLY_JOIN", "MY_JOIN_APPLICATIONS", "CONFIRM_CANCEL_JOIN",
                        "CANCEL_JOIN", "JOIN_APPLICATIONS", "JOIN_APPLICATIONS_PAGE", "JOIN_APPLICATION",
                        "CONFIRM_APPROVE_JOIN", "APPROVE_JOIN", "CONFIRM_REJECT_JOIN", "REJECT_JOIN")
                .registerAll(adminApplicationUi::route, "ADMIN_APPLICATIONS", "ADMIN_APPLICATIONS_PAGE",
                        "ADMIN_APPLICATION", "CONFIRM_ADMIN_APPROVE", "CONFIRM_ADMIN_REJECT",
                        "CONFIRM_ADMIN_CHANGE", "ADMIN_APPROVE", "CONFIRM_FAILED_RECOVERY",
                        "RECOVER_FAILED", "ADMIN_PREVIEW_SITE")
                .registerAll(applicationUi::route, "APPLICATION", "SELECT_SITE", "PREVIEW_SITE",
                        "REMIND_INITIAL_MEMBERS", "CONFIRM_SUBMIT", "SUBMIT", "CONFIRM_CANCEL", "CANCEL")
                .registerAll(applicationFormUi::route, "CREATE_APPLICATION", "EDIT_APPLICATION",
                        "EDIT_TOWN", "EDIT_TOWN_DESCRIPTION", "APPLICATION_BASICS_FORM",
                        "APPLICATION_CONTENT_FORM", "APPLICATION_MEMBERS_FORM", "SELECT_INITIAL_MEMBER",
                        "CHOOSE_INITIAL_MEMBER", "SAVE_APPLICATION_DRAFT", "SAVE_FORM_DRAFT",
                        "DISCARD_FORM_DRAFT")
                .build());
    }

    List<Listener> listeners() {
        return List.of(facade, this, serviceStations);
    }

    boolean createStation(Player player) {
        return serviceStations.create(player);
    }

    boolean removeStation(Player player) {
        return serviceStations.remove(player);
    }

    void showStationInfo(Player player) {
        serviceStations.showInfo(player);
    }

    void listStations(CommandSender sender) {
        serviceStations.list(sender);
    }

    boolean giveHandbook(Player player, boolean notifyPlayer) {
        return serviceStations.giveHandbook(player, notifyPlayer);
    }

    void close() {
        applicationFormUi.clear();
        territoryUi.clear();
        for (UUID viewerId : facade.closeDialogs()) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                facade.closeUi(viewer);
            }
        }
    }

    void previewTownForAdmin(Player player, TownSnapshot town) {
        territoryUi.previewTownForAdmin(player, town);
    }

    void showAdminApplicationList(CommandSender sender, List<ApplicationSnapshot> applications) {
        adminApplicationUi.showList(sender, applications);
    }

    void notifyApplicationDecision(ApplicationSnapshot application) {
        adminApplicationUi.notifyDecision(application);
    }

    void openFinance(Player player, int page) {
        facade.openFinance(player, page);
    }

    static String safeText(Object value) {
        return TownUiLegacyFacade.safeText(value);
    }

    static List<String> townTerritoryLore(PluginMessages messages, InitialTerritory territory) {
        return TownTerritoryUi.townTerritoryLore(messages, territory);
    }

    static Map<String, String> townNotificationPlaceholders(TownPlayerChange change) {
        return TownMembershipUi.townNotificationPlaceholders(change);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        facade.clearPlayerSession(event.getPlayer());
        territoryUi.clear(event.getPlayer().getUniqueId());
    }
}

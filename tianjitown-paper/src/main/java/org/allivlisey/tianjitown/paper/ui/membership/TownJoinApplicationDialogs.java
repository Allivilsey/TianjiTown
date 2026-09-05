package org.allivlisey.tianjitown.paper.ui.membership;
import org.allivlisey.tianjitown.paper.runtime.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Handles browsing towns, join requests and manager decisions. */
public final class TownJoinApplicationDialogs {
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownJoinApplicationDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openJoinTowns(Player player) {
        openJoinTowns(player, 0);
    }

    public void openJoinTowns(Player player, int requestedPage) {
        int page = Math.max(0, requestedPage);
        runtime.read(player, () -> runtime.repository().listTowns(false).stream()
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .toList(), towns -> {
            List<MenuItem> items = new ArrayList<>();
            List<TownSnapshot> visible = TownUiLegacyFacade.page(towns, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                TownSnapshot town = visible.get(index);
                items.add(new MenuItem(index, facade.button(Material.BELL,
                        facade.dialogText("common.town-name", Map.of(
                                "town", TownUiLegacyFacade.safeText(town.profile().name()))),
                        List.of(facade.dialogText("common.town-code", Map.of(
                                        "code", TownUiLegacyFacade.safeText(town.profile().residenceName()))),
                                facade.dialogText("common.town-description", Map.of(
                                        "description", TownUiLegacyFacade.safeText(TownUiLegacyFacade.preview(
                                                town.profile().description(), 80)))),
                                facade.dialogText("tooltip.join.town-apply")),
                        "JOIN_TOWN", town.id().toString())));
            }
            if (towns.isEmpty()) {
                items.add(new MenuItem(0, facade.button(Material.BELL,
                        facade.dialogText("join.empty-title"),
                        List.of(facade.dialogText("join.empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, facade.button(Material.ARROW,
                        facade.dialogText("common.previous"), List.of(),
                        "JOIN_TOWNS_PAGE", String.valueOf(page - 1))));
            }
            if (TownUiLegacyFacade.hasNext(towns, page, 8)) {
                items.add(new MenuItem(53, facade.button(Material.ARROW,
                        facade.dialogText("common.next"), List.of(),
                        "JOIN_TOWNS_PAGE", String.valueOf(page + 1))));
            }
            facade.openMenu(player, 54, facade.dialogText("join.list-title", Map.of("page", page + 1)),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void openJoinTown(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-unavailable"))), town -> {
            JoinTownMenuModel joinTown = JoinTownMenuModel.create(plugin.messages(), town);
            JoinTownMenuModel.Entry rulesEntry = joinTown.rulesEntry();
            JoinTownMenuModel.Entry applyEntry = joinTown.applyEntry();
            List<MenuItem> items = List.of(
                    new MenuItem(4, facade.button(Material.BELL,
                            facade.dialogText("common.town-name", Map.of(
                                    "town", TownUiLegacyFacade.safeText(town.profile().name()))),
                            joinTown.summaryLore(),
                            null, null)),
                    new MenuItem(rulesEntry.slot(), facade.button(Material.WRITTEN_BOOK,
                            facade.dialogText(rulesEntry.labelKey()), rulesEntry.loreKeys().stream()
                                    .map(facade::dialogText).toList(), rulesEntry.action(),
                            rulesEntry.target())),
                    new MenuItem(applyEntry.slot(), facade.button(Material.LIME_CONCRETE,
                            facade.dialogText(applyEntry.labelKey()), applyEntry.loreKeys().stream()
                                    .map(facade::dialogText).toList(), applyEntry.action(),
                            applyEntry.target())));
            facade.openMenu(player, 27, facade.dialogText("join.town-title", Map.of(
                            "town", TownUiLegacyFacade.safeText(town.profile().name()))),
                    new DialogRoute("JOIN_TOWNS", null), items);
        });
    }

    public void openMyJoinApplications(Player player) {
        runtime.read(player, () -> runtime.repository().listJoinApplications(player.getUniqueId()),
                applications -> {
                    List<MenuItem> items = new ArrayList<>();
                    for (int index = 0; index < Math.min(applications.size(), 45); index++) {
                        JoinApplicationSnapshot application = applications.get(index);
                        items.add(new MenuItem(index, facade.button(Material.PAPER,
                                facade.dialogText("common.town-entry-title", Map.of(
                                        "town", TownUiLegacyFacade.safeText(application.townName()))),
                                List.of(facade.dialogText("common.expires", Map.of(
                                                "time", TownUiLegacyFacade.safeText(application.expiresAt()))),
                                        facade.dialogText("tooltip.my-join.withdraw")),
                                "CONFIRM_CANCEL_JOIN", application.id().toString())));
                    }
                    facade.openMenu(player, 54, facade.dialogText("my-join.list-title", Map.of(
                                    "count", applications.size())),
                            new DialogRoute("MAIN", null), items);
                });
    }

    public void openTownJoinApplications(Player mayor, UUID townId) {
        openTownJoinApplications(mayor, townId, 0);
    }

    public void openTownJoinApplications(Player mayor, UUID townId, int requestedPage) {
        int page = Math.max(0, requestedPage);
        runtime.read(mayor, () -> runtime.repository().listTownJoinApplications(
                townId, mayor.getUniqueId()), applications -> {
            List<MenuItem> items = new ArrayList<>();
            List<JoinApplicationSnapshot> visible = TownUiLegacyFacade.page(applications, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                JoinApplicationSnapshot application = visible.get(index);
                String name = facade.displayName(application.applicantId());
                items.add(new MenuItem(index, facade.button(Material.PLAYER_HEAD,
                        facade.dialogText("town-join.entry-title", Map.of(
                                "applicant", TownUiLegacyFacade.safeText(name))),
                        List.of(facade.dialogText("tooltip.town-join.entry-created", Map.of(
                                        "time", TownUiLegacyFacade.safeText(application.createdAt()))),
                                facade.dialogText("common.expires", Map.of(
                                        "time", TownUiLegacyFacade.safeText(application.expiresAt()))),
                                facade.dialogText("tooltip.town-join.entry-review")),
                        "JOIN_APPLICATION", application.id().toString())));
            }
            if (applications.isEmpty()) {
                items.add(new MenuItem(0, facade.button(Material.BOOK,
                        facade.dialogText("town-join.list-empty"),
                        List.of(facade.dialogText("town-join.list-empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, facade.button(Material.ARROW,
                        facade.dialogText("common.previous"), List.of(),
                        "JOIN_APPLICATIONS_PAGE", townId + ":" + (page - 1))));
            }
            if (TownUiLegacyFacade.hasNext(applications, page, 8)) {
                items.add(new MenuItem(53, facade.button(Material.ARROW,
                        facade.dialogText("common.next"), List.of(),
                        "JOIN_APPLICATIONS_PAGE", townId + ":" + (page + 1))));
            }
            facade.openMenu(mayor, 54, facade.dialogText("town-join.list-title", Map.of(
                            "count", applications.size(), "page", page + 1)),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    public void openTownJoinApplication(Player mayor, UUID applicationId) {
        runtime.read(mayor, () -> runtime.repository().dashboard(mayor.getUniqueId()), dashboard -> {
            JoinApplicationSnapshot application = dashboard.incomingJoinApplications().stream()
                    .filter(candidate -> candidate.id().equals(applicationId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("申请已过期或已处理"));
            String name = Objects.requireNonNullElse(
                    Bukkit.getOfflinePlayer(application.applicantId()).getName(),
                    application.applicantId().toString());
            List<MenuItem> items = List.of(
                    new MenuItem(4, facade.button(Material.PLAYER_HEAD, "§6" + name,
                            List.of("§7玩家 UUID: " + application.applicantId(),
                                    "§7申请时间: " + application.createdAt(),
                                    "§7到期: " + application.expiresAt()), null, null)),
                    new MenuItem(11, facade.button(Material.LIME_CONCRETE, "§a批准加入",
                            List.of(facade.dialogText("tooltip.town-join.approve")),
                            "CONFIRM_APPROVE_JOIN",
                            application.id().toString())),
                    new MenuItem(15, facade.button(Material.RED_CONCRETE, "§c拒绝申请",
                            List.of(facade.dialogText("tooltip.town-join.reject")),
                            "CONFIRM_REJECT_JOIN", application.id().toString())));
            facade.openMenu(mayor, 27, "审核入镇申请",
                    new DialogRoute("JOIN_APPLICATIONS", application.townId().toString()), items);
        });
    }

    public void applyJoin(Player player, UUID townId) {
        actions.applyToTown(player, townId, outcome ->
                facade.handleOutcome(player, outcome, application -> {
                    facade.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyMayorJoinApplication(application);
                    facade.openNotice(player, facade.dialogText("notice.join-submitted-title"),
                            facade.dialogText("notice.join-submitted-message", Map.of(
                                    "expires", application.expiresAt())),
                            facade.dialogText("common.view-my-applications"),
                            "MY_JOIN_APPLICATIONS", null);
                }));
    }

    public void cancelJoin(Player player, UUID applicationId) {
        actions.cancelJoinApplication(player, applicationId, outcome ->
                facade.handleOutcome(player, outcome, ignored -> {
            facade.openNotice(player, facade.dialogText("notice.join-cancelled-title"),
                    facade.dialogText("notice.join-cancelled-message"),
                    facade.dialogText("common.back"),
                    "MY_JOIN_APPLICATIONS", null);
        }));
    }

    public void approveJoin(Player mayor, UUID applicationId) {
        actions.approveJoinApplication(mayor, applicationId, outcome ->
                facade.handleOutcome(mayor, outcome, application -> {
            facade.playSound(mayor, Sound.ENTITY_PLAYER_LEVELUP);
            notifyJoinDecision(application, true);
            facade.openNotice(mayor, facade.dialogText("notice.join-approved-title"),
                    facade.dialogText("notice.join-approved-message"),
                    facade.dialogText("common.back"), "JOIN_APPLICATIONS",
                    application.townId().toString());
        }));
    }

    public void rejectJoin(Player mayor, UUID applicationId) {
        actions.rejectJoinApplication(mayor, applicationId, outcome ->
                facade.handleOutcome(mayor, outcome, application -> {
            notifyJoinDecision(application, false);
            facade.openNotice(mayor, facade.dialogText("notice.join-rejected-title"),
                    facade.dialogText("notice.join-rejected-message"),
                    facade.dialogText("common.back"), "JOIN_APPLICATIONS",
                    application.townId().toString());
        }));
    }

    public void notifyMayorJoinApplication(JoinApplicationSnapshot application) {
        runtime.read(Bukkit.getConsoleSender(), () -> new ManagerNotification(
                runtime.repository().findTown(application.townId()).orElse(null),
                runtime.governance().listManagerIds(application.townId())), notification -> {
            if (notification.town() == null) {
                return;
            }
            String applicant = Objects.requireNonNullElse(
                    Bukkit.getOfflinePlayer(application.applicantId()).getName(),
                    application.applicantId().toString());
            for (UUID managerId : notification.managerIds()) {
                Player manager = Bukkit.getPlayer(managerId);
                if (manager == null) {
                    continue;
                }
                manager.sendMessage(plugin.messages().component("chat.notification.join-request", Map.of(
                                "applicant", applicant, "town", application.townName()))
                        .append(facade.callbackButton(manager, "chat.buttons.review-join",
                                () -> openTownJoinApplication(manager, application.id()))));
                facade.playSound(manager, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
            }
        });
    }

    private void notifyJoinDecision(JoinApplicationSnapshot application, boolean approved) {
        Player applicant = Bukkit.getPlayer(application.applicantId());
        if (applicant == null) {
            return;
        }
        applicant.sendMessage(plugin.messages().component(approved
                        ? "chat.notification.join-approved"
                        : "chat.notification.join-rejected",
                Map.of("town", application.townName()))
                .append(facade.callbackButton(applicant, "chat.buttons.open-system",
                        () -> facade.openMain(applicant))));
        facade.playSound(applicant, approved ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO);
    }

    private record ManagerNotification(TownSnapshot town, List<UUID> managerIds) {
    }
}

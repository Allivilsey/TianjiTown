package org.allivlisey.tianjitown.paper.ui.membership;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Displays member and visitor management and applies membership actions. */
public final class TownMembershipDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownMembershipDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openVisitorCenter(Player player, UUID townId) {
        runtime.read(player, () -> new VisitorCenterView(
                runtime.governance().dashboard(player.getUniqueId()).orElse(null),
                runtime.repository().listVisitorIds(townId).size()), view -> {
            if (rejectVisitorManagement(player, view.governance(), townId)) {
                return;
            }
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, presentation.button(Material.NAME_TAG, presentation.dialogText("visitor.title"),
                    List.of(presentation.dialogText("visitor.current-count", Map.of("count",
                            view.visitorCount()))), null, null)));
            items.add(new MenuItem(11, presentation.button(Material.PLAYER_HEAD, presentation.dialogText("visitor.list"),
                    List.of(presentation.dialogText("tooltip.visitor.list")),
                    "VISITOR_LIST", townId + ":0")));
            items.add(new MenuItem(15, presentation.button(Material.WRITABLE_BOOK, presentation.dialogText("visitor.invite"),
                    List.of(presentation.dialogText("tooltip.visitor.invite")),
                    "VISITOR_INVITE", townId + ":0")));
            presentation.openMenu(player, 27, presentation.dialogText("visitor.title"),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    public void openVisitorList(Player player, UUID townId, int page) {
        runtime.read(player, () -> new VisitorPageView(
                runtime.repository().listVisitors(townId, page, 8),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            if (rejectVisitorManagement(player, view.governance(), townId)) {
                return;
            }
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (TownSnapshot.Visitor visitor : view.page().visitors()) {
                String visitorName = facade.displayName(visitor.playerId());
                String inviterName = facade.displayName(visitor.invitedBy());
                items.add(new MenuItem(slot++, presentation.button(Material.PLAYER_HEAD,
                        presentation.dialogText("visitor.entry-player", Map.of(
                                "player", TownUiLegacyFacade.safeText(visitorName))),
                        List.of(presentation.dialogText("tooltip.visitor.entry.inviter",
                                        Map.of("player", TownUiLegacyFacade.safeText(inviterName))),
                                presentation.dialogText("tooltip.visitor.entry.added",
                                        Map.of("time", visitor.addedAt())),
                                presentation.dialogText("tooltip.visitor.entry.remove")),
                        "CONFIRM_REMOVE_VISITOR",
                        townId + ":" + visitor.playerId() + ":" + page)));
            }
            if (view.page().visitors().isEmpty()) {
                items.add(new MenuItem(4, presentation.button(Material.PAPER, presentation.dialogText("visitor.list-empty"),
                        List.of(presentation.dialogText("visitor.list-empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, presentation.button(Material.ARROW, presentation.dialogText("common.previous"),
                        List.of(),
                        "VISITOR_LIST", townId + ":" + (page - 1))));
            }
            if (view.page().hasNext()) {
                items.add(new MenuItem(53, presentation.button(Material.ARROW, presentation.dialogText("common.next"), List.of(),
                        "VISITOR_LIST", townId + ":" + (page + 1))));
            }
            presentation.openMenu(player, 54, presentation.dialogText("visitor.list-title", Map.of("page", page + 1)),
                    new DialogRoute("VISITOR_CENTER", townId.toString()), items);
        });
    }

    public void openVisitorInvite(Player player, UUID townId, int page) {
        runtime.read(player, () -> new VisitorInviteView(
                runtime.governance().dashboard(player.getUniqueId()).orElse(null),
                runtime.repository().listMemberIds(townId),
                runtime.repository().listVisitorIds(townId)), view -> {
            if (rejectVisitorManagement(player, view.governance(), townId)) {
                return;
            }
            List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream()
                    .filter(candidate -> !view.memberIds().contains(candidate.getUniqueId()))
                    .filter(candidate -> !view.visitorIds().contains(candidate.getUniqueId()))
                    .sorted(java.util.Comparator.comparing(Player::getName,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            List<? extends Player> visible = TownUiLegacyFacade.page(candidates, page, 8);
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (Player candidate : visible) {
                items.add(new MenuItem(slot++, presentation.button(Material.PLAYER_HEAD,
                        presentation.dialogText("visitor.invite-player", Map.of(
                                "player", TownUiLegacyFacade.safeText(candidate.getName()))),
                        List.of(presentation.dialogText("tooltip.visitor.invite-entry.click")),
                        "CONFIRM_ADD_VISITOR",
                        townId + ":" + candidate.getUniqueId() + ":" + page)));
            }
            if (visible.isEmpty()) {
                items.add(new MenuItem(4, presentation.button(Material.PAPER, presentation.dialogText("visitor.no-candidates"),
                        List.of(), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, presentation.button(Material.ARROW, presentation.dialogText("common.previous"),
                        List.of(),
                        "VISITOR_INVITE", townId + ":" + (page - 1))));
            }
            if (TownUiLegacyFacade.hasNext(candidates, page, 8)) {
                items.add(new MenuItem(53, presentation.button(Material.ARROW, presentation.dialogText("common.next"), List.of(),
                        "VISITOR_INVITE", townId + ":" + (page + 1))));
            }
            presentation.openMenu(player, 54, presentation.dialogText("visitor.invite-title", Map.of("page", page + 1)),
                    new DialogRoute("VISITOR_CENTER", townId.toString()), items);
        });
    }

    private boolean rejectVisitorManagement(Player player,
                                            MemberGovernanceSnapshot governance,
                                            UUID townId) {
        if (governance != null && governance.townId().equals(townId)
                && governance.role().isLeader()) {
            return false;
        }
        presentation.openNotice(player, presentation.dialogText("notice.visitor-forbidden-title"),
                presentation.dialogText("notice.visitor-forbidden-message"),
                presentation.dialogText("common.back"), "GOVERNANCE_CENTER", null);
        return true;
    }

    public void openTownMemberOverview(Player player, UUID townId, int page) {
        runtime.read(player, () -> new TownMemberOverview(
                runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                plugin.messages().plainText("chat.runtime.town-not-found"))),
                runtime.repository().listAllMembers(townId)), view -> {
            TownSnapshot.Page memberPage = facade.memberPage(view.members(), page);
            Component content = presentation.dialogComponent("town-members.heading", Map.of(
                    "town", TownUiLegacyFacade.safeText(view.town().profile().name()), "page", page + 1));
            if (memberPage.members().isEmpty()) {
                content = content.append(Component.newline()).append(Component.newline())
                        .append(presentation.dialogComponent("town-members.empty"));
            } else {
                for (int index = 0; index < memberPage.members().size(); index++) {
                    TownSnapshot.Member member = memberPage.members().get(index);
                    content = content.append(Component.newline()).append(Component.newline())
                            .append(presentation.dialogComponent("town-members.item", Map.of(
                                    "index", page * 8 + index + 1,
                                    "name", facade.displayName(member.playerId()),
                                    "role", facade.memberRoleText(member.role()))));
                }
            }
            DialogRoute parent = new DialogRoute("TOWN", townId.toString());
            presentation.openDialogPage(player, presentation.dialogText("town-members.title", Map.of("page", page + 1)),
                    List.of(DialogBody.plainMessage(content, 420)), List.of(),
                    DialogBase.DialogAfterAction.NONE, session -> {
                        List<ActionButton> actions = new ArrayList<>();
                        if (page > 0) {
                            actions.add(ActionButton.create(presentation.dialogComponent("common.previous"),
                                    null, 170, presentation.dialogAction(player, session,
                                            "TOWN_MEMBER_OVERVIEW",
                                            townId + ":" + (page - 1))));
                        }
                        if (memberPage.hasNext()) {
                            actions.add(ActionButton.create(presentation.dialogComponent("common.next"),
                                    null, 170, presentation.dialogAction(player, session,
                                            "TOWN_MEMBER_OVERVIEW",
                                            townId + ":" + (page + 1))));
                        }
                        ActionButton back = presentation.returnButton(player, session, parent);
                        if (actions.isEmpty()) {
                            return DialogType.notice(back);
                        }
                        return DialogType.multiAction(actions)
                                .exitAction(back)
                                .columns(actions.size() == 1 ? 1 : 2)
                                .build();
                    }, parent);
        });
    }

    public void openMembers(Player player, UUID townId, int page) {
        runtime.read(player, () -> new MemberPage(runtime.repository().listAllMembers(townId),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot.Page memberPage = facade.memberPage(view.members(), page);
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (TownSnapshot.Member member : memberPage.members()) {
                String name = facade.displayName(member.playerId());
                String color = switch (member.role()) {
                    case MAYOR -> "§6";
                    case DEPUTY_MAYOR -> "§a";
                    case MEMBER -> "§f";
                };
                boolean sameTown = view.governance() != null
                        && view.governance().townId().equals(townId);
                items.add(new MenuItem(slot++, presentation.button(Material.PLAYER_HEAD,
                        color + name,
                        List.of(presentation.dialogText("tooltip.members.role",
                                        Map.of("role", facade.memberRoleText(member.role()))),
                                presentation.dialogText("tooltip.members.joined",
                                        Map.of("time", member.joinedAt())),
                                sameTown ? presentation.dialogText("tooltip.members.manage")
                                        : presentation.dialogText("tooltip.members.readonly")),
                        sameTown ? "MEMBER_DETAIL" : null,
                        sameTown ? townId + ":" + member.playerId() + ":" + page : null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, presentation.button(Material.ARROW,
                        presentation.dialogText("common.previous"), List.of(),
                        "MEMBERS", townId + ":" + (page - 1))));
            }
            if (memberPage.hasNext()) {
                items.add(new MenuItem(53, presentation.button(Material.ARROW,
                        presentation.dialogText("common.next"), List.of(),
                        "MEMBERS", townId + ":" + (page + 1))));
            }
            presentation.openMenu(player, 54, presentation.dialogText("town-members.title", Map.of("page", page + 1)),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    public void openMemberDetail(Player player, UUID townId, UUID targetId, int page) {
        runtime.read(player, () -> new MemberDetail(
                runtime.governance().dashboard(player.getUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                plugin.messages().plainText("chat.runtime.town-required"))),
                runtime.governance().memberRole(townId, targetId)), view -> {
            if (!view.viewer().townId().equals(townId)) {
                presentation.openNotice(player, presentation.dialogText("notice.member-forbidden-title"),
                        presentation.dialogText("notice.member-forbidden-message"),
                        presentation.dialogText("common.back"), "MAIN", null);
                return;
            }
            String name = facade.displayName(targetId);
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, presentation.button(Material.PLAYER_HEAD, "§6" + name,
                    List.of(presentation.dialogText("member-role.identity", Map.of("role",
                            facade.memberRoleText(view.targetRole()))),
                            presentation.dialogText("member-detail.uuid", Map.of("id", targetId))), null, null)));
            boolean targetIsMayor = view.targetRole() == MemberRole.MAYOR;
            boolean viewerIsMayor = view.viewer().role() == MemberRole.MAYOR;
            if (viewerIsMayor && !targetIsMayor) {
                MemberRole nextRole = view.targetRole() == MemberRole.DEPUTY_MAYOR
                        ? MemberRole.MEMBER : MemberRole.DEPUTY_MAYOR;
                items.add(new MenuItem(10, presentation.button(Material.GOLDEN_HELMET,
                        nextRole == MemberRole.DEPUTY_MAYOR
                                ? presentation.dialogText("member-detail.promote-deputy")
                                : presentation.dialogText("member-detail.demote-member"),
                        List.of(presentation.dialogText("tooltip.member-detail.max-deputies")), "CONFIRM_ROLE",
                        townId + ":" + targetId + ":" + nextRole + ":" + page)));
            }
            boolean viewerCanRemove = view.viewer().role().isLeader() && !targetIsMayor
                    && (viewerIsMayor || view.targetRole() == MemberRole.MEMBER);
            if (viewerCanRemove) {
                items.add(new MenuItem(12, presentation.button(Material.RED_CONCRETE,
                        presentation.dialogText("member-detail.kick"),
                        List.of(presentation.dialogText("tooltip.member-detail.kick-now"),
                            presentation.dialogText("common.confirmation-required")),
                        "CONFIRM_KICK_MEMBER", townId + ":" + targetId + ":" + page)));
            }
            if (viewerIsMayor && !targetIsMayor) {
                items.add(new MenuItem(14, presentation.button(Material.NETHER_STAR,
                        presentation.dialogText("member-detail.transfer"),
                        List.of(presentation.dialogText("tooltip.member-detail.transfer-expiry")),
                        "CONFIRM_TRANSFER_MAYOR",
                        townId + ":" + targetId + ":" + page)));
            }
            if (!targetIsMayor && !targetId.equals(player.getUniqueId())) {
                items.add(new MenuItem(16, presentation.button(Material.PAPER,
                        presentation.dialogText("votes.action-kick-member"),
                        List.of(presentation.dialogText("tooltip.member-detail.vote-kick-threshold")),
                        "CONFIRM_CREATE_VOTE", townId + ":KICK_MEMBER:" + targetId
                                + ":" + page)));
            }
            if (!targetIsMayor && view.viewer().role().isLeader()) {
                items.add(new MenuItem(22, presentation.button(Material.ENCHANTED_BOOK,
                        presentation.dialogText("votes.action-replace-mayor"),
                        List.of(presentation.dialogText("tooltip.member-detail.replace-mayor-threshold")),
                        "CONFIRM_CREATE_VOTE", townId + ":REPLACE_MAYOR:" + targetId
                                + ":" + page)));
            }
            presentation.openMenu(player, 27, presentation.dialogText("member-detail.title", Map.of("name", name)),
                    new DialogRoute("MEMBERS", townId + ":" + page), items);
        });
    }

    public void changeMemberRole(Player mayor, UUID townId, UUID playerId, MemberRole role, int page) {
        actions.changeMemberRole(mayor, townId, playerId, role, outcome ->
                facade.handleOutcome(mayor, outcome, changed -> {
            presentation.openNotice(mayor, presentation.dialogText("notice.role-updated-title"),
                    presentation.dialogText("notice.role-updated-message", Map.of("role",
                            facade.memberRoleText(changed))),
                    presentation.dialogText("common.back"),
                    "MEMBER_DETAIL", townId + ":" + playerId + ":" + page);
        }));
    }

    public void kickMember(Player mayor, UUID townId, UUID playerId, int page) {
        actions.kickMember(mayor, townId, playerId, outcome ->
                facade.handleOutcome(mayor, outcome, change -> {
            Player removed = Bukkit.getPlayer(change.playerId());
            if (removed != null && removed.isOnline()) {
                plugin.messages().send(removed, "chat.notification.member-removed",
                        TownMembershipUi.townNotificationPlaceholders(change));
            }
            presentation.openNotice(mayor, presentation.dialogText("notice.member-removed-title"),
                    presentation.dialogText("notice.member-removed-message"),
                    presentation.dialogText("common.back"), "MEMBERS", change.townId() + ":" + page);
        }));
    }

    public void addVisitor(Player manager, UUID townId, UUID playerId) {
        actions.addVisitor(manager, townId, playerId, outcome ->
                facade.handleOutcome(manager, outcome, change -> {
            Player invited = Bukkit.getPlayer(change.playerId());
            if (invited != null && invited.isOnline()) {
                plugin.messages().send(invited, "chat.notification.visitor-added",
                        TownMembershipUi.townNotificationPlaceholders(change));
                presentation.playSound(invited, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            presentation.openNotice(manager, presentation.dialogText("notice.visitor-added-title"),
                    presentation.dialogText("notice.visitor-added-message", Map.of(
                            "player", facade.displayName(change.playerId()))),
                    presentation.dialogText("common.back"), "VISITOR_LIST", change.townId() + ":0");
        }));
    }

    public void removeVisitor(Player manager, UUID townId, UUID playerId, int page) {
        actions.removeVisitor(manager, townId, playerId, outcome ->
                facade.handleOutcome(manager, outcome, change -> {
            Player visitor = Bukkit.getPlayer(change.playerId());
            if (visitor != null && visitor.isOnline()) {
                plugin.messages().send(visitor, "chat.notification.visitor-removed",
                        TownMembershipUi.townNotificationPlaceholders(change));
            }
            presentation.openNotice(manager, presentation.dialogText("notice.visitor-removed-title"),
                    presentation.dialogText("notice.visitor-removed-message", Map.of(
                            "player", facade.displayName(change.playerId()))),
                    presentation.dialogText("common.back"), "VISITOR_LIST", change.townId() + ":" + page);
        }));
    }

    private record TownMemberOverview(TownSnapshot town, List<TownSnapshot.Member> members) {
        private TownMemberOverview {
            members = List.copyOf(members);
        }
    }

    private record MemberPage(List<TownSnapshot.Member> members,
                              MemberGovernanceSnapshot governance) {
        private MemberPage {
            members = List.copyOf(members);
        }
    }

    private record MemberDetail(MemberGovernanceSnapshot viewer, MemberRole targetRole) {
    }

    private record VisitorCenterView(MemberGovernanceSnapshot governance, int visitorCount) {
    }

    private record VisitorPageView(TownSnapshot.VisitorPage page,
                                   MemberGovernanceSnapshot governance) {
    }

    private record VisitorInviteView(MemberGovernanceSnapshot governance,
                                     List<UUID> memberIds,
                                     List<UUID> visitorIds) {
        private VisitorInviteView {
            memberIds = List.copyOf(memberIds);
            visitorIds = List.copyOf(visitorIds);
        }
    }
}

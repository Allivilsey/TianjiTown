package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.Map;
import java.util.Objects;

/** Owns member, visitor, role, removal, and mayor-transfer route protocols. */
final class TownMembershipUi {
    private final TownUiLegacyFacade facade;

    TownMembershipUi(TownUiLegacyFacade facade) {
        this.facade = facade;
    }

    static Map<String, String> townNotificationPlaceholders(TownPlayerChange change) {
        Objects.requireNonNull(change, "change");
        return Map.of("town", TownUiLegacyFacade.safeText(change.townName()));
    }

    void route(Player player, String action, String target) {
        try {
            switch (action) {
                case "TOWN_MEMBER_OVERVIEW" -> {
                    TownPage page = TownPage.parse(target);
                    facade.openTownMemberOverview(player, page.townId(), page.page());
                }
                case "VISITOR_CENTER" -> facade.openVisitorCenter(player, townId(target));
                case "VISITOR_LIST" -> openVisitorList(player, target);
                case "VISITOR_INVITE" -> openVisitorInvite(player, target);
                case "CONFIRM_ADD_VISITOR" -> confirmVisitor(player, target, true);
                case "ADD_VISITOR" -> addVisitor(player, target);
                case "CONFIRM_REMOVE_VISITOR" -> confirmVisitor(player, target, false);
                case "REMOVE_VISITOR" -> removeVisitor(player, target);
                case "MEMBERS" -> openMembers(player, target);
                case "MEMBER_DETAIL" -> openMemberDetail(player, target);
                case "CONFIRM_ROLE" -> confirmRole(player, target);
                case "SET_ROLE" -> setRole(player, target);
                case "CONFIRM_KICK_MEMBER" -> confirmKick(player, target);
                case "KICK_MEMBER" -> kickMember(player, target);
                case "CONFIRM_TRANSFER_MAYOR" -> confirmMayorTransfer(player, target);
                case "REQUEST_TRANSFER_MAYOR" -> requestMayorTransfer(player, target);
                case "TRANSFER_REQUEST" -> facade.openTransferRequest(player, townId(target));
                case "CONFIRM_TRANSFER_DECISION" -> confirmTransferDecision(player, target);
                case "TRANSFER_DECISION" -> decideTransfer(player, target);
                default -> throw new IllegalArgumentException("unsupported membership action: " + action);
            }
        } catch (IllegalArgumentException exception) {
            facade.openStaleMenu(player);
        }
    }

    private void openVisitorList(Player player, String target) {
        TownPage page = TownPage.parse(target);
        facade.openVisitorList(player, page.townId(), page.page());
    }

    private void openVisitorInvite(Player player, String target) {
        TownPage page = TownPage.parse(target);
        facade.openVisitorInvite(player, page.townId(), page.page());
    }

    private void confirmVisitor(Player player, String target, boolean adding) {
        MemberPage member = MemberPage.parse(target);
        facade.openConfirmation(player,
                facade.dialogText(adding ? "confirmation.add-visitor-title"
                        : "confirmation.remove-visitor-title"),
                adding ? "ADD_VISITOR" : "REMOVE_VISITOR", member.encode(),
                facade.dialogText(adding ? "confirmation.add-visitor-consequence"
                        : "confirmation.remove-visitor-consequence"),
                adding ? "VISITOR_INVITE" : "VISITOR_LIST", member.townPage().encode());
    }

    private void addVisitor(Player player, String target) {
        MemberPage member = MemberPage.parse(target);
        facade.addVisitor(player, member.townPage().townId(), member.memberId());
    }

    private void removeVisitor(Player player, String target) {
        MemberPage member = MemberPage.parse(target);
        facade.removeVisitor(player, member.townPage().townId(), member.memberId(),
                member.townPage().page());
    }

    private void openMembers(Player player, String target) {
        TownPage page = TownPage.parse(target);
        facade.openMembers(player, page.townId(), page.page());
    }

    private void openMemberDetail(Player player, String target) {
        MemberPage member = MemberPage.parse(target);
        facade.openMemberDetail(player, member.townPage().townId(), member.memberId(),
                member.townPage().page());
    }

    private void confirmRole(Player player, String target) {
        RoleTarget role = RoleTarget.parse(target);
        facade.openConfirmation(player, facade.dialogText("confirmation.change-role-title"),
                "SET_ROLE", role.encode(), facade.dialogText("confirmation.change-role-consequence",
                        java.util.Map.of("role", facade.memberRoleText(role.role()))),
                "MEMBER_DETAIL", role.member().encode());
    }

    private void setRole(Player player, String target) {
        RoleTarget role = RoleTarget.parse(target);
        MemberPage member = role.member();
        facade.changeMemberRole(player, member.townPage().townId(), member.memberId(),
                role.role(), member.townPage().page());
    }

    private void confirmKick(Player player, String target) {
        MemberPage member = MemberPage.parse(target);
        facade.openConfirmation(player, facade.dialogText("confirmation.kick-member-title"),
                "KICK_MEMBER", member.encode(),
                facade.dialogText("confirmation.kick-member-consequence"), "MEMBER_DETAIL",
                member.encode());
    }

    private void kickMember(Player player, String target) {
        MemberPage member = MemberPage.parse(target);
        facade.kickMember(player, member.townPage().townId(), member.memberId(),
                member.townPage().page());
    }

    private void confirmMayorTransfer(Player player, String target) {
        MemberPage member = MemberPage.parse(target);
        facade.openConfirmation(player, facade.dialogText("confirmation.transfer-mayor-title"),
                "REQUEST_TRANSFER_MAYOR", member.encode(),
                facade.dialogText("confirmation.transfer-mayor-consequence"), "MEMBER_DETAIL",
                member.encode());
    }

    private void requestMayorTransfer(Player player, String target) {
        MemberPage member = MemberPage.parse(target);
        facade.requestMayorTransfer(player, member.townPage().townId(), member.memberId());
    }

    private void confirmTransferDecision(Player player, String target) {
        TransferDecision decision = TransferDecision.parse(target);
        facade.openConfirmation(player, facade.dialogText(decision.accept()
                        ? "confirmation.accept-transfer-title" : "confirmation.reject-transfer-title"),
                "TRANSFER_DECISION", decision.encode(), facade.dialogText(decision.accept()
                        ? "confirmation.accept-transfer-consequence"
                        : "confirmation.reject-transfer-consequence"),
                "TRANSFER_REQUEST", decision.transferId().toString());
    }

    private void decideTransfer(Player player, String target) {
        TransferDecision decision = TransferDecision.parse(target);
        facade.decideMayorTransfer(player, decision.transferId(), decision.accept());
    }

    private static UUID townId(String target) {
        if (target == null || target.isBlank() || target.indexOf(':') >= 0) {
            throw new IllegalArgumentException("invalid town target");
        }
        return UUID.fromString(target);
    }

    record TownPage(UUID townId, int page) {
        static TownPage parse(String target) {
            String[] values = parts(target, 2);
            int page = Integer.parseInt(values[1]);
            if (page < 0) {
                throw new IllegalArgumentException("negative page");
            }
            return new TownPage(UUID.fromString(values[0]), page);
        }

        String encode() {
            return townId + ":" + page;
        }
    }

    record MemberPage(TownPage townPage, UUID memberId) {
        static MemberPage parse(String target) {
            String[] values = parts(target, 3);
            return new MemberPage(new TownPage(UUID.fromString(values[0]), page(values[2])),
                    UUID.fromString(values[1]));
        }

        String encode() {
            return townPage.townId() + ":" + memberId + ":" + townPage.page();
        }
    }

    record RoleTarget(MemberPage member, MemberRole role) {
        static RoleTarget parse(String target) {
            String[] values = parts(target, 4);
            return new RoleTarget(new MemberPage(new TownPage(UUID.fromString(values[0]),
                    page(values[3])), UUID.fromString(values[1])), MemberRole.valueOf(values[2]));
        }

        String encode() {
            return member.townPage().townId() + ":" + member.memberId() + ":" + role
                    + ":" + member.townPage().page();
        }
    }

    record TransferDecision(UUID transferId, boolean accept) {
        static TransferDecision parse(String target) {
            String[] values = parts(target, 2);
            if (!"true".equals(values[1]) && !"false".equals(values[1])) {
                throw new IllegalArgumentException("invalid transfer decision");
            }
            return new TransferDecision(UUID.fromString(values[0]), Boolean.parseBoolean(values[1]));
        }

        String encode() {
            return transferId + ":" + accept;
        }
    }

    private static String[] parts(String target, int expected) {
        String[] values = target == null ? new String[0] : target.split(":", -1);
        if (values.length != expected) {
            throw new IllegalArgumentException("invalid route target");
        }
        return values;
    }

    private static int page(String value) {
        int page = Integer.parseInt(value);
        if (page < 0) {
            throw new IllegalArgumentException("negative page");
        }
        return page;
    }
}

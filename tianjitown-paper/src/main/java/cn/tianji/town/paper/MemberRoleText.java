package cn.tianji.town.paper;

import cn.tianji.town.core.town.MemberRole;

/** Player-facing labels for every stored member role. */
final class MemberRoleText {
    private MemberRoleText() {
    }

    static String messageKey(MemberRole role) {
        return switch (role) {
            case MAYOR -> "member-role.mayor";
            case DEPUTY_MAYOR -> "member-role.deputy-mayor";
            case MEMBER -> "member-role.member";
        };
    }

    static int displayOrder(MemberRole role) {
        return switch (role) {
            case MAYOR -> 0;
            case DEPUTY_MAYOR -> 1;
            case MEMBER -> 2;
        };
    }
}

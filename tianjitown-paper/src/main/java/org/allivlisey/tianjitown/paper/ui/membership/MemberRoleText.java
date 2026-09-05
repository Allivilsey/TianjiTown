package org.allivlisey.tianjitown.paper.ui.membership;

import org.allivlisey.tianjitown.core.town.MemberRole;

/** Player-facing labels for every stored member role. */
public final class MemberRoleText {
    private MemberRoleText() {
    }

    public static String messageKey(MemberRole role) {
        return switch (role) {
            case MAYOR -> "member-role.mayor";
            case DEPUTY_MAYOR -> "member-role.deputy-mayor";
            case MEMBER -> "member-role.member";
        };
    }

    public static int displayOrder(MemberRole role) {
        return switch (role) {
            case MAYOR -> 0;
            case DEPUTY_MAYOR -> 1;
            case MEMBER -> 2;
        };
    }
}

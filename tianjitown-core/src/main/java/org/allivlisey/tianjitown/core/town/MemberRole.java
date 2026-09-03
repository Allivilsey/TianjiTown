package org.allivlisey.tianjitown.core.town;

public enum MemberRole {
    MAYOR,
    DEPUTY_MAYOR,
    MEMBER;

    public boolean isLeader() {
        return this == MAYOR || this == DEPUTY_MAYOR;
    }
}

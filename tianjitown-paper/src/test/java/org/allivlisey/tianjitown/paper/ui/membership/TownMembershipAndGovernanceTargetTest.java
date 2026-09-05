package org.allivlisey.tianjitown.paper.ui.membership;
import org.allivlisey.tianjitown.paper.ui.governance.TownGovernanceUi;

import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownMembershipAndGovernanceTargetTest {
    private static final UUID TOWN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void membershipTargetsRequireEveryTypedField() {
        TownMembershipUi.RoleTarget target = TownMembershipUi.RoleTarget.parse(
                TOWN + ":" + PLAYER + ":DEPUTY_MAYOR:3");

        assertEquals(MemberRole.DEPUTY_MAYOR, target.role());
        assertEquals(3, target.member().townPage().page());
        assertThrows(IllegalArgumentException.class, () -> TownMembershipUi.RoleTarget.parse(
                TOWN + ":" + PLAYER + ":NOT_A_ROLE:3"));
        assertThrows(IllegalArgumentException.class, () -> TownMembershipUi.MemberPage.parse(
                TOWN + ":not-a-uuid:0"));
        assertThrows(IllegalArgumentException.class, () -> TownMembershipUi.TownPage.parse(
                TOWN + ":-1"));
    }

    @Test
    void governanceTargetsRejectMalformedVotesAndPages() {
        TownGovernanceUi.VoteCreation target = TownGovernanceUi.VoteCreation.parse(
                TOWN + ":" + VoteType.KICK_MEMBER + ":" + PLAYER + ":2");

        assertEquals(VoteType.KICK_MEMBER, target.type());
        assertEquals(2, target.memberPage());
        assertThrows(IllegalArgumentException.class, () -> TownGovernanceUi.VoteDecision.parse(
                PLAYER + ":yes"));
        assertThrows(IllegalArgumentException.class, () -> TownGovernanceUi.VoteCreation.parse(
                TOWN + ":KICK_MEMBER:" + PLAYER));
        assertThrows(IllegalArgumentException.class, () -> TownGovernanceUi.TownPage.parse(
                TOWN + ":-1"));
    }
}

package cn.tianji.town.core.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceRulesTest {
    @Test
    void kickVoteRequiresStrictMajority() {
        assertEquals(1, GovernanceRules.requiredYes(VoteType.KICK_MEMBER, 1));
        assertEquals(2, GovernanceRules.requiredYes(VoteType.KICK_MEMBER, 2));
        assertEquals(2, GovernanceRules.requiredYes(VoteType.KICK_MEMBER, 3));
        assertFalse(GovernanceRules.passed(VoteType.KICK_MEMBER, 4, 2));
        assertTrue(GovernanceRules.passed(VoteType.KICK_MEMBER, 4, 3));
    }

    @Test
    void mayorVoteRoundsTwoThirdsUp() {
        assertEquals(1, GovernanceRules.requiredYes(VoteType.REPLACE_MAYOR, 1));
        assertEquals(2, GovernanceRules.requiredYes(VoteType.REPLACE_MAYOR, 2));
        assertEquals(2, GovernanceRules.requiredYes(VoteType.REPLACE_MAYOR, 3));
        assertEquals(3, GovernanceRules.requiredYes(VoteType.REPLACE_MAYOR, 4));
        assertFalse(GovernanceRules.passed(VoteType.REPLACE_MAYOR, 5, 3));
        assertTrue(GovernanceRules.passed(VoteType.REPLACE_MAYOR, 5, 4));
    }
}

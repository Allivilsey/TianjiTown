package org.allivlisey.tianjitown.core.governance;

public final class GovernanceRules {
    private GovernanceRules() {
    }

    public static int requiredYes(VoteType type, int eligibleVoters) {
        if (eligibleVoters < 1) {
            throw new IllegalArgumentException("有效选民数必须大于 0");
        }
        return switch (type) {
            case KICK_MEMBER -> Math.floorDiv(eligibleVoters, 2) + 1;
            case REPLACE_MAYOR -> Math.floorDiv(Math.addExact(
                    Math.multiplyExact(eligibleVoters, 2), 2), 3);
        };
    }

    public static boolean passed(VoteType type, int eligibleVoters, int yesVotes) {
        if (yesVotes < 0 || yesVotes > eligibleVoters) {
            throw new IllegalArgumentException("赞成票数超出有效选民范围");
        }
        return yesVotes >= requiredYes(type, eligibleVoters);
    }
}

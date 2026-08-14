package cn.tianji.town.storage.governance;

import cn.tianji.town.core.governance.VoteStatus;
import cn.tianji.town.core.governance.VoteType;

import java.time.Instant;
import java.util.UUID;

public record VoteSnapshot(
        UUID id,
        UUID townId,
        VoteType type,
        UUID subjectId,
        UUID candidateId,
        VoteStatus status,
        int eligibleVoters,
        int requiredYes,
        int yesVotes,
        int noVotes,
        Instant endsAt,
        boolean viewerEligible,
        boolean viewerVoted
) {
    public boolean passed() {
        return status == VoteStatus.PASSED;
    }
}

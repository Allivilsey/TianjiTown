package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.governance.VoteStatus;
import org.allivlisey.tianjitown.core.governance.VoteType;

import java.time.Instant;
import java.util.UUID;

public record VoteSnapshot(
        UUID id,
        UUID townId,
        VoteType type,
        UUID subjectId,
        UUID candidateId,
        UUID createdBy,
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

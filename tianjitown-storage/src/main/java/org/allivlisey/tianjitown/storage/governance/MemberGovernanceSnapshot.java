package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.town.MemberRole;

import java.util.List;
import java.util.UUID;

public record MemberGovernanceSnapshot(
        UUID townId,
        String townName,
        MemberRole role,
        long townRulesRevision,
        long acceptedRulesRevision,
        List<String> rules,
        TransferSnapshot pendingTransfer,
        List<VoteSnapshot> votes
) {
    public MemberGovernanceSnapshot {
        rules = List.copyOf(rules);
        votes = List.copyOf(votes);
    }

    public boolean requiresRulesConfirmation() {
        return acceptedRulesRevision < townRulesRevision;
    }

    public boolean canReviewApplications() {
        return role.isLeader();
    }
}

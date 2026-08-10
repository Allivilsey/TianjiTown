package cn.tianji.town.storage.phase2;

import cn.tianji.town.core.town.MemberRole;

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
        return role == MemberRole.MAYOR || role == MemberRole.OFFICER;
    }
}

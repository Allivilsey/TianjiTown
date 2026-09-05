package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Public facade for member governance, mayor transfers and voting workflows. */
public final class GovernanceRepository {
    private final GovernanceMemberStore members;
    private final MayorTransferStore transfers;
    private final GovernanceVoteStore votes;

    public GovernanceRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        GovernanceDatabase database = new GovernanceDatabase(dataSource, forbiddenThread);
        this.members = new GovernanceMemberStore(database);
        this.transfers = new MayorTransferStore(database);
        this.votes = new GovernanceVoteStore(database);
    }

    public void recordActivity(UUID playerId) {
        members.recordActivity(playerId);
    }

    public Optional<MemberGovernanceSnapshot> dashboard(UUID playerId) {
        return members.dashboard(playerId);
    }

    public void acknowledgeRules(UUID townId, UUID playerId, long revision) {
        members.acknowledgeRules(townId, playerId, revision);
    }

    public MemberRole changeRoleByMayor(UUID townId, UUID targetId, MemberRole role,
                                        UUID mayorId, String mayorName) {
        return members.changeRoleByMayor(townId, targetId, role, mayorId, mayorName);
    }

    public MemberRole changeRoleByAdmin(UUID townId, UUID targetId, MemberRole role,
                                        UUID actorId, String actorName, String reason) {
        return members.changeRoleByAdmin(townId, targetId, role, actorId, actorName, reason);
    }

    public TownPlayerChange removeMemberByMayor(UUID townId, UUID targetId, UUID mayorId,
                                                String mayorName) {
        return members.removeMemberByMayor(townId, targetId, mayorId, mayorName);
    }

    public MemberRole memberRole(UUID townId, UUID playerId) {
        return members.memberRole(townId, playerId);
    }

    public List<UUID> listManagerIds(UUID townId) {
        return members.listManagerIds(townId);
    }

    public TransferSnapshot requestMayorTransfer(UUID townId, UUID candidateId, UUID mayorId,
                                                 Duration lifetime) {
        return transfers.requestMayorTransfer(townId, candidateId, mayorId, lifetime);
    }

    public TransferSnapshot decideMayorTransfer(UUID transferId, UUID candidateId, boolean accept) {
        return transfers.decideMayorTransfer(transferId, candidateId, accept);
    }

    public VoteSnapshot createVote(UUID townId, VoteType type, UUID targetId, UUID creatorId,
                                   Duration activeWindow, Duration minimumMembership,
                                   Duration lifetime, boolean adminBypass) {
        return votes.createVote(townId, type, targetId, creatorId, activeWindow,
                minimumMembership, lifetime, adminBypass);
    }

    public VoteSnapshot castVote(UUID voteId, UUID playerId, boolean approve) {
        return votes.castVote(voteId, playerId, approve);
    }

    public VoteSnapshot settleVote(UUID voteId, UUID actorId, String actorName, boolean force) {
        return votes.settleVote(voteId, actorId, actorName, force);
    }

    public List<VoteSnapshot> settleDueVotes() {
        return votes.settleDueVotes();
    }

    public VoteSnapshot cancelVote(UUID voteId, UUID actorId, String actorName, String reason) {
        return votes.cancelVote(voteId, actorId, actorName, reason);
    }

    public VoteSnapshot cancelOwnVote(UUID voteId, UUID creatorId, String creatorName) {
        return votes.cancelOwnVote(voteId, creatorId, creatorName);
    }

    public List<VoteSnapshot> listTownVotes(UUID townId, UUID viewerId, boolean onlyOpen) {
        return votes.listTownVotes(townId, viewerId, onlyOpen);
    }

    public List<UUID> listVoteVoterIds(UUID voteId) {
        return votes.listVoteVoterIds(voteId);
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }

        public ConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.governance.GovernanceRules;
import org.allivlisey.tianjitown.core.governance.VoteStatus;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.town.MemberRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository.ConflictException;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.requireUpdated;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.uuid;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.instant;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.performMayorTransfer;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.removeNonMayor;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.requireActiveTown;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.memberExists;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.memberRole;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.mayorId;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.audit;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.requireReason;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.shiftedInstant;

/** Governance vote creation, ballots, queries and settlement. */
final class GovernanceVoteStore {
    private final GovernanceDatabase database;

    GovernanceVoteStore(GovernanceDatabase database) {
        this.database = database;
    }

    VoteSnapshot createVote(UUID townId, VoteType type, UUID targetId, UUID creatorId,
                                   Duration activeWindow, Duration minimumMembership,
                                   Duration lifetime, boolean adminBypass) {
        database.requireWorkerThread();
        if (activeWindow.isNegative() || minimumMembership.isNegative()
                || lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("投票时间配置无效");
        }
        Instant now = Instant.now();
        Instant activeAfter = shiftedInstant(now, activeWindow, false, "投票时间配置无效");
        Instant joinedBefore = shiftedInstant(now, minimumMembership, false, "投票时间配置无效");
        Instant endsAt = shiftedInstant(now, lifetime, true, "投票时间配置无效");
        return database.transaction(connection -> {
            requireActiveTown(connection, townId);
            if (!adminBypass && !memberExists(connection, townId, creatorId)) {
                throw new ConflictException("只有本镇成员可以发起投票");
            }
            if (!adminBypass && type == VoteType.REPLACE_MAYOR
                    && memberRole(connection, townId, creatorId) == MemberRole.MEMBER) {
                throw new ConflictException("普通镇员不能发起强制更换镇长投票");
            }
            requireNoOpenVote(connection, townId);
            UUID mayorId = mayorId(connection, townId);
            UUID subjectId;
            UUID candidateId;
            if (type == VoteType.KICK_MEMBER) {
                if (mayorId.equals(targetId) || !memberExists(connection, townId, targetId)) {
                    throw new ConflictException("投票移除目标必须是本镇非镇长成员");
                }
                subjectId = targetId;
                candidateId = null;
            } else {
                if (mayorId.equals(targetId) || !memberExists(connection, townId, targetId)) {
                    throw new ConflictException("新镇长候选人必须是本镇其他成员");
                }
                subjectId = mayorId;
                candidateId = targetId;
            }
            List<UUID> voters = eligibleVoters(connection, townId, subjectId,
                    activeAfter, joinedBefore);
            if (voters.isEmpty()) {
                throw new ConflictException("没有满足活跃和入镇时长要求的有效选民");
            }
            boolean mayorCreatingReplacement = type == VoteType.REPLACE_MAYOR
                    && mayorId.equals(creatorId);
            if (!adminBypass && !voters.contains(creatorId) && !mayorCreatingReplacement) {
                throw new ConflictException("你当前不在本次投票的活跃选民范围内");
            }
            UUID voteId = UUID.randomUUID();
            int requiredYes = GovernanceRules.requiredYes(type, voters.size());
            try (PreparedStatement vote = connection.prepareStatement("""
                    INSERT INTO governance_votes
                        (vote_id, town_id, vote_type, subject_uuid, candidate_uuid, created_by,
                         status, eligible_voters, required_yes, ends_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                    """);
                 PreparedStatement voter = connection.prepareStatement("""
                         INSERT INTO governance_vote_voters (vote_id, player_uuid) VALUES (?, ?)
                         """)) {
                vote.setBytes(1, uuid(voteId));
                vote.setBytes(2, uuid(townId));
                vote.setString(3, type.name());
                vote.setBytes(4, uuid(subjectId));
                if (candidateId == null) {
                    vote.setNull(5, java.sql.Types.BLOB);
                } else {
                    vote.setBytes(5, uuid(candidateId));
                }
                vote.setBytes(6, uuid(creatorId));
                vote.setInt(7, voters.size());
                vote.setInt(8, requiredYes);
                vote.setLong(9, endsAt.toEpochMilli());
                vote.executeUpdate();
                for (UUID voterId : voters) {
                    voter.setBytes(1, uuid(voteId));
                    voter.setBytes(2, uuid(voterId));
                    voter.addBatch();
                }
                voter.executeBatch();
            }
            audit(connection, creatorId, creatorId.toString(), "VOTE_CREATE", townId,
                    "创建成员治理投票", type + ":" + targetId + ", voters=" + voters.size()
                            + ", requiredYes=" + requiredYes);
            return requireVote(connection, voteId, creatorId);
        });
    }

    VoteSnapshot castVote(UUID voteId, UUID playerId, boolean approve) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            VoteSnapshot vote = requireVote(connection, voteId, playerId);
            if (vote.status() != VoteStatus.OPEN || vote.endsAt().isBefore(Instant.now())) {
                throw new ConflictException("投票已结束");
            }
            if (!vote.viewerEligible()) {
                throw new ConflictException("你不在该投票冻结的选民快照中");
            }
            try (PreparedStatement ballot = connection.prepareStatement("""
                    INSERT INTO governance_vote_ballots (vote_id, player_uuid, approve)
                    VALUES (?, ?, ?)
                    """)) {
                ballot.setBytes(1, uuid(voteId));
                ballot.setBytes(2, uuid(playerId));
                ballot.setBoolean(3, approve);
                ballot.executeUpdate();
            }
            recount(connection, voteId);
            audit(connection, playerId, playerId.toString(), "VOTE_CAST", vote.townId(),
                    "成员提交投票", voteId + ":" + (approve ? "YES" : "NO"));
            VoteSnapshot updated = requireVote(connection, voteId, playerId);
            if (updated.yesVotes() + updated.noVotes() == updated.eligibleVoters()) {
                return settle(connection, updated, playerId, playerId.toString());
            }
            return updated;
        });
    }

    VoteSnapshot settleVote(UUID voteId, UUID actorId, String actorName, boolean force) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            VoteSnapshot vote = requireVote(connection, voteId, actorId);
            if (vote.status() != VoteStatus.OPEN) {
                return vote;
            }
            if (!force && vote.endsAt().isAfter(Instant.now())
                    && vote.yesVotes() + vote.noVotes() < vote.eligibleVoters()) {
                return vote;
            }
            return settle(connection, vote, actorId, actorName);
        });
    }

    List<VoteSnapshot> settleDueVotes() {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            List<UUID> due = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT vote_id FROM governance_votes
                     WHERE status = 'OPEN' AND ends_at <= ? ORDER BY ends_at, vote_id
                    """)) {
                statement.setLong(1, Instant.now().toEpochMilli());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        due.add(readUuid(result, "vote_id"));
                    }
                }
            }
            List<VoteSnapshot> settled = new ArrayList<>();
            for (UUID voteId : due) {
                VoteSnapshot vote = requireVote(connection, voteId, null);
                settled.add(settle(connection, vote, null, "SYSTEM"));
            }
            return List.copyOf(settled);
        });
    }

    VoteSnapshot cancelVote(UUID voteId, UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        requireReason(reason);
        return database.transaction(connection -> {
            VoteSnapshot vote = requireVote(connection, voteId, actorId);
            return cancelOpenVote(connection, vote, actorId, actorName, reason);
        });
    }

    VoteSnapshot cancelOwnVote(UUID voteId, UUID creatorId, String creatorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            VoteSnapshot vote = requireVote(connection, voteId, creatorId);
            if (!vote.createdBy().equals(creatorId)) {
                throw new ConflictException("只有投票发起人可以终止本次投票");
            }
            return cancelOpenVote(connection, vote, creatorId, creatorName,
                    "投票发起人主动终止");
        });
    }

    List<VoteSnapshot> listTownVotes(UUID townId, UUID viewerId, boolean onlyOpen) {
        database.requireWorkerThread();
        return database.query(connection -> listVotes(connection, townId, viewerId, onlyOpen));
    }

    List<UUID> listVoteVoterIds(UUID voteId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<UUID> voters = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid FROM governance_vote_voters
                     WHERE vote_id = ? ORDER BY player_uuid
                    """)) {
                statement.setBytes(1, uuid(voteId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        voters.add(readUuid(result, "player_uuid"));
                    }
                }
            }
            return List.copyOf(voters);
        });
    }

    private VoteSnapshot settle(Connection connection, VoteSnapshot vote, UUID actorId,
                                String actorName) throws SQLException {
        if (vote.status() != VoteStatus.OPEN) {
            return vote;
        }
        boolean passed = GovernanceRules.passed(vote.type(), vote.eligibleVoters(), vote.yesVotes());
        if (passed) {
            if (vote.type() == VoteType.KICK_MEMBER) {
                if (!memberExists(connection, vote.townId(), vote.subjectId())) {
                    passed = false;
                } else {
                    if (memberRole(connection, vote.townId(), vote.subjectId()) == MemberRole.MAYOR) {
                        passed = false;
                    } else {
                        removeNonMayor(connection, vote.townId(), vote.subjectId(), "REMOVED");
                    }
                }
            } else {
                UUID candidate = Objects.requireNonNull(vote.candidateId(), "candidateId");
                if (!memberExists(connection, vote.townId(), candidate)) {
                    passed = false;
                } else if (mayorId(connection, vote.townId()).equals(candidate)) {
                    // 其他流程已实现同一结果，结算只需落定状态。
                } else if (mayorId(connection, vote.townId()).equals(vote.subjectId())) {
                    performMayorTransfer(connection, vote.townId(), vote.subjectId(), candidate);
                } else {
                    passed = false;
                }
            }
        }
        VoteStatus status = passed ? VoteStatus.PASSED : VoteStatus.REJECTED;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE governance_votes SET status = ?, settled_at = ?
                 WHERE vote_id = ? AND status = 'OPEN'
                """)) {
            statement.setString(1, status.name());
            statement.setLong(2, Instant.now().toEpochMilli());
            statement.setBytes(3, uuid(vote.id()));
            requireUpdated(statement, "投票已由另一个结算任务处理");
        }
        audit(connection, actorId, actorName, "VOTE_SETTLE", vote.townId(),
                status == VoteStatus.PASSED ? "投票通过并已执行" : "投票未达到门槛",
                vote.id() + ": yes=" + vote.yesVotes() + "/" + vote.requiredYes());
        return requireVote(connection, vote.id(), actorId);
    }

    static void cancelSubjectVotes(Connection connection, UUID townId, UUID subjectId,
                                           String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE governance_votes SET status = 'CANCELLED', settled_at = ?,
                       cancelled_reason = ?
                 WHERE town_id = ? AND status = 'OPEN'
                   AND (subject_uuid = ? OR candidate_uuid = ?)
                """)) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.setString(2, reason);
            statement.setBytes(3, uuid(townId));
            statement.setBytes(4, uuid(subjectId));
            statement.setBytes(5, uuid(subjectId));
            statement.executeUpdate();
        }
    }

    private static List<UUID> eligibleVoters(Connection connection, UUID townId, UUID excluded,
                                             Instant activeAfter, Instant joinedBefore)
            throws SQLException {
        List<UUID> voters = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid FROM town_members
                 WHERE town_id = ? AND player_uuid <> ?
                   AND last_active_at >= ? AND joined_at <= ?
                 ORDER BY joined_at, player_uuid
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(excluded));
            statement.setLong(3, activeAfter.toEpochMilli());
            statement.setLong(4, joinedBefore.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    voters.add(readUuid(result, "player_uuid"));
                }
            }
        }
        return List.copyOf(voters);
    }

    private static void requireNoOpenVote(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM governance_votes
                 WHERE town_id = ? AND status = 'OPEN' LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("该小镇已有进行中的治理投票");
                }
            }
        }
    }

    static List<VoteSnapshot> listVotes(Connection connection, UUID townId, UUID viewerId,
                                         boolean onlyOpen) throws SQLException {
        String sql = """
                SELECT v.*,
                       EXISTS(SELECT 1 FROM governance_vote_voters e
                               WHERE e.vote_id = v.vote_id AND e.player_uuid = ?) AS viewer_eligible,
                       EXISTS(SELECT 1 FROM governance_vote_ballots b
                               WHERE b.vote_id = v.vote_id AND b.player_uuid = ?) AS viewer_voted
                  FROM governance_votes v WHERE v.town_id = ?
                """ + (onlyOpen ? " AND v.status = 'OPEN'" : "")
                + " ORDER BY v.created_at DESC, v.vote_id LIMIT 100";
        List<VoteSnapshot> votes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            byte[] viewer = viewerId == null ? uuid(new UUID(0, 0)) : uuid(viewerId);
            statement.setBytes(1, viewer);
            statement.setBytes(2, viewer);
            statement.setBytes(3, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    votes.add(readVote(result));
                }
            }
        }
        return List.copyOf(votes);
    }

    private VoteSnapshot requireVote(Connection connection, UUID voteId, UUID viewerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT v.*,
                       EXISTS(SELECT 1 FROM governance_vote_voters e
                               WHERE e.vote_id = v.vote_id AND e.player_uuid = ?) AS viewer_eligible,
                       EXISTS(SELECT 1 FROM governance_vote_ballots b
                               WHERE b.vote_id = v.vote_id AND b.player_uuid = ?) AS viewer_voted
                  FROM governance_votes v WHERE v.vote_id = ?
                """)) {
            byte[] viewer = viewerId == null ? uuid(new UUID(0, 0)) : uuid(viewerId);
            statement.setBytes(1, viewer);
            statement.setBytes(2, viewer);
            statement.setBytes(3, uuid(voteId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("投票不存在");
                }
                return readVote(result);
            }
        }
    }

    private static VoteSnapshot readVote(ResultSet result) throws SQLException {
        byte[] candidate = result.getBytes("candidate_uuid");
        return new VoteSnapshot(readUuid(result, "vote_id"), readUuid(result, "town_id"),
                VoteType.valueOf(result.getString("vote_type")),
                readUuid(result, "subject_uuid"), candidate == null ? null : uuid(candidate),
                readUuid(result, "created_by"),
                VoteStatus.valueOf(result.getString("status")),
                result.getInt("eligible_voters"), result.getInt("required_yes"),
                result.getInt("yes_votes"), result.getInt("no_votes"),
                instant(result, "ends_at"), result.getBoolean("viewer_eligible"),
                result.getBoolean("viewer_voted"));
    }

    private VoteSnapshot cancelOpenVote(Connection connection, VoteSnapshot vote,
                                        UUID actorId, String actorName, String reason)
            throws SQLException {
        if (vote.status() != VoteStatus.OPEN) {
            throw new ConflictException("投票已经结束，不能取消");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE governance_votes SET status = 'CANCELLED', settled_at = ?,
                       cancelled_reason = ? WHERE vote_id = ? AND status = 'OPEN'
                """)) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.setString(2, reason);
            statement.setBytes(3, uuid(vote.id()));
            requireUpdated(statement, "投票已经结束");
        }
        audit(connection, actorId, actorName, "VOTE_CANCEL", vote.townId(), reason,
                vote.id().toString());
        return requireVote(connection, vote.id(), actorId);
    }

    private static void recount(Connection connection, UUID voteId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE governance_votes
                   SET yes_votes = (SELECT COUNT(*) FROM governance_vote_ballots
                                     WHERE vote_id = ? AND approve = 1),
                       no_votes = (SELECT COUNT(*) FROM governance_vote_ballots
                                    WHERE vote_id = ? AND approve = 0)
                 WHERE vote_id = ? AND status = 'OPEN'
                """)) {
            statement.setBytes(1, uuid(voteId));
            statement.setBytes(2, uuid(voteId));
            statement.setBytes(3, uuid(voteId));
            requireUpdated(statement, "投票已结束");
        }
    }
}

package cn.tianji.town.storage.phase2;

import cn.tianji.town.core.governance.GovernanceRules;
import cn.tianji.town.core.governance.VoteStatus;
import cn.tianji.town.core.governance.VoteType;
import cn.tianji.town.core.town.MemberRole;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class GovernanceRepository {
    private static final String RULE_SEPARATOR = "\u001e";
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public GovernanceRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    public void recordActivity(UUID playerId) {
        requireWorkerThread();
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_members
                       SET last_active_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                     WHERE player_uuid = ?
                    """)) {
                statement.setBytes(1, uuid(playerId));
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Optional<MemberGovernanceSnapshot> dashboard(UUID playerId) {
        requireWorkerThread();
        return transaction(connection -> {
            expireTransfers(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.town_id, t.name, m.role, t.rules_revision,
                           m.rules_revision AS accepted_rules_revision, t.rules_text
                      FROM town_members m JOIN towns t ON t.town_id = m.town_id
                     WHERE m.player_uuid = ? AND t.status = 'ACTIVE'
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    UUID townId = readUuid(result, "town_id");
                    return Optional.of(new MemberGovernanceSnapshot(townId,
                            result.getString("name"), MemberRole.valueOf(result.getString("role")),
                            result.getLong("rules_revision"),
                            result.getLong("accepted_rules_revision"),
                            splitRules(result.getString("rules_text")),
                            findPendingTransfer(connection, playerId).orElse(null),
                            listVotes(connection, townId, playerId, true)));
                }
            }
        });
    }

    public void acknowledgeRules(UUID townId, UUID playerId, long revision) {
        requireWorkerThread();
        transaction(connection -> {
            long current;
            try (PreparedStatement town = connection.prepareStatement("""
                    SELECT rules_revision FROM towns WHERE town_id = ? AND status = 'ACTIVE'
                    """)) {
                town.setBytes(1, uuid(townId));
                try (ResultSet result = town.executeQuery()) {
                    if (!result.next()) {
                        throw new ConflictException("小镇不存在或已归档");
                    }
                    current = result.getLong(1);
                }
            }
            if (revision != current) {
                throw new ConflictException("规则已再次更新，请重新阅读后确认");
            }
            try (PreparedStatement member = connection.prepareStatement("""
                    UPDATE town_members SET rules_revision = ?
                     WHERE town_id = ? AND player_uuid = ? AND rules_revision < ?
                    """)) {
                member.setLong(1, current);
                member.setBytes(2, uuid(townId));
                member.setBytes(3, uuid(playerId));
                member.setLong(4, current);
                if (member.executeUpdate() == 0 && !memberExists(connection, townId, playerId)) {
                    throw new ConflictException("你已不属于该小镇");
                }
            }
            audit(connection, playerId, playerId.toString(), "RULES_CONFIRM", townId,
                    "成员确认规则版本", Long.toString(current));
            return null;
        });
    }

    public MemberRole changeRoleByMayor(UUID townId, UUID targetId, MemberRole role,
                                        UUID mayorId, String mayorName) {
        requireWorkerThread();
        return changeRole(townId, targetId, role, mayorId, mayorName, true,
                "镇长通过治理界面调整成员角色");
    }

    public MemberRole changeRoleByAdmin(UUID townId, UUID targetId, MemberRole role,
                                        UUID actorId, String actorName, String reason) {
        requireWorkerThread();
        requireReason(reason);
        return changeRole(townId, targetId, role, actorId, actorName, false, reason);
    }

    private MemberRole changeRole(UUID townId, UUID targetId, MemberRole role, UUID actorId,
                                  String actorName, boolean requireMayor, String reason) {
        if (role == MemberRole.MAYOR) {
            throw new ConflictException("MAYOR 必须通过镇长转让流程设置");
        }
        return transaction(connection -> {
            requireActiveTown(connection, townId);
            if (requireMayor) {
                requireRole(connection, townId, actorId, MemberRole.MAYOR);
            }
            MemberRole currentRole = memberRole(connection, townId, targetId);
            if (role == MemberRole.DEPUTY_MAYOR && currentRole != MemberRole.DEPUTY_MAYOR
                    && deputyMayorCount(connection, townId) >= 3) {
                throw new ConflictException("每个小镇最多任命 3 名副镇长");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_members SET role = ?
                     WHERE town_id = ? AND player_uuid = ? AND role <> 'MAYOR'
                    """)) {
                statement.setString(1, role.name());
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(targetId));
                requireUpdated(statement, "目标成员不存在或为镇长");
            }
            audit(connection, actorId, actorName, "MEMBER_ROLE_CHANGE", townId, reason,
                    targetId + " -> " + role);
            return role;
        });
    }

    public void removeMemberByMayor(UUID townId, UUID targetId, UUID mayorId, String mayorName) {
        requireWorkerThread();
        transaction(connection -> {
            MemberRole actorRole = memberRole(connection, townId, mayorId);
            if (!actorRole.isLeader()) {
                throw new ConflictException("只有本镇镇长或副镇长可以移除成员");
            }
            MemberRole targetRole = memberRole(connection, townId, targetId);
            if (targetRole == MemberRole.MAYOR) {
                throw new ConflictException("镇长不能通过成员移除流程离镇");
            }
            if (actorRole == MemberRole.DEPUTY_MAYOR
                    && targetRole == MemberRole.DEPUTY_MAYOR) {
                throw new ConflictException("只有镇长可以免除或移除副镇长");
            }
            removeNonMayor(connection, townId, targetId, "REMOVED");
            cancelSubjectVotes(connection, townId, targetId, "目标成员已被管理组移除");
            audit(connection, mayorId, mayorName, "MEMBER_MAYOR_REMOVE", townId,
                    "镇长或副镇长通过治理界面移除成员", targetId.toString());
            return null;
        });
    }

    public TransferSnapshot requestMayorTransfer(UUID townId, UUID candidateId, UUID mayorId,
                                                 Duration lifetime) {
        requireWorkerThread();
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("镇长转让确认有效期必须大于 0");
        }
        Instant expiresAt = shiftedInstant(Instant.now(), lifetime, true,
                "镇长转让确认时间配置无效");
        return transaction(connection -> {
            expireTransfers(connection);
            requireRole(connection, townId, mayorId, MemberRole.MAYOR);
            if (mayorId.equals(candidateId) || !memberExists(connection, townId, candidateId)) {
                throw new ConflictException("候选人必须是本镇其他成员");
            }
            UUID transferId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mayor_transfer_requests
                        (transfer_id, town_id, requested_by, candidate_uuid, status, expires_at)
                    VALUES (?, ?, ?, ?, 'PENDING', ?)
                    """)) {
                statement.setBytes(1, uuid(transferId));
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(mayorId));
                statement.setBytes(4, uuid(candidateId));
                statement.setLong(5, expiresAt.toEpochMilli());
                statement.executeUpdate();
            }
            audit(connection, mayorId, mayorId.toString(), "MAYOR_TRANSFER_REQUEST", townId,
                    "镇长发起双方确认转让", candidateId.toString());
            return requireTransfer(connection, transferId);
        });
    }

    public TransferSnapshot decideMayorTransfer(UUID transferId, UUID candidateId, boolean accept) {
        requireWorkerThread();
        return transaction(connection -> {
            expireTransfers(connection);
            TransferSnapshot transfer = requireTransfer(connection, transferId);
            if (!"PENDING".equals(transfer.status()) || !candidateId.equals(transfer.candidateId())) {
                throw new ConflictException("转让请求已失效或不属于你");
            }
            if (accept) {
                requireActiveTown(connection, transfer.townId());
                requireRole(connection, transfer.townId(), transfer.requestedBy(), MemberRole.MAYOR);
                if (!memberExists(connection, transfer.townId(), candidateId)) {
                    throw new ConflictException("候选人已不属于该小镇");
                }
                performMayorTransfer(connection, transfer.townId(), transfer.requestedBy(), candidateId);
            }
            String status = accept ? "ACCEPTED" : "REJECTED";
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE mayor_transfer_requests SET status = ?, decided_at = ?
                     WHERE transfer_id = ? AND status = 'PENDING'
                    """)) {
                statement.setString(1, status);
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.setBytes(3, uuid(transferId));
                requireUpdated(statement, "转让请求已被处理");
            }
            audit(connection, candidateId, candidateId.toString(),
                    accept ? "MAYOR_TRANSFER_ACCEPT" : "MAYOR_TRANSFER_REJECT",
                    transfer.townId(), accept ? "候选成员接受镇长转让" : "候选成员拒绝镇长转让",
                    transferId.toString());
            return requireTransfer(connection, transferId);
        });
    }

    public VoteSnapshot createVote(UUID townId, VoteType type, UUID targetId, UUID creatorId,
                                   Duration activeWindow, Duration minimumMembership,
                                   Duration lifetime, boolean adminBypass) {
        requireWorkerThread();
        if (activeWindow.isNegative() || minimumMembership.isNegative()
                || lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("投票时间配置无效");
        }
        Instant now = Instant.now();
        Instant activeAfter = shiftedInstant(now, activeWindow, false, "投票时间配置无效");
        Instant joinedBefore = shiftedInstant(now, minimumMembership, false, "投票时间配置无效");
        Instant endsAt = shiftedInstant(now, lifetime, true, "投票时间配置无效");
        return transaction(connection -> {
            requireActiveTown(connection, townId);
            if (!adminBypass && !memberExists(connection, townId, creatorId)) {
                throw new ConflictException("只有本镇成员可以发起投票");
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
            if (!adminBypass && !voters.contains(creatorId)) {
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

    public VoteSnapshot castVote(UUID voteId, UUID playerId, boolean approve) {
        requireWorkerThread();
        return transaction(connection -> {
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

    public VoteSnapshot settleVote(UUID voteId, UUID actorId, String actorName, boolean force) {
        requireWorkerThread();
        return transaction(connection -> {
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

    public List<VoteSnapshot> settleDueVotes() {
        requireWorkerThread();
        return transaction(connection -> {
            List<UUID> due = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT vote_id FROM governance_votes
                     WHERE status = 'OPEN' AND ends_at <= ? ORDER BY ends_at
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

    public VoteSnapshot cancelVote(UUID voteId, UUID actorId, String actorName, String reason) {
        requireWorkerThread();
        requireReason(reason);
        return transaction(connection -> {
            VoteSnapshot vote = requireVote(connection, voteId, actorId);
            if (vote.status() != VoteStatus.OPEN) {
                throw new ConflictException("投票已经结束，不能取消");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE governance_votes SET status = 'CANCELLED', settled_at = ?,
                           cancelled_reason = ? WHERE vote_id = ? AND status = 'OPEN'
                    """)) {
                statement.setLong(1, Instant.now().toEpochMilli());
                statement.setString(2, reason);
                statement.setBytes(3, uuid(voteId));
                requireUpdated(statement, "投票已经结束");
            }
            audit(connection, actorId, actorName, "VOTE_CANCEL", vote.townId(), reason,
                    voteId.toString());
            return requireVote(connection, voteId, actorId);
        });
    }

    public List<VoteSnapshot> listTownVotes(UUID townId, UUID viewerId, boolean onlyOpen) {
        requireWorkerThread();
        return query(connection -> listVotes(connection, townId, viewerId, onlyOpen));
    }

    public MemberRole memberRole(UUID townId, UUID playerId) {
        requireWorkerThread();
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT role FROM town_members WHERE town_id = ? AND player_uuid = ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new ConflictException("目标玩家已不属于该小镇");
                    }
                    return MemberRole.valueOf(result.getString("role"));
                }
            }
        });
    }

    public List<UUID> listManagerIds(UUID townId) {
        requireWorkerThread();
        return query(connection -> {
            List<UUID> managers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid FROM town_members
                     WHERE town_id = ? AND role IN ('MAYOR', 'DEPUTY_MAYOR')
                     ORDER BY CASE role WHEN 'MAYOR' THEN 0 ELSE 1 END, joined_at
                    """)) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        managers.add(readUuid(result, "player_uuid"));
                    }
                }
            }
            return List.copyOf(managers);
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
                if (memberExists(connection, vote.townId(), vote.subjectId())) {
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

    private static void performMayorTransfer(Connection connection, UUID townId, UUID oldMayorId,
                                             UUID candidateId) throws SQLException {
        try (PreparedStatement oldMayor = connection.prepareStatement("""
                UPDATE town_members SET role = 'MEMBER'
                 WHERE town_id = ? AND player_uuid = ? AND role = 'MAYOR'
                """);
             PreparedStatement newMayor = connection.prepareStatement("""
                     UPDATE town_members SET role = 'MAYOR'
                      WHERE town_id = ? AND player_uuid = ? AND role <> 'MAYOR'
                     """);
             PreparedStatement town = connection.prepareStatement("""
                     UPDATE towns SET mayor_uuid = ?, version = version + 1 WHERE town_id = ?
                     """)) {
            oldMayor.setBytes(1, uuid(townId));
            oldMayor.setBytes(2, uuid(oldMayorId));
            requireUpdated(oldMayor, "原镇长身份已经变化");
            newMayor.setBytes(1, uuid(townId));
            newMayor.setBytes(2, uuid(candidateId));
            requireUpdated(newMayor, "候选人已不属于该小镇");
            town.setBytes(1, uuid(candidateId));
            town.setBytes(2, uuid(townId));
            requireUpdated(town, "小镇不存在");
        }
    }

    private static void removeNonMayor(Connection connection, UUID townId, UUID targetId,
                                       String departureType) throws SQLException {
        try (PreparedStatement member = connection.prepareStatement("""
                DELETE FROM town_members WHERE town_id = ? AND player_uuid = ? AND role <> 'MAYOR'
                """);
             PreparedStatement departure = connection.prepareStatement("""
                     INSERT INTO town_member_departures (town_id, player_uuid, departure_type)
                     VALUES (?, ?, ?)
                     """)) {
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(targetId));
            requireUpdated(member, "目标成员不存在或为镇长");
            departure.setBytes(1, uuid(townId));
            departure.setBytes(2, uuid(targetId));
            departure.setString(3, departureType);
            departure.executeUpdate();
        }
    }

    private static void cancelSubjectVotes(Connection connection, UUID townId, UUID subjectId,
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

    private Optional<TransferSnapshot> findPendingTransfer(Connection connection, UUID candidateId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM mayor_transfer_requests
                 WHERE candidate_uuid = ? AND status = 'PENDING' AND expires_at > ?
                 ORDER BY created_at DESC LIMIT 1
                """)) {
            statement.setBytes(1, uuid(candidateId));
            statement.setLong(2, Instant.now().toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTransfer(result)) : Optional.empty();
            }
        }
    }

    private TransferSnapshot requireTransfer(Connection connection, UUID transferId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM mayor_transfer_requests WHERE transfer_id = ?")) {
            statement.setBytes(1, uuid(transferId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("镇长转让请求不存在");
                }
                return readTransfer(result);
            }
        }
    }

    private static TransferSnapshot readTransfer(ResultSet result) throws SQLException {
        return new TransferSnapshot(readUuid(result, "transfer_id"),
                readUuid(result, "town_id"), readUuid(result, "requested_by"),
                readUuid(result, "candidate_uuid"), result.getString("status"),
                instant(result, "expires_at"), instant(result, "created_at"));
    }

    private List<VoteSnapshot> listVotes(Connection connection, UUID townId, UUID viewerId,
                                         boolean onlyOpen) throws SQLException {
        String sql = """
                SELECT v.*,
                       EXISTS(SELECT 1 FROM governance_vote_voters e
                               WHERE e.vote_id = v.vote_id AND e.player_uuid = ?) AS viewer_eligible,
                       EXISTS(SELECT 1 FROM governance_vote_ballots b
                               WHERE b.vote_id = v.vote_id AND b.player_uuid = ?) AS viewer_voted
                  FROM governance_votes v WHERE v.town_id = ?
                """ + (onlyOpen ? " AND v.status = 'OPEN'" : "")
                + " ORDER BY v.created_at DESC LIMIT 100";
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
                VoteStatus.valueOf(result.getString("status")),
                result.getInt("eligible_voters"), result.getInt("required_yes"),
                result.getInt("yes_votes"), result.getInt("no_votes"),
                instant(result, "ends_at"), result.getBoolean("viewer_eligible"),
                result.getBoolean("viewer_voted"));
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

    private static void expireTransfers(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE mayor_transfer_requests SET status = 'EXPIRED', decided_at = ?
                 WHERE status = 'PENDING' AND expires_at <= ?
                """)) {
            long now = Instant.now().toEpochMilli();
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.executeUpdate();
        }
    }

    private static void requireActiveTown(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM towns WHERE town_id = ? AND status = 'ACTIVE'")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("小镇不存在或未处于正常运行状态");
                }
            }
        }
    }

    private static void requireRole(Connection connection, UUID townId, UUID playerId,
                                    MemberRole role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members m JOIN towns t ON t.town_id = m.town_id
                 WHERE m.town_id = ? AND m.player_uuid = ? AND m.role = ?
                   AND t.status = 'ACTIVE'
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            statement.setString(3, role.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("只有本镇镇长可以执行该操作");
                }
            }
        }
    }

    private static boolean memberExists(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM town_members WHERE town_id = ? AND player_uuid = ?")) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static MemberRole memberRole(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM town_members WHERE town_id = ? AND player_uuid = ?")) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("目标成员已不属于该小镇");
                }
                return MemberRole.valueOf(result.getString("role"));
            }
        }
    }

    private static int deputyMayorCount(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM town_members
                 WHERE town_id = ? AND role = 'DEPUTY_MAYOR'
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static UUID mayorId(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT mayor_uuid FROM towns WHERE town_id = ? AND status = 'ACTIVE'")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("小镇不存在或已归档");
                }
                return readUuid(result, "mayor_uuid");
            }
        }
    }

    private static void audit(Connection connection, UUID actorId, String actorName, String action,
                              UUID townId, String reason, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_logs
                    (actor_uuid, actor_name, action, target_type, target_id, reason, detail)
                VALUES (?, ?, ?, 'TOWN', ?, ?, ?)
                """)) {
            if (actorId == null) {
                statement.setNull(1, java.sql.Types.BLOB);
            } else {
                statement.setBytes(1, uuid(actorId));
            }
            statement.setString(2, actorName == null || actorName.isBlank() ? "SYSTEM" : actorName);
            statement.setString(3, action);
            statement.setString(4, townId.toString());
            statement.setString(5, reason);
            statement.setString(6, detail == null ? "" : detail);
            statement.executeUpdate();
        }
    }

    private static List<String> splitRules(String rules) {
        return rules == null || rules.isEmpty() ? List.of() : List.of(rules.split(RULE_SEPARATOR, -1));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("原因不能为空且不能超过 500 个字符");
        }
    }

    private static Instant shiftedInstant(Instant base, Duration duration, boolean future,
                                          String message) {
        try {
            Instant shifted = future ? base.plus(duration) : base.minus(duration);
            shifted.toEpochMilli();
            return shifted;
        } catch (ArithmeticException | DateTimeException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("SQLite 业务访问不得在 Paper 主线程执行");
        }
    }

    private <T> T query(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private static RuntimeException translate(SQLException exception) {
        String message = exception.getMessage() == null ? "SQLite 操作失败" : exception.getMessage();
        if (message.contains("UNIQUE constraint failed")) {
            return new ConflictException("操作与现有治理状态冲突，请重新读取后再试", exception);
        }
        return new StorageUnavailableException("SQLite 治理操作失败: " + message, exception);
    }

    private static void requireUpdated(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new ConflictException(message);
        }
    }

    private static byte[] uuid(UUID value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }

    private static UUID uuid(byte[] value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID readUuid(ResultSet result, String column) throws SQLException {
        return uuid(result.getBytes(column));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return Instant.ofEpochMilli(result.getLong(column));
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
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

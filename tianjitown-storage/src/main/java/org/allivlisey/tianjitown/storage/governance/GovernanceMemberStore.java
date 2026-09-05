package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository.ConflictException;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.requireUpdated;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.uuid;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.removeNonMayor;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.requireActiveTown;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.requireRole;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.memberExists;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.deputyMayorCount;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.townName;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.audit;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.requireReason;

/** Member activity, rules acknowledgement, dashboard and role administration. */
final class GovernanceMemberStore {
    private static final String RULE_SEPARATOR = "\u001e";
    private final GovernanceDatabase database;

    GovernanceMemberStore(GovernanceDatabase database) {
        this.database = database;
    }

    void recordActivity(UUID playerId) {
        database.requireWorkerThread();
        database.transaction(connection -> {
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

    Optional<MemberGovernanceSnapshot> dashboard(UUID playerId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            MayorTransferStore.expireTransfers(connection);
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
                            MayorTransferStore.findPendingTransfer(connection, playerId).orElse(null),
                            GovernanceVoteStore.listVotes(connection, townId, playerId, true)));
                }
            }
        });
    }

    void acknowledgeRules(UUID townId, UUID playerId, long revision) {
        database.requireWorkerThread();
        database.transaction(connection -> {
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

    MemberRole changeRoleByMayor(UUID townId, UUID targetId, MemberRole role,
                                        UUID mayorId, String mayorName) {
        database.requireWorkerThread();
        return changeRole(townId, targetId, role, mayorId, mayorName, true,
                "镇长通过治理界面调整成员角色");
    }

    MemberRole changeRoleByAdmin(UUID townId, UUID targetId, MemberRole role,
                                        UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        requireReason(reason);
        return changeRole(townId, targetId, role, actorId, actorName, false, reason);
    }

    private MemberRole changeRole(UUID townId, UUID targetId, MemberRole role, UUID actorId,
                                  String actorName, boolean requireMayor, String reason) {
        if (role == MemberRole.MAYOR) {
            throw new ConflictException("MAYOR 必须通过镇长转让流程设置");
        }
        return database.transaction(connection -> {
            requireActiveTown(connection, townId);
            if (requireMayor) {
                requireRole(connection, townId, actorId, MemberRole.MAYOR);
            }
            MemberRole currentRole = GovernancePersistence.memberRole(connection, townId, targetId);
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

    TownPlayerChange removeMemberByMayor(UUID townId, UUID targetId, UUID mayorId,
                                                String mayorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            MemberRole actorRole = GovernancePersistence.memberRole(connection, townId, mayorId);
            if (!actorRole.isLeader()) {
                throw new ConflictException("只有本镇镇长或副镇长可以移除成员");
            }
            MemberRole targetRole = GovernancePersistence.memberRole(connection, townId, targetId);
            if (targetRole == MemberRole.MAYOR) {
                throw new ConflictException("镇长不能通过成员移除流程离镇");
            }
            if (actorRole == MemberRole.DEPUTY_MAYOR
                    && targetRole == MemberRole.DEPUTY_MAYOR) {
                throw new ConflictException("只有镇长可以免除或移除副镇长");
            }
            removeNonMayor(connection, townId, targetId, "REMOVED");
            GovernanceVoteStore.cancelSubjectVotes(connection, townId, targetId, "目标成员已被管理组移除");
            audit(connection, mayorId, mayorName, "MEMBER_MAYOR_REMOVE", townId,
                    "镇长或副镇长通过治理界面移除成员", targetId.toString());
            return new TownPlayerChange(townId, targetId, townName(connection, townId));
        });
    }

    MemberRole memberRole(UUID townId, UUID playerId) {
        database.requireWorkerThread();
        return database.query(connection -> {
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

    List<UUID> listManagerIds(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<UUID> managers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid FROM town_members
                     WHERE town_id = ? AND role IN ('MAYOR', 'DEPUTY_MAYOR')
                     ORDER BY CASE role WHEN 'MAYOR' THEN 0 ELSE 1 END, joined_at, player_uuid
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

    private static List<String> splitRules(String rules) {
        return rules == null || rules.isEmpty() ? List.of() : List.of(rules.split(RULE_SEPARATOR, -1));
    }
}

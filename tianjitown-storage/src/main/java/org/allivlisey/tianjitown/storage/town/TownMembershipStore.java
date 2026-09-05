package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownRepository.ConflictException;
import org.allivlisey.tianjitown.storage.town.TownRepository.MemberConflict;
import org.allivlisey.tianjitown.storage.town.TownRepository.NotFoundException;

import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Town member roles, departures and visitor administration. */
final class TownMembershipStore {
    private final TownDatabase database;

    TownMembershipStore(TownDatabase database) {
        this.database = database;
    }

    TownSnapshot.Page listMembers(UUID townId, int page, int pageSize) {
        database.requireWorkerThread();
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int safePage = Math.max(page, 0);
        return database.query(connection -> {
            List<TownSnapshot.Member> members = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, role, joined_at FROM town_members
                     WHERE town_id = ? ORDER BY joined_at, player_uuid LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setInt(2, safeSize + 1);
                statement.setInt(3, Math.multiplyExact(safePage, safeSize));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        members.add(new TownSnapshot.Member(readUuid(result, "player_uuid"),
                                MemberRole.valueOf(result.getString("role")),
                                result.getTimestamp("joined_at").toInstant()));
                    }
                }
            }
            boolean hasNext = members.size() > safeSize;
            if (hasNext) {
                members.removeLast();
            }
            return new TownSnapshot.Page(members, hasNext);
        });
    }

    List<TownSnapshot.Member> listAllMembers(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<TownSnapshot.Member> members = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, role, joined_at FROM town_members
                     WHERE town_id = ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        members.add(new TownSnapshot.Member(readUuid(result, "player_uuid"),
                                MemberRole.valueOf(result.getString("role")),
                                result.getTimestamp("joined_at").toInstant()));
                    }
                }
            }
            return List.copyOf(members);
        });
    }

    List<UUID> listMemberIds(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<UUID> members = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid FROM town_members WHERE town_id = ? "
                            + "ORDER BY joined_at, player_uuid")) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        members.add(readUuid(result, "player_uuid"));
                    }
                }
            }
            return List.copyOf(members);
        });
    }

    List<MemberConflict> initialMemberConflicts(List<UUID> playerIds) {
        database.requireWorkerThread();
        if (playerIds == null) {
            return List.of();
        }
        return database.query(connection -> {
            List<MemberConflict> conflicts = new ArrayList<>();
            for (UUID playerId : playerIds.stream().filter(Objects::nonNull).distinct().toList()) {
                Optional<UUID> townId = TownPersistence.memberTownId(connection, playerId);
                if (townId.isEmpty()) {
                    continue;
                }
                TownSnapshot town = TownPersistence.requireTown(connection, townId.get());
                conflicts.add(new MemberConflict(playerId, "ALREADY_MEMBER", town.id(),
                        town.profile().name()));
            }
            return List.copyOf(conflicts);
        });
    }

    TownSnapshot.VisitorPage listVisitors(UUID townId, int page, int pageSize) {
        database.requireWorkerThread();
        return database.query(connection -> TownVisitorStore.listVisitors(connection, townId, page, pageSize));
    }

    List<UUID> listVisitorIds(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> TownVisitorStore.listVisitorIds(connection, townId));
    }

    List<UUID> listLandAccessIds(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> TownVisitorStore.listLandAccessIds(connection, townId));
    }

    TownSnapshot.Visitor addVisitor(UUID townId, UUID playerId, UUID actorId,
                                           String actorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> addVisitor(connection, townId, playerId, actorId,
                actorName));
    }

    TownPlayerChange addVisitorWithTownName(UUID townId, UUID playerId, UUID actorId,
                                                    String actorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            TownSnapshot.Visitor visitor = addVisitor(connection, townId, playerId, actorId,
                    actorName);
            return new TownPlayerChange(townId, visitor.playerId(), townName(connection, townId));
        });
    }

    private TownSnapshot.Visitor addVisitor(Connection connection, UUID townId, UUID playerId,
                                            UUID actorId, String actorName) throws SQLException {
        TownPersistence.requireManager(connection, townId, actorId);
        if (memberExists(connection, townId, playerId)) {
            throw new ConflictException("本镇成员不能加入访客名单");
        }
        if (TownVisitorStore.visitorExists(connection, townId, playerId)) {
            throw new ConflictException("目标玩家已经在访客名单中");
        }
        TownVisitorStore.insert(connection, townId, playerId, actorId);
        TownPersistence.audit(connection, null, actorId, actorName, "VISITOR_ADD", "TOWN",
                townId.toString(), "镇长或副镇长邀请访客", playerId.toString());
        return TownVisitorStore.requireVisitor(connection, townId, playerId);
    }

    UUID removeVisitor(UUID townId, UUID playerId, UUID actorId, String actorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> removeVisitor(connection, townId, playerId, actorId,
                actorName));
    }

    TownPlayerChange removeVisitorWithTownName(UUID townId, UUID playerId, UUID actorId,
                                                       String actorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            UUID removed = removeVisitor(connection, townId, playerId, actorId, actorName);
            return new TownPlayerChange(townId, removed, townName(connection, townId));
        });
    }

    private UUID removeVisitor(Connection connection, UUID townId, UUID playerId, UUID actorId,
                               String actorName) throws SQLException {
        TownPersistence.requireManager(connection, townId, actorId);
        TownVisitorStore.delete(connection, townId, playerId);
        TownPersistence.audit(connection, null, actorId, actorName, "VISITOR_REMOVE", "TOWN",
                townId.toString(), "镇长或副镇长移出访客", playerId.toString());
        return playerId;
    }

    Map<UUID, List<UUID>> listMemberIdsByTown() {
        database.requireWorkerThread();
        return database.query(connection -> {
            Map<UUID, List<UUID>> mutable = new java.util.LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id, player_uuid FROM town_members
                     ORDER BY town_id, joined_at, player_uuid
                    """);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID townId = readUuid(result, "town_id");
                    mutable.computeIfAbsent(townId, ignored -> new ArrayList<>())
                            .add(readUuid(result, "player_uuid"));
                }
            }
            Map<UUID, List<UUID>> immutable = new java.util.LinkedHashMap<>();
            mutable.forEach((townId, members) -> immutable.put(townId, List.copyOf(members)));
            return Map.copyOf(immutable);
        });
    }

    void leaveTown(UUID playerId) {
        database.requireWorkerThread();
        database.transaction(connection -> {
            UUID townId = TownPersistence.memberTownId(connection, playerId)
                    .orElseThrow(() -> new ConflictException("你不属于任何小镇"));
            try (PreparedStatement member = connection.prepareStatement("""
                    DELETE FROM town_members WHERE town_id = ? AND player_uuid = ? AND role <> 'MAYOR'
                    """);
                 PreparedStatement departure = connection.prepareStatement("""
                         INSERT INTO town_member_departures (town_id, player_uuid, departure_type)
                         VALUES (?, ?, 'VOLUNTARY')
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                TownPersistence.requireUpdated(member, "镇长不能主动退出，请先解散小镇或由管理员转移镇长");
                departure.setBytes(1, uuid(townId));
                departure.setBytes(2, uuid(playerId));
                departure.executeUpdate();
            }
            TownPersistence.audit(connection, null, playerId, playerId.toString(), "MEMBER_LEAVE", "TOWN",
                    townId.toString(), "成员主动退出", "");
            return null;
        });
    }

    void removeMember(UUID townId, UUID playerId, UUID actorId, String actorName,
                             String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        database.transaction(connection -> {
            try (PreparedStatement member = connection.prepareStatement("""
                    DELETE FROM town_members WHERE town_id = ? AND player_uuid = ? AND role <> 'MAYOR'
                    """);
                 PreparedStatement departure = connection.prepareStatement("""
                         INSERT INTO town_member_departures (town_id, player_uuid, departure_type)
                         VALUES (?, ?, 'REMOVED')
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                TownPersistence.requireUpdated(member, "成员不存在或目标是镇长");
                departure.setBytes(1, uuid(townId));
                departure.setBytes(2, uuid(playerId));
                departure.executeUpdate();
            }
            TownPersistence.audit(connection, null, actorId, actorName, "MEMBER_REMOVE", "TOWN", townId.toString(),
                    reason, playerId.toString());
            return null;
        });
    }

    void addMember(UUID townId, UUID playerId, UUID actorId, String actorName,
                          String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        database.transaction(connection -> {
            TownSnapshot town = TownPersistence.requireTown(connection, townId);
            if (town.status() != TownStatus.ACTIVE) {
                throw new ConflictException("只有正常运行的小镇可以添加成员");
            }
            if (TownPersistence.memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("目标玩家已经属于一个小镇");
            }
            try (PreparedStatement member = connection.prepareStatement("""
                    INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, 'MEMBER')
                    """);
                 PreparedStatement applications = connection.prepareStatement("""
                         UPDATE town_join_applications
                            SET status = 'CANCELLED',
                                decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE applicant_uuid = ? AND status = 'PENDING'
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                member.executeUpdate();
                applications.setBytes(1, uuid(playerId));
                applications.executeUpdate();
            }
            TownPersistence.audit(connection, null, actorId, actorName, "MEMBER_ADMIN_ADD", "TOWN",
                    townId.toString(), reason, playerId.toString());
            return null;
        });
    }

    void transferMayor(UUID townId, UUID newMayorId, UUID actorId, String actorName,
                              String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        database.transaction(connection -> {
            TownSnapshot town = TownPersistence.requireTown(connection, townId);
            if (town.status() == TownStatus.ARCHIVED) {
                throw new ConflictException("已归档小镇不能转移镇长");
            }
            if (!memberExists(connection, townId, newMayorId)) {
                throw new ConflictException("新镇长必须已是该镇成员");
            }
            try (PreparedStatement oldMayor = connection.prepareStatement("""
                    UPDATE town_members SET role = 'MEMBER'
                     WHERE town_id = ? AND player_uuid = ? AND role = 'MAYOR'
                    """);
                 PreparedStatement newMayor = connection.prepareStatement("""
                         UPDATE town_members SET role = 'MAYOR'
                          WHERE town_id = ? AND player_uuid = ?
                         """);
                 PreparedStatement townUpdate = connection.prepareStatement("""
                         UPDATE towns SET mayor_uuid = ?, version = version + 1
                          WHERE town_id = ? AND version = ?
                         """)) {
                oldMayor.setBytes(1, uuid(townId));
                oldMayor.setBytes(2, uuid(town.mayorId()));
                oldMayor.executeUpdate();
                newMayor.setBytes(1, uuid(townId));
                newMayor.setBytes(2, uuid(newMayorId));
                TownPersistence.requireUpdated(newMayor, "新镇长不是该镇成员");
                townUpdate.setBytes(1, uuid(newMayorId));
                townUpdate.setBytes(2, uuid(townId));
                townUpdate.setLong(3, town.version());
                TownPersistence.requireUpdated(townUpdate, "小镇资料已被其他操作修改");
            }
            TownPersistence.audit(connection, null, actorId, actorName, "MAYOR_TRANSFER", "TOWN",
                    townId.toString(), reason, town.mayorId() + " -> " + newMayorId);
            return null;
        });
    }

    private static String townName(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM towns WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new NotFoundException("找不到小镇 " + townId);
                }
                return result.getString("name");
            }
        }
    }

    private boolean memberExists(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members WHERE town_id = ? AND player_uuid = ? LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}

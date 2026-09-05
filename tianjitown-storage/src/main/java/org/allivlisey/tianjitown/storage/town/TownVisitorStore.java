package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.town.TownRepository.ConflictException;
import org.allivlisey.tianjitown.storage.town.TownRepository.NotFoundException;

import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Visitor persistence; authorization and audit are owned by TownRepository. */
final class TownVisitorStore {
    private TownVisitorStore() {}

    static TownSnapshot.VisitorPage listVisitors(Connection connection, UUID townId, int page, int pageSize)
            throws SQLException {
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int safePage = Math.max(page, 0);
        List<TownSnapshot.Visitor> visitors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, invited_by, added_at FROM town_visitors
                 WHERE town_id = ? ORDER BY added_at, player_uuid LIMIT ? OFFSET ?
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setInt(2, safeSize + 1);
            statement.setInt(3, Math.multiplyExact(safePage, safeSize));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    visitors.add(new TownSnapshot.Visitor(
                            readUuid(result, "player_uuid"),
                            readUuid(result, "invited_by"),
                            result.getTimestamp("added_at").toInstant()));
                }
            }
        }
        boolean hasNext = visitors.size() > safeSize;
        if (hasNext) {
            visitors.removeLast();
        }
        return new TownSnapshot.VisitorPage(visitors, hasNext);
    }

    static List<UUID> listVisitorIds(Connection connection, UUID townId) throws SQLException {
        List<UUID> visitors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid FROM town_visitors
                 WHERE town_id = ? ORDER BY added_at, player_uuid
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    visitors.add(readUuid(result, "player_uuid"));
                }
            }
        }
        return List.copyOf(visitors);
    }

    static List<UUID> listLandAccessIds(Connection connection, UUID townId) throws SQLException {
        List<UUID> players = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, subject_type, granted_at FROM (
                    SELECT player_uuid, 0 AS subject_type, joined_at AS granted_at
                      FROM town_members WHERE town_id = ?
                    UNION ALL
                    SELECT player_uuid, 1 AS subject_type, added_at AS granted_at
                      FROM town_visitors WHERE town_id = ?
                ) ORDER BY subject_type, granted_at, player_uuid
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    players.add(readUuid(result, "player_uuid"));
                }
            }
        }
        return List.copyOf(players);
    }

    static boolean visitorExists(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_visitors WHERE town_id = ? AND player_uuid = ? LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static TownSnapshot.Visitor requireVisitor(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, invited_by, added_at FROM town_visitors
                 WHERE town_id = ? AND player_uuid = ?
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new NotFoundException("访客记录不存在");
                }
                return new TownSnapshot.Visitor(readUuid(result, "player_uuid"),
                        readUuid(result, "invited_by"),
                        result.getTimestamp("added_at").toInstant());
            }
        }
    }

    static void insert(Connection connection, UUID townId, UUID playerId, UUID actorId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO town_visitors (town_id, player_uuid, invited_by)
                VALUES (?, ?, ?)
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            statement.setBytes(3, uuid(actorId));
            statement.executeUpdate();
        }
    }

    static void delete(Connection connection, UUID townId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM town_visitors WHERE town_id = ? AND player_uuid = ?
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            if (statement.executeUpdate() != 1) {
                throw new ConflictException("目标玩家不在访客名单中");
            }
        }
    }

}

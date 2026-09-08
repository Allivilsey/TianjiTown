package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.town.MemberRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository.ConflictException;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.requireUpdated;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.uuid;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.readUuid;

/** Shared town mutations and checks; always uses the caller's transaction connection. */
final class GovernancePersistence {
    private GovernancePersistence() {
    }

    static void performMayorTransfer(Connection connection, UUID townId, UUID oldMayorId,
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
            MayorTransferValidity.cancelInvalid(connection);
        }
    }

    static void removeNonMayor(Connection connection, UUID townId, UUID targetId,
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
            MayorTransferValidity.cancelInvalid(connection);
        }
    }

    static void requireActiveTown(Connection connection, UUID townId) throws SQLException {
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

    static void requireRole(Connection connection, UUID townId, UUID playerId,
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

    static boolean memberExists(Connection connection, UUID townId, UUID playerId)
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

    static MemberRole memberRole(Connection connection, UUID townId, UUID playerId)
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

    static int deputyMayorCount(Connection connection, UUID townId) throws SQLException {
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

    static UUID mayorId(Connection connection, UUID townId) throws SQLException {
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

    static String townName(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM towns WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("小镇不存在");
                }
                return result.getString("name");
            }
        }
    }

    static void audit(Connection connection, UUID actorId, String actorName, String action,
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

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("原因不能为空且不能超过 500 个字符");
        }
    }

    static Instant shiftedInstant(Instant base, Duration duration, boolean future,
                                          String message) {
        try {
            Instant shifted = future ? base.plus(duration) : base.minus(duration);
            shifted.toEpochMilli();
            return shifted;
        } catch (ArithmeticException | DateTimeException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }
}

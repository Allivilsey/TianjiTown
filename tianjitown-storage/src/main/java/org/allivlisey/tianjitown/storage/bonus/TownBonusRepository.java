package org.allivlisey.tianjitown.storage.bonus;

import org.allivlisey.tianjitown.core.town.MemberRole;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class TownBonusRepository {
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public TownBonusRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    public BonusIndex loadBonusIndex() {
        requireWorkerThread();
        return query(connection -> {
            Map<UUID, UUID> memberships = new HashMap<>();
            Map<UUID, MemberRole> roles = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.player_uuid, m.town_id, m.role
                      FROM town_members m JOIN towns t ON t.town_id = m.town_id
                     WHERE t.status = 'ACTIVE'
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    memberships.put(readUuid(rows, "player_uuid"), readUuid(rows, "town_id"));
                    roles.put(readUuid(rows, "player_uuid"),
                            MemberRole.valueOf(rows.getString("role")));
                }
            }
            Map<ChunkKey, UUID> territories = new HashMap<>();
            Map<UUID, String> residences = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT u.town_id, u.world_uuid, c.chunk_x, c.chunk_z, u.residence_name
                      FROM territory_chunks c
                      JOIN territory_units u ON u.unit_id = c.unit_id
                      JOIN towns t ON t.town_id = u.town_id
                     WHERE t.status = 'ACTIVE' AND u.projection_status = 'ACTIVE'
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID townId = readUuid(rows, "town_id");
                    territories.put(new ChunkKey(readUuid(rows, "world_uuid"),
                            rows.getInt("chunk_x"), rows.getInt("chunk_z")), townId);
                    residences.putIfAbsent(townId, rows.getString("residence_name"));
                }
            }
            // Legacy town_beacon_effects rows are intentionally ignored. Live sources come from the world.
            return new BonusIndex(memberships, roles, territories, residences);
        });
    }

    public RefundReservation reserveBuildingRefund(UUID townId, UUID playerId, UUID worldId,
                                                    int chunkX, int chunkZ, LocalDate weekStart,
                                                    String materialKey, int weeklyLimit) {
        requireWorkerThread();
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(weekStart, "weekStart");
        Objects.requireNonNull(materialKey, "materialKey");
        if (weeklyLimit < 1) {
            throw new IllegalArgumentException("每周返还上限必须大于 0");
        }
        return transaction(connection -> {
            if (!ownsActiveTerritory(connection, townId, playerId, worldId, chunkX, chunkZ)) {
                return RefundReservation.denied("成员或领地状态已变化");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO building_refund_weekly
                        (town_id, player_uuid, week_start, refund_count, last_material_key)
                    VALUES (?, ?, ?, 1, ?)
                    ON CONFLICT (town_id, player_uuid, week_start) DO UPDATE SET
                        refund_count = building_refund_weekly.refund_count + 1,
                        last_material_key = excluded.last_material_key
                    WHERE building_refund_weekly.refund_count < ?
                    RETURNING refund_count
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(playerId));
                statement.setString(3, weekStart.toString());
                statement.setString(4, materialKey);
                statement.setInt(5, weeklyLimit);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next()
                            ? RefundReservation.granted(row.getInt("refund_count"), weeklyLimit)
                            : RefundReservation.denied("本周建筑返还已达到上限 " + weeklyLimit);
                }
            }
        });
    }

    public int cleanupRefundCounters(LocalDate oldestRetainedWeek) {
        requireWorkerThread();
        Objects.requireNonNull(oldestRetainedWeek, "oldestRetainedWeek");
        return transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM building_refund_weekly WHERE week_start < ?")) {
                statement.setString(1, oldestRetainedWeek.toString());
                return statement.executeUpdate();
            }
        });
    }

    private static boolean ownsActiveTerritory(Connection connection, UUID townId, UUID playerId,
                                                UUID worldId, int chunkX, int chunkZ)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members m
                JOIN towns t ON t.town_id = m.town_id
                JOIN territory_units u ON u.town_id = t.town_id
                JOIN territory_chunks c ON c.unit_id = u.unit_id
                WHERE m.player_uuid = ? AND m.town_id = ? AND t.status = 'ACTIVE'
                  AND u.projection_status = 'ACTIVE' AND c.world_uuid = ?
                  AND c.chunk_x = ? AND c.chunk_z = ?
                LIMIT 1
                """)) {
            statement.setBytes(1, uuid(playerId));
            statement.setBytes(2, uuid(townId));
            statement.setBytes(3, uuid(worldId));
            statement.setInt(4, chunkX);
            statement.setInt(5, chunkZ);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("禁止在 Paper 主线程执行数据库 I/O");
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
            boolean begun = false;
            try {
                executeTransactionCommand(connection, "BEGIN IMMEDIATE");
                begun = true;
                T result = work.run(connection);
                executeTransactionCommand(connection, "COMMIT");
                begun = false;
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, begun, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private static void rollback(Connection connection, boolean begun, Throwable failure) {
        if (!begun) {
            return;
        }
        try {
            executeTransactionCommand(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void executeTransactionCommand(Connection connection, String command)
            throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private static RuntimeException translate(SQLException exception) {
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 19) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("SQLite 操作失败: " + exception.getMessage(),
                exception);
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID readUuid(ResultSet row, String column) throws SQLException {
        ByteBuffer buffer = ByteBuffer.wrap(row.getBytes(column));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    public record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    public record BonusIndex(Map<UUID, UUID> memberships, Map<UUID, MemberRole> roles,
                             Map<ChunkKey, UUID> territories, Map<UUID, String> residenceNames) {
        public BonusIndex {
            memberships = Map.copyOf(memberships);
            roles = Map.copyOf(roles);
            territories = Map.copyOf(territories);
            residenceNames = Map.copyOf(residenceNames);
        }
    }

    public record RefundReservation(boolean granted, int used, int limit, String message) {
        public static RefundReservation granted(int used, int limit) {
            return new RefundReservation(true, used, limit, "返还已预留");
        }

        public static RefundReservation denied(String message) {
            return new RefundReservation(false, 0, 0, message);
        }
    }

    public static class ConflictException extends RuntimeException {
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

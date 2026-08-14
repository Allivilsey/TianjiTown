package cn.tianji.town.storage.phase5;

import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.town.MemberRole;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class PhaseFiveRepository {
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public PhaseFiveRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
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
            Map<UUID, Map<String, Integer>> beaconEffects = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT e.town_id, e.effect_key, e.amplifier
                      FROM town_beacon_effects e JOIN towns t ON t.town_id = e.town_id
                     WHERE t.status = 'ACTIVE'
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    beaconEffects.computeIfAbsent(readUuid(rows, "town_id"),
                                    ignored -> new HashMap<>())
                            .put(rows.getString("effect_key"), rows.getInt("amplifier"));
                }
            }
            return new BonusIndex(memberships, roles, territories, residences, beaconEffects);
        });
    }

    public boolean recordBeaconEffect(UUID townId, String effectKey, int amplifier) {
        requireWorkerThread();
        Objects.requireNonNull(townId, "townId");
        if (effectKey == null || effectKey.isBlank() || effectKey.length() > 128) {
            throw new IllegalArgumentException("信标效果键无效");
        }
        if (amplifier < 0) {
            throw new IllegalArgumentException("信标效果等级不能小于 0");
        }
        return transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_beacon_effects (town_id, effect_key, amplifier)
                    SELECT ?, ?, ?
                     WHERE EXISTS (SELECT 1 FROM towns WHERE town_id = ? AND status = 'ACTIVE')
                    ON CONFLICT (town_id, effect_key) DO UPDATE SET
                        amplifier = excluded.amplifier
                    WHERE excluded.amplifier > town_beacon_effects.amplifier
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setString(2, effectKey);
                statement.setInt(3, amplifier);
                statement.setBytes(4, uuid(townId));
                return statement.executeUpdate() > 0;
            }
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

    public DiagnosticSnapshot diagnose(Instant quickShopSince) {
        requireWorkerThread();
        Objects.requireNonNull(quickShopSince, "quickShopSince");
        return query(connection -> {
            String quickCheck;
            try (Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("PRAGMA quick_check")) {
                quickCheck = row.next() ? row.getString(1) : "无结果";
            }
            int foreignKeyViolations = countRows(connection, "PRAGMA foreign_key_check");
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("activeTowns", scalar(connection,
                    "SELECT COUNT(*) FROM towns WHERE status = 'ACTIVE'"));
            counts.put("members", scalar(connection, "SELECT COUNT(*) FROM town_members"));
            counts.put("territoryUnits", scalar(connection,
                    "SELECT COUNT(*) FROM territory_units WHERE reuse_blocked = 1"));
            counts.put("failedProjections", scalar(connection, """
                    SELECT COUNT(*) FROM territory_units u
                      JOIN towns t ON t.town_id = u.town_id
                     WHERE t.status = 'ACTIVE' AND u.reuse_blocked = 1
                       AND u.projection_status <> 'ACTIVE'
                    """));
            counts.put("lockedAccounts", scalar(connection,
                    "SELECT COUNT(*) FROM town_accounts WHERE locked = 1"));
            counts.put("openOrders", scalar(connection, """
                    SELECT COUNT(*) FROM resource_orders
                     WHERE status IN ('PENDING', 'CLAIMING', 'REFUND_REQUIRED')
                    """));
            counts.put("pendingEconomy", scalar(connection, """
                    SELECT COUNT(*) FROM economy_operations
                     WHERE status IN ('PREPARED', 'EXTERNAL_APPLIED', 'COMPENSATION_REQUIRED')
                    """));
            counts.put("pendingExpansions", scalar(connection, """
                    SELECT COUNT(*) FROM territory_expansions
                     WHERE status IN ('PREPARED', 'COMPENSATION_REQUIRED')
                    """));
            counts.put("accountLedgerMismatches", scalar(connection, """
                    SELECT COUNT(*) FROM town_accounts a
                     WHERE a.balance_minor <> COALESCE((
                         SELECT l.balance_after_minor FROM ledger_entries l
                          WHERE l.town_id = a.town_id
                          ORDER BY l.created_at DESC, l.rowid DESC LIMIT 1
                     ), 0)
                    """));
            long internalTaxCount;
            long internalTaxMinor;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) AS records, COALESCE(SUM(tax_minor), 0) AS total
                      FROM quickshop_tax_records WHERE created_at >= ?
                    """)) {
                statement.setLong(1, quickShopSince.toEpochMilli());
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    internalTaxCount = row.getLong("records");
                    internalTaxMinor = row.getLong("total");
                }
            }
            return new DiagnosticSnapshot(quickCheck, foreignKeyViolations, counts,
                    internalTaxCount, internalTaxMinor, loadLandStates(connection));
        });
    }

    private static List<LandState> loadLandStates(Connection connection) throws SQLException {
        Map<UUID, LandBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.town_id, t.name, u.residence_name, u.residence_area_name,
                       u.world_uuid, u.world_name, u.center_chunk_x, u.center_chunk_z
                  FROM towns t JOIN territory_units u ON u.town_id = t.town_id
                 WHERE t.status = 'ACTIVE' AND u.reuse_blocked = 1
                 ORDER BY t.created_at, u.grid_z, u.grid_x
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID townId = readUuid(rows, "town_id");
                LandBuilder builder = builders.computeIfAbsent(townId,
                        ignored -> new LandBuilder(townId, rowsSafe(rows, "name"),
                                rowsSafe(rows, "residence_name")));
                InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                        readUuid(rows, "world_uuid"), rows.getString("world_name"),
                        rows.getInt("center_chunk_x"), rows.getInt("center_chunk_z")));
                builder.areas.add(new LandProtectionService.Area(
                        rows.getString("residence_area_name"), territory));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.town_id, m.player_uuid FROM town_members m
                JOIN towns t ON t.town_id = m.town_id WHERE t.status = 'ACTIVE'
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                LandBuilder builder = builders.get(readUuid(rows, "town_id"));
                if (builder != null) {
                    builder.members.add(readUuid(rows, "player_uuid"));
                }
            }
        }
        return builders.values().stream().map(LandBuilder::build).toList();
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

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    private static int countRows(Connection connection, String sql) throws SQLException {
        int count = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                count++;
            }
        }
        return count;
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
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 19) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("SQLite 操作失败: " + exception.getMessage(),
                exception);
    }

    private static String rowsSafe(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException exception) {
            throw new StorageUnavailableException("读取 SQLite 诊断数据失败", exception);
        }
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

    private static final class LandBuilder {
        private final UUID townId;
        private final String townName;
        private final String residenceName;
        private final List<UUID> members = new ArrayList<>();
        private final List<LandProtectionService.Area> areas = new ArrayList<>();

        private LandBuilder(UUID townId, String townName, String residenceName) {
            this.townId = townId;
            this.townName = townName;
            this.residenceName = residenceName;
        }

        private LandState build() {
            return new LandState(townId, townName, residenceName, members, areas);
        }
    }

    public record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    public record BonusIndex(Map<UUID, UUID> memberships, Map<UUID, MemberRole> roles,
                             Map<ChunkKey, UUID> territories, Map<UUID, String> residenceNames,
                             Map<UUID, Map<String, Integer>> beaconEffects) {
        public BonusIndex {
            memberships = Map.copyOf(memberships);
            roles = Map.copyOf(roles);
            territories = Map.copyOf(territories);
            residenceNames = Map.copyOf(residenceNames);
            Map<UUID, Map<String, Integer>> immutableEffects = new HashMap<>();
            beaconEffects.forEach((townId, effects) -> immutableEffects.put(townId,
                    Map.copyOf(effects)));
            beaconEffects = Map.copyOf(immutableEffects);
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

    public record DiagnosticSnapshot(String quickCheck, int foreignKeyViolations,
                                     Map<String, Long> counts, long internalTaxCount,
                                     long internalTaxMinor, List<LandState> landStates) {
        public DiagnosticSnapshot {
            counts = Map.copyOf(counts);
            landStates = List.copyOf(landStates);
        }
    }

    public record LandState(UUID townId, String townName, String residenceName,
                            List<UUID> members, List<LandProtectionService.Area> areas) {
        public LandState {
            members = List.copyOf(members);
            areas = List.copyOf(areas);
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

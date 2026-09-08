package org.allivlisey.tianjitown.storage.diagnostics;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.economy.QuickShopPurchase;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class TownDiagnosticRepository {
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public TownDiagnosticRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
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
            List<QuickShopPurchase> purchases = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT shop_id, shop_type, interacting_uuid, gross_minor, tax_minor, created_at
                      FROM quickshop_tax_records WHERE created_at >= ?
                     ORDER BY created_at
                    """)) {
                statement.setLong(1, quickShopSince.toEpochMilli());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        purchases.add(new QuickShopPurchase(rows.getLong("shop_id"),
                                rows.getString("shop_type"), readUuid(rows, "interacting_uuid"),
                                rows.getLong("gross_minor"), rows.getLong("tax_minor"),
                                Instant.ofEpochMilli(rows.getLong("created_at"))));
                    }
                }
            }
            return new DiagnosticSnapshot(quickCheck, foreignKeyViolations, counts,
                    purchases.size(), purchases.stream().mapToLong(QuickShopPurchase::taxMinor)
                            .reduce(0, Math::addExact), loadLandStates(connection), purchases);
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
                UNION
                SELECT v.town_id, v.player_uuid FROM town_visitors v
                JOIN towns t ON t.town_id = v.town_id WHERE t.status = 'ACTIVE'
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

    private static String rowsSafe(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException exception) {
            throw new StorageUnavailableException("读取 SQLite 诊断数据失败", exception);
        }
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

    public record DiagnosticSnapshot(String quickCheck, int foreignKeyViolations,
                                     Map<String, Long> counts, long internalTaxCount,
                                     long internalTaxMinor, List<LandState> landStates,
                                     List<QuickShopPurchase> purchases) {
        public DiagnosticSnapshot {
            counts = Map.copyOf(counts);
            landStates = List.copyOf(landStates);
            purchases = List.copyOf(purchases);
        }
    }

    public record LandState(UUID townId, String townName, String residenceName,
                            List<UUID> members, List<LandProtectionService.Area> areas) {
        public LandState {
            members = List.copyOf(members);
            areas = List.copyOf(areas);
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

    private static RuntimeException translate(SQLException exception) {
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 19) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("SQLite 操作失败: " + exception.getMessage(),
                exception);
    }

    private static UUID readUuid(ResultSet row, String column) throws SQLException {
        ByteBuffer buffer = ByteBuffer.wrap(row.getBytes(column));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
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

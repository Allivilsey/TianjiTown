package cn.tianji.town.storage.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseGateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesSqliteSchemaWithRequiredPragmas() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("town.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();
            assertTrue(health.healthy(), health.detail());
            assertTrue(gate.ping());
            try (Connection connection = gate.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
                    assertTrue(result.next());
                    assertEquals("wal", result.getString(1));
                }
                try (ResultSet result = statement.executeQuery("PRAGMA busy_timeout")) {
                    assertTrue(result.next());
                    assertEquals(5_000, result.getInt(1));
                }
                try (ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'towns'")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }

    @Test
    void rejectsNonSqliteConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConfig(
                "jdbc:mysql://localhost/town", Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    @Test
    void upgradesSchemaOneDatabaseWithoutRebuildingExistingTown() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("schema1-upgrade.db");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target("1.1").load().migrate();
        UUID townId = UUID.randomUUID();
        UUID mayorId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, '旧版镇', '旧版镇', '旧', '旧', '升级测试', '规则', 'ACTIVE', ?)
                     """);
             PreparedStatement member = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role)
                     VALUES (?, ?, 'MAYOR')
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setBytes(2, uuid(mayorId));
            town.executeUpdate();
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(mayorId));
            member.executeUpdate();
        }

        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();
            assertTrue(health.healthy(), health.detail());
            assertTrue(health.detail().contains("schema=8.0"));
            try (Connection connection = gate.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT t.name, t.rules_revision, m.role
                           FROM towns t JOIN town_members m ON m.town_id = t.town_id
                          WHERE t.town_id = ? AND m.player_uuid = ?
                         """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(mayorId));
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("旧版镇", result.getString("name"));
                    assertEquals(1, result.getInt("rules_revision"));
                    assertEquals("MAYOR", result.getString("role"));
                }
            }
        }
    }

    @Test
    void upgradesExistingVotesToOneOpenVotePerTown() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("schema2-vote-upgrade.db");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target("2.0").load().migrate();
        UUID townId = UUID.randomUUID();
        UUID mayorId = UUID.randomUUID();
        UUID firstVoteId = UUID.randomUUID();
        UUID secondVoteId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, '迁移镇', '迁移镇', '迁', '迁', '升级测试', '规则', 'ACTIVE', ?)
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setBytes(2, uuid(mayorId));
            town.executeUpdate();
            insertVote(connection, firstVoteId, townId, mayorId, "KICK_MEMBER", 1_000L);
            insertVote(connection, secondVoteId, townId, mayorId, "REPLACE_MAYOR", 2_000L);
        }

        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();
            assertTrue(health.healthy(), health.detail());
            assertTrue(health.detail().contains("schema=8.0"));
            try (Connection connection = gate.dataSource().getConnection();
                 PreparedStatement votes = connection.prepareStatement("""
                         SELECT status, cancelled_reason FROM governance_votes
                          WHERE town_id = ? ORDER BY created_at
                         """)) {
                votes.setBytes(1, uuid(townId));
                try (ResultSet result = votes.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("OPEN", result.getString("status"));
                    assertTrue(result.next());
                    assertEquals("CANCELLED", result.getString("status"));
                    assertEquals("升级治理约束时取消同镇重复开放投票",
                            result.getString("cancelled_reason"));
                    assertFalse(result.next());
                }
                assertThrows(SQLException.class,
                        () -> insertVote(connection, UUID.randomUUID(), townId, mayorId,
                                "KICK_MEMBER", 3_000L));
            }
        }
    }

    @Test
    void upgradesRolesWeeklyRefundsAndHistoricalResourceData() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("schema6-upgrade.db");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target("5.0").load().migrate();
        UUID townId = UUID.randomUUID();
        UUID mayorId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url)) {
            try (PreparedStatement town = connection.prepareStatement("""
                    INSERT INTO towns
                        (town_id, name, normalized_name, short_name, normalized_short_name,
                         description, rules_text, status, mayor_uuid)
                    VALUES (?, '六期镇', '六期镇', '六', '六', '升级测试', '规则', 'ACTIVE', ?)
                    """)) {
                town.setBytes(1, uuid(townId));
                town.setBytes(2, uuid(mayorId));
                town.executeUpdate();
            }
            insertMember(connection, townId, mayorId, "MAYOR", 0L);
            for (int index = 0; index < 4; index++) {
                insertMember(connection, townId, UUID.randomUUID(), "OFFICER", index + 1L);
            }
            insertHistoricalResourceData(connection, townId, mayorId);
            try (PreparedStatement refund = connection.prepareStatement("""
                    INSERT INTO building_refund_daily
                        (town_id, player_uuid, day_key, refund_count, last_material_key)
                    VALUES (?, ?, ?, ?, 'minecraft:stone')
                    """)) {
                for (String day : java.util.List.of("2026-08-10", "2026-08-12")) {
                    refund.setBytes(1, uuid(townId));
                    refund.setBytes(2, uuid(mayorId));
                    refund.setString(3, day);
                    refund.setInt(4, 7);
                    refund.addBatch();
                }
                refund.executeBatch();
            }
        }

        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();
            assertTrue(health.healthy(), health.detail());
            try (Connection connection = gate.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                try (ResultSet roles = statement.executeQuery("""
                        SELECT role, COUNT(*) AS total FROM town_members
                         GROUP BY role ORDER BY role
                        """)) {
                    assertTrue(roles.next());
                    assertEquals("DEPUTY_MAYOR", roles.getString("role"));
                    assertEquals(3, roles.getInt("total"));
                    assertTrue(roles.next());
                    assertEquals("MAYOR", roles.getString("role"));
                    assertTrue(roles.next());
                    assertEquals("MEMBER", roles.getString("role"));
                }
                try (ResultSet refund = statement.executeQuery("""
                        SELECT week_start, refund_count FROM building_refund_weekly
                        """)) {
                    assertTrue(refund.next());
                    assertEquals("2026-08-10", refund.getString("week_start"));
                    assertEquals(14, refund.getInt("refund_count"));
                }
                try (ResultSet order = statement.executeQuery("""
                        SELECT resource_key, quantity, status FROM resource_orders
                         WHERE business_key = 'legacy-resource:test'
                        """)) {
                    assertTrue(order.next());
                    assertEquals("iron", order.getString("resource_key"));
                    assertEquals(16, order.getInt("quantity"));
                    assertEquals("PENDING", order.getString("status"));
                }
                try (ResultSet ledger = statement.executeQuery("""
                        SELECT entry_type, amount_minor FROM ledger_entries
                         WHERE business_key = 'legacy-resource:test'
                        """)) {
                    assertTrue(ledger.next());
                    assertEquals("RESOURCE_PURCHASE", ledger.getString("entry_type"));
                    assertEquals(-8_000, ledger.getLong("amount_minor"));
                }
            }
        }
    }

    @Test
    void createsConsistentOnlineBackupWithoutOverwriting() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("online-source.db");
        Path backup = temporaryDirectory.resolve("backup").resolve("town.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            gate.onlineBackup(backup);
            assertTrue(java.nio.file.Files.isRegularFile(backup));
            assertThrows(IllegalArgumentException.class, () -> gate.onlineBackup(backup));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + backup);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            assertTrue(result.next());
            assertEquals("ok", result.getString(1));
        }
    }

    @Test
    void rejectsReadOnlyDatabaseBeforeReportingHealthy() {
        Path database = temporaryDirectory.resolve("read-only.db").toAbsolutePath();
        initializeDatabase("jdbc:sqlite:" + database);
        String readOnlyUrl = "jdbc:sqlite:file:"
                + database.toString().replace('\\', '/') + "?mode=ro";

        boolean rejected = false;
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(readOnlyUrl,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();
            rejected = !health.healthy();
            if (!health.healthy()) {
                assertTrue(health.detail().toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(不可写|readonly|read-only).*"), health.detail());
            }
        } catch (RuntimeException exception) {
            rejected = true;
        }
        assertTrue(rejected, "只读 SQLite 不能通过启动门禁");
    }

    @Test
    void rejectsMigrationNewerThanSupportedSchema() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("future-migration.db");
        initializeDatabase(url);
        insertMigrationHistory(url, "99.0", "V99_0__unknown_future.sql", true);

        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();

            assertFalse(health.healthy());
            assertTrue(health.detail().contains("高于当前插件支持范围"), health.detail());
            assertTrue(health.detail().contains("99.0"), health.detail());
        }
    }

    @Test
    void rejectsFailedMigrationHistory() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("failed-migration.db");
        initializeDatabase(url);
        insertMigrationHistory(url, "8.1", "V8_1__interrupted_test.sql", false);

        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();

            assertFalse(health.healthy());
            assertTrue(health.detail().contains("失败的 Flyway 迁移"), health.detail());
            assertTrue(health.detail().contains("8.1"), health.detail());
        }
    }

    private static void initializeDatabase(String url) {
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .load().migrate();
    }

    private static void insertMigrationHistory(String url, String version, String script,
                                               boolean success) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO flyway_schema_history
                         (installed_rank, version, description, type, script, checksum,
                          installed_by, execution_time, success)
                      VALUES ((SELECT COALESCE(MAX(installed_rank), 0) + 1
                                 FROM flyway_schema_history),
                              ?, '测试迁移', 'SQL', ?, 1, 'test', 1, ?)
                     """)) {
            statement.setString(1, version);
            statement.setString(2, script);
            statement.setBoolean(3, success);
            statement.executeUpdate();
        }
    }

    private static void insertVote(Connection connection, UUID voteId, UUID townId, UUID actorId,
                                   String type, long createdAt) throws SQLException {
        try (PreparedStatement vote = connection.prepareStatement("""
                INSERT INTO governance_votes
                    (vote_id, town_id, vote_type, subject_uuid, candidate_uuid, created_by,
                     status, eligible_voters, required_yes, ends_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'OPEN', 1, 1, 9999999999999, ?)
                """)) {
            vote.setBytes(1, uuid(voteId));
            vote.setBytes(2, uuid(townId));
            vote.setString(3, type);
            vote.setBytes(4, uuid(actorId));
            vote.setBytes(5, uuid(actorId));
            vote.setBytes(6, uuid(actorId));
            vote.setLong(7, createdAt);
            vote.executeUpdate();
        }
    }

    private static void insertMember(Connection connection, UUID townId, UUID playerId,
                                     String role, long joinedAt) throws SQLException {
        try (PreparedStatement member = connection.prepareStatement("""
                INSERT INTO town_members (town_id, player_uuid, role, joined_at)
                VALUES (?, ?, ?, ?)
                """)) {
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(playerId));
            member.setString(3, role);
            member.setLong(4, joinedAt);
            member.executeUpdate();
        }
    }

    private static void insertHistoricalResourceData(Connection connection, UUID townId,
                                                     UUID buyerId) throws SQLException {
        try (PreparedStatement account = connection.prepareStatement("""
                UPDATE town_accounts SET balance_minor = 92000 WHERE town_id = ?
                """);
             PreparedStatement order = connection.prepareStatement("""
                INSERT INTO resource_orders
                    (order_id, town_id, buyer_uuid, buyer_name, resource_key, resource_name,
                     material_key, quantity, total_minor, business_key, status)
                VALUES (?, ?, ?, 'Mayor', 'iron', '铁锭补给', 'minecraft:iron_ingot',
                        16, 8000, 'legacy-resource:test', 'PENDING')
                """);
             PreparedStatement ledger = connection.prepareStatement("""
                INSERT INTO ledger_entries
                    (entry_id, town_id, entry_type, amount_minor, balance_after_minor,
                     actor_uuid, actor_name, business_key, note)
                VALUES (?, ?, 'RESOURCE_PURCHASE', -8000, 92000, ?, 'Mayor',
                        'legacy-resource:test', '历史资源采购')
                """)) {
            account.setBytes(1, uuid(townId));
            account.executeUpdate();
            order.setBytes(1, uuid(UUID.randomUUID()));
            order.setBytes(2, uuid(townId));
            order.setBytes(3, uuid(buyerId));
            order.executeUpdate();
            ledger.setBytes(1, uuid(UUID.randomUUID()));
            ledger.setBytes(2, uuid(townId));
            ledger.setBytes(3, uuid(buyerId));
            ledger.executeUpdate();
        }
    }

    private static byte[] uuid(UUID value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }
}

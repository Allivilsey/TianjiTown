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
    void upgradesPhaseOneDatabaseWithoutRebuildingExistingTown() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("phase1-upgrade.db");
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
            assertTrue(health.detail().contains("schema=4.0"));
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
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("phase2-vote-upgrade.db");
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
            assertTrue(health.detail().contains("schema=4.0"));
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

    private static byte[] uuid(UUID value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }
}

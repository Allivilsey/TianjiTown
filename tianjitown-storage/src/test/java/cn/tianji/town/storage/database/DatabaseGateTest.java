package cn.tianji.town.storage.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertTrue(health.detail().contains("schema=2.0"));
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

    private static byte[] uuid(UUID value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }
}

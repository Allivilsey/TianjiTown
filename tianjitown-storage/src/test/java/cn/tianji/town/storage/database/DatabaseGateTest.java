package cn.tianji.town.storage.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

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
}

package org.allivlisey.tianjitown.storage.database;

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
            assertTrue(health.detail().contains("schema=1.3"), health.detail());
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
        insertMigrationHistory(url, "1.1", "V1_1__interrupted_test.sql", false);

        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            DatabaseGate.HealthResult health = gate.verifyAndMigrate();

            assertFalse(health.healthy());
            assertTrue(health.detail().contains("失败的 Flyway 迁移"), health.detail());
            assertTrue(health.detail().contains("1.1"), health.detail());
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
}

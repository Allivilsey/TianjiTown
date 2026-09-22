package org.allivlisey.tianjitown.storage.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class TownEconomyUpgradeTest {
    @TempDir Path directory;

    @Test void upgradePreservesBalancesAndUnknownOperationsAndOnlyRemovesBankLocks() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("legacy.db");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target("1.0").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            for (int i = 1; i <= 3; i++) {
                String id = "X'" + String.format("%032x", i) + "'";
                sql.execute("INSERT INTO towns(town_id,name,normalized_name,description,rules_text,status,mayor_uuid) VALUES ("
                        + id + ",'town" + i + "','town" + i + "','','','ACTIVE'," + id + ")");
                String lock = i == 3 ? "LEDGER_RECONCILIATION: manual" : "SETTLEMENT_RECONCILIATION: shortfall";
                sql.execute("UPDATE town_accounts SET balance_minor=" + i * 100 + ",locked=1,lock_reason='" + lock + "' WHERE town_id=" + id);
            }
            sql.execute("""
                    INSERT INTO economy_operations(operation_id,town_id,operation_type,amount_minor,actor_name,business_key,note,status)
                    VALUES (zeroblob(16),X'00000000000000000000000000000002','ADMIN_ADJUSTMENT',-10,'Admin','legacy:unknown','legacy','COMPENSATION_REQUIRED')
                    """);
        }
        for (int restart = 0; restart < 2; restart++) {
            try (var gate = new DatabaseGate(new DatabaseConfig(url, Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
                var health = gate.verifyAndMigrate();
                assertTrue(health.healthy(), health.detail());
                assertEquals("1.1", gate.schemaVersion());
                try (var connection = gate.dataSource().getConnection(); var sql = connection.createStatement()) {
                    try (var rows = sql.executeQuery("SELECT balance_minor,locked,lock_reason FROM town_accounts ORDER BY balance_minor")) {
                        assertTrue(rows.next());
                        assertEquals(100, rows.getLong(1));
                        assertFalse(rows.getBoolean(2));
                        assertNull(rows.getString(3));
                        assertTrue(rows.next());
                        assertEquals(200, rows.getLong(1));
                        assertTrue(rows.getBoolean(2));
                        assertTrue(rows.getString(3).startsWith("ECONOMY_COMPENSATION:"));
                        assertTrue(rows.next());
                        assertEquals(300, rows.getLong(1));
                        assertTrue(rows.getBoolean(2));
                        assertEquals("LEDGER_RECONCILIATION: manual", rows.getString(3));
                    }
                    try (var rows = sql.executeQuery("SELECT status,amount_minor FROM economy_operations")) {
                        assertTrue(rows.next());
                        assertEquals("COMPENSATION_REQUIRED", rows.getString(1));
                        assertEquals(-10, rows.getLong(2));
                        assertFalse(rows.next());
                    }
                    try (var rows = sql.executeQuery("SELECT COUNT(*) FROM ledger_entries")) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
            }
        }
    }
}

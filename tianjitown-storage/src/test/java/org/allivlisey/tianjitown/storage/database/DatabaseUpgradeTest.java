package org.allivlisey.tianjitown.storage.database;

import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseUpgradeTest {
    // Flyway checksum of the published V1_0 script in commit 027107a.
    // Do not update this value when adding a migration: production already stores it.
    private static final int PUBLISHED_V1_0_CHECKSUM = -142058434;
    private static final UUID APPLICATION_ID = new UUID(0, 2);
    @TempDir Path directory;

    @Test
    void upgradesPublishedDatabaseAndPreservesBusinessDataAcrossRestarts() throws Exception {
        String url = initializePublishedDatabase();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO towns(town_id, name, normalized_name, description, rules_text, status, mayor_uuid)
                    VALUES (zeroblob(16), '旧小镇', 'legacy', '原简介', '原规则', 'ACTIVE', zeroblob(16))
                    """);
            statement.executeUpdate("UPDATE town_accounts SET balance_minor=123456, version=7");
            statement.executeUpdate("""
                    INSERT INTO town_members(town_id, player_uuid, role)
                    VALUES (zeroblob(16), zeroblob(16), 'MAYOR')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ledger_entries(entry_id, town_id, entry_type, amount_minor,
                        balance_after_minor, actor_name, business_key, note)
                    VALUES (zeroblob(16), zeroblob(16), 'DONATION', 123456, 123456,
                        'Mayor', 'legacy-donation', '原账本')
                    """);
            insertLegacyApplication(connection, ApplicationSnapshot.FeeStatus.REFUND_PENDING);
            assertEquals("0", scalar(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE name IN ('application_fee_operations', 'income_tax_collections')
                    """));
        }

        for (int cycle = 0; cycle < 2; cycle++) {
            try (var gate = gate(url)) {
                var health = gate.verifyAndMigrate();
                assertTrue(health.healthy(), health.detail());
                assertEquals("1.1", gate.schemaVersion());
                try (var connection = gate.dataSource().getConnection()) {
                    assertEquals("旧小镇", scalar(connection, "SELECT name FROM towns"));
                    assertEquals("123456:7", scalar(connection,
                            "SELECT balance_minor || ':' || version FROM town_accounts"));
                    assertEquals("MAYOR", scalar(connection, "SELECT role FROM town_members"));
                    assertEquals("legacy-donation:123456:原账本", scalar(connection,
                            "SELECT business_key || ':' || amount_minor || ':' || note FROM ledger_entries"));
                    assertEquals("2", scalar(connection,
                            "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"));
                    assertEquals(String.valueOf(PUBLISHED_V1_0_CHECKSUM), scalar(connection,
                            "SELECT checksum FROM flyway_schema_history WHERE version='1.0'"));
                    assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM income_tax_collections"));
                    assertEquals("1", scalar(connection, """
                            SELECT COUNT(*) FROM sqlite_master
                            WHERE type='index' AND name='ix_income_tax_collection_recovery'
                            """));
                    try (var statement = connection.createStatement();
                         var violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                        assertFalse(violations.next());
                    }
                }
                var repository = new TownRepository(gate.dataSource(), () -> false);
                if (cycle == 0) {
                    // Existing refundable fees can enter the new durable operation table.
                    var refund = repository.claimApplicationFeeRefund(APPLICATION_ID, new UUID(0, 3), "Admin");
                    assertEquals(ApplicationFeeOperation.State.REFUNDING, refund.state());
                    repository.completeApplicationFeeOperation(refund, ApplicationFeeOperation.Outcome.SUCCESS,
                            "测试模拟外部退款成功", new UUID(0, 3), "Admin");
                }
                assertEquals(ApplicationFeeOperation.State.REFUNDED,
                        repository.applicationFeeOperation(APPLICATION_ID).state());
                assertTrue(repository.pendingApplicationFees(100).isEmpty());
                assertThrows(TownRepository.ConflictException.class,
                        () -> repository.claimApplicationFeeRefund(APPLICATION_ID, new UUID(0, 3), "Admin"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ApplicationSnapshot.FeeStatus.class)
    void readsLegacyFeeStatesWithoutInventingPaymentOperations(ApplicationSnapshot.FeeStatus status)
            throws Exception {
        String url = initializePublishedDatabase();
        try (var connection = DriverManager.getConnection(url)) {
            insertLegacyApplication(connection, status);
        }
        try (var gate = gate(url)) {
            var health = gate.verifyAndMigrate();
            assertTrue(health.healthy(), health.detail());
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var application = repository.findApplication(APPLICATION_ID).orElseThrow();
            assertEquals(status, application.applicationFeeStatus());
            var operation = repository.applicationFeeOperation(APPLICATION_ID);
            var expected = switch (status) {
                case CONSUMED -> ApplicationFeeOperation.State.ESCROWED;
                default -> ApplicationFeeOperation.State.valueOf(status.name());
            };
            assertEquals(expected, operation.state());
            assertEquals(status == ApplicationSnapshot.FeeStatus.UNPAID ? 0 : 5000, operation.amountMinor());
            assertEquals(-1, operation.version());
            try (var connection = gate.dataSource().getConnection()) {
                assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM application_fee_operations"));
                assertEquals("0", scalar(connection, "SELECT COUNT(*) FROM income_tax_collections"));
            }
        }
    }

    @Test
    void rejectsChangedPublishedChecksumBeforeApplyingTheUpgrade() throws Exception {
        String url = initializePublishedDatabase();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1.0'");
        }
        try (var gate = gate(url)) {
            var health = gate.verifyAndMigrate();
            assertFalse(health.healthy());
            assertTrue(health.detail().contains("checksum mismatch"), health.detail());
            try (var connection = gate.dataSource().getConnection()) {
                assertEquals("0", scalar(connection, """
                        SELECT COUNT(*) FROM sqlite_master
                        WHERE name IN ('application_fee_operations', 'income_tax_collections')
                        """));
                assertEquals(String.valueOf(PUBLISHED_V1_0_CHECKSUM + 1), scalar(connection,
                        "SELECT checksum FROM flyway_schema_history WHERE version='1.0'"));
            }
        }
    }

    private String initializePublishedDatabase() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("upgrade.db");
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .target("1.0").load().migrate();
        try (var connection = DriverManager.getConnection(url)) {
            assertEquals(String.valueOf(PUBLISHED_V1_0_CHECKSUM), scalar(connection,
                    "SELECT checksum FROM flyway_schema_history WHERE version='1.0'"),
                    "The published baseline must not change; add a versioned migration instead");
        }
        return url;
    }

    private static DatabaseGate gate(String url) {
        return new DatabaseGate(new DatabaseConfig(url, Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    private static void insertLegacyApplication(Connection connection, ApplicationSnapshot.FeeStatus status)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO town_applications(application_id, applicant_uuid, name, normalized_name,
                    residence_name, description, rules_text, status, application_fee_status, application_fee_minor)
                VALUES (X'00000000000000000000000000000002', zeroblob(16), '旧申请', 'oldapplication',
                    'oldapplication', '原简介', '原规则', 'CANCELLED', ?, ?)
                """)) {
            statement.setString(1, status.name());
            statement.setLong(2, status == ApplicationSnapshot.FeeStatus.UNPAID ? 0 : 5000);
            statement.executeUpdate();
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            String value = rows.getString(1);
            assertFalse(rows.next());
            return value;
        }
    }
}

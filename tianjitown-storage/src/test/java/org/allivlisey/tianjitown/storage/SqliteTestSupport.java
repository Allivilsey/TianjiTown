package org.allivlisey.tianjitown.storage;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class SqliteTestSupport {
    private SqliteTestSupport() {
    }

    public static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    public static void execute(DatabaseGate gate, String sql) throws SQLException {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public static long scalar(DatabaseGate gate, String sql) throws SQLException {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    public static String scalarText(DatabaseGate gate, String sql) throws SQLException {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    public static void installAuditFailure(DatabaseGate gate, String triggerName, String action)
            throws SQLException {
        execute(gate, "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON audit_logs "
                + "WHEN NEW.action = '" + action + "' BEGIN "
                + "SELECT RAISE(ABORT, 'injected audit failure'); END");
    }
}

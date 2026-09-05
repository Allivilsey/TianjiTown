package org.allivlisey.tianjitown.storage.governance;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository.ConflictException;

/** JDBC value conversion and update checks shared by governance stores. */
final class GovernanceSqlValues {
    private GovernanceSqlValues() {
    }

    static void requireUpdated(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new ConflictException(message);
        }
    }

    static byte[] uuid(UUID value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }

    static UUID uuid(byte[] value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static UUID readUuid(ResultSet result, String column) throws SQLException {
        return uuid(result.getBytes(column));
    }

    static Instant instant(ResultSet result, String column) throws SQLException {
        return Instant.ofEpochMilli(result.getLong(column));
    }
}

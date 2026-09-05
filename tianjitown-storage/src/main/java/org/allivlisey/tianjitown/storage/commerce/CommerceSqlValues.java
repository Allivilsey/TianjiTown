package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ConflictException;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** JDBC value conversion and update-count checks. */
final class CommerceSqlValues {
    private CommerceSqlValues() {
    }

    static void requireUpdated(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new ConflictException(message);
        }
    }

    static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static UUID readUuid(ResultSet row, String column) throws SQLException {
        return uuid(row.getBytes(column));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        return Instant.ofEpochMilli(row.getLong(column));
    }

    static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}

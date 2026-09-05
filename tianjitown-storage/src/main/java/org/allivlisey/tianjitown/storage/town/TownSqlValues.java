package org.allivlisey.tianjitown.storage.town;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** JDBC value encoding shared by town persistence components. */
final class TownSqlValues {
    private TownSqlValues() {}

    static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    static void setNullableUuid(PreparedStatement statement, int index, UUID value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BLOB);
        } else {
            statement.setBytes(index, uuid(value));
        }
    }

    static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static UUID readUuid(ResultSet result, String column) throws SQLException {
        return uuid(result.getBytes(column));
    }

    static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

}

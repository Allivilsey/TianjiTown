package org.allivlisey.tianjitown.storage.economy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Pending external debits reserve funds until committed or explicitly cancelled. */
public final class AccountReservations {
    private AccountReservations() {}

    public static long available(Connection connection, UUID townId, long balance)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount_minor), 0) FROM economy_operations
                 WHERE town_id = ? AND amount_minor < 0
                   AND status IN ('PREPARED', 'EXTERNAL_APPLIED', 'COMPENSATION_REQUIRED')
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(townId));
            try (var row = statement.executeQuery()) {
                row.next();
                return Math.addExact(balance, row.getLong(1));
            }
        }
    }
}

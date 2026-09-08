package org.allivlisey.tianjitown.storage.governance;

import java.sql.Connection;
import java.sql.SQLException;

/** Invalidates requests in the same transaction as membership and mayor changes. */
public final class MayorTransferValidity {
    private MayorTransferValidity() {}

    public static void cancelInvalid(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE mayor_transfer_requests SET status = 'CANCELLED',
                    decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                 WHERE status = 'PENDING' AND (
                     NOT EXISTS (SELECT 1 FROM town_members m JOIN towns t ON t.town_id = m.town_id
                         WHERE m.town_id = mayor_transfer_requests.town_id
                           AND m.player_uuid = requested_by AND m.role = 'MAYOR' AND t.status = 'ACTIVE')
                     OR NOT EXISTS (SELECT 1 FROM town_members m
                         WHERE m.town_id = mayor_transfer_requests.town_id
                           AND m.player_uuid = candidate_uuid AND m.role <> 'MAYOR'))
                """)) {
            statement.executeUpdate();
        }
    }
}

package org.allivlisey.tianjitown.storage.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.LedgerEntry;

/** Ledger pagination and confirmed actor-name maintenance. */
final class EconomyLedgerStore {
    private final EconomyDatabase database;

    EconomyLedgerStore(EconomyDatabase database) {
        this.database = database;
    }

    List<LedgerEntry> ledger(UUID townId, int page, int pageSize) {
        database.requireWorkerThread();
        if (page < 0 || pageSize < 1 || pageSize > 45) {
            throw new IllegalArgumentException("账本分页参数无效");
        }
        return database.query(connection -> {
            List<LedgerEntry> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM ledger_entries
                     WHERE town_id = ?
                     ORDER BY created_at DESC, entry_id LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(townId));
                statement.setInt(2, pageSize);
                statement.setInt(3, Math.multiplyExact(page, pageSize));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readLedger(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    List<LedgerEntry> displayLedger(UUID townId, int page, int pageSize) {
        database.requireWorkerThread();
        if (page < 0 || pageSize < 1 || pageSize > 45) {
            throw new IllegalArgumentException("账本分页参数无效");
        }
        return database.query(connection -> {
            List<LedgerEntry> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT e.entry_id, e.town_id, e.entry_type,
                           e.amount_minor + COALESCE(s.amount_minor, 0) AS amount_minor,
                           COALESCE(s.balance_after_minor, e.balance_after_minor)
                               AS balance_after_minor,
                           e.actor_uuid, e.actor_name, e.business_key,
                           CASE WHEN s.entry_id IS NULL THEN e.note
                                ELSE e.note || '；含服务器等额补贴' END AS note,
                           COALESCE(s.created_at, e.created_at) AS created_at
                      FROM ledger_entries e
                      LEFT JOIN ledger_entries s
                        ON s.town_id = e.town_id
                       AND s.entry_type = 'SERVER_TAX_SUBSIDY'
                       AND s.business_key = e.business_key || ':subsidy'
                     WHERE e.town_id = ?
                       AND e.entry_type <> 'SERVER_TAX_SUBSIDY'
                     ORDER BY COALESCE(s.created_at, e.created_at) DESC, e.entry_id
                     LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(townId));
                statement.setInt(2, pageSize);
                statement.setInt(3, Math.multiplyExact(page, pageSize));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readLedger(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    List<UUID> unresolvedLedgerActorIds() {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT actor_uuid, actor_name FROM ledger_entries
                     WHERE actor_uuid IS NOT NULL
                    """); ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID actorId = EconomyPersistence.readUuid(result, "actor_uuid");
                    try {
                        if (UUID.fromString(result.getString("actor_name")).equals(actorId)) {
                            ids.add(actorId);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 已经是历史玩家名，不覆盖。
                    }
                }
            }
            return ids.stream().distinct().toList();
        });
    }

    int backfillLedgerActorName(UUID actorId, String confirmedName) {
        database.requireWorkerThread();
        Objects.requireNonNull(actorId, "actorId");
        if (confirmedName == null || confirmedName.isBlank()) {
            return 0;
        }
        return database.transaction(connection -> {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ledger_entries SET actor_name = ?
                     WHERE actor_uuid = ? AND actor_name = ?
                    """)) {
                statement.setString(1, confirmedName);
                statement.setBytes(2, EconomyPersistence.uuid(actorId));
                statement.setString(3, actorId.toString());
                updated = statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE quickshop_tax_records SET receiver_name = ?
                     WHERE receiver_uuid = ? AND receiver_name = ''
                    """)) {
                statement.setString(1, confirmedName);
                statement.setBytes(2, EconomyPersistence.uuid(actorId));
                updated += statement.executeUpdate();
            }
            return updated;
        });
    }

    private static LedgerEntry readLedger(ResultSet row) throws SQLException {
        byte[] actor = row.getBytes("actor_uuid");
        return new LedgerEntry(EconomyPersistence.readUuid(row, "entry_id"), EconomyPersistence.readUuid(row, "town_id"),
                row.getString("entry_type"), row.getLong("amount_minor"),
                row.getLong("balance_after_minor"), actor == null ? null : EconomyPersistence.uuid(actor),
                row.getString("actor_name"), row.getString("business_key"),
                row.getString("note"), EconomyPersistence.instant(row, "created_at"));
    }
}

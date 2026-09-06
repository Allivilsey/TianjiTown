package org.allivlisey.tianjitown.storage.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.LedgerEntry;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.DisplayLedgerEntry;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.TaxIncomeSummary;

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

    List<DisplayLedgerEntry> displayLedger(UUID townId, int page, int pageSize) {
        database.requireWorkerThread();
        if (page < 0 || pageSize < 1 || pageSize > 45) {
            throw new IllegalArgumentException("账本分页参数无效");
        }
        return database.query(connection -> {
            List<DisplayLedgerEntry> result = new ArrayList<>();
            // Shanghai 04:00 is UTC 20:00 on the preceding date. Group by tax posting time;
            // its linked subsidy belongs to the same period even if its timestamp crosses 04:00.
            // Aggregate before pagination, leaving the immutable financial ledger untouched.
            try (PreparedStatement statement = connection.prepareStatement("""
                    WITH town_ledger AS (
                        SELECT * FROM ledger_entries WHERE town_id = ?
                    ), taxes AS (
                        SELECT CASE WHEN e.entry_type = 'JOBS_TAX' THEN 'JOBS_INCOME'
                                    ELSE 'SHOP_INCOME' END AS entry_type,
                               unixepoch(date(e.created_at / 1000.0, 'unixepoch', '+4 hours'),
                                         '-4 hours') * 1000 AS period_start,
                               e.amount_minor AS tax_minor,
                               COALESCE(s.amount_minor, 0) AS subsidy_minor
                          FROM town_ledger e
                          LEFT JOIN ledger_entries s
                            ON s.town_id = e.town_id
                           AND s.entry_type = 'SERVER_TAX_SUBSIDY'
                           AND s.business_key = e.business_key || ':subsidy'
                         WHERE e.entry_type IN ('JOBS_TAX', 'QUICKSHOP_TAX', 'GLOBALMARKETPLUS_TAX')
                    ), displayed AS (
                        SELECT entry_type, SUM(tax_minor) + SUM(subsidy_minor) AS amount_minor,
                               NULL AS balance_after_minor, NULL AS actor_uuid,
                               NULL AS actor_name, NULL AS note,
                               period_start + 86400000 AS created_at, period_start,
                               SUM(tax_minor) AS tax_minor, SUM(subsidy_minor) AS subsidy_minor,
                               COUNT(*) AS transaction_count, entry_type AS sort_key
                          FROM taxes GROUP BY entry_type, period_start
                        UNION ALL
                        SELECT e.entry_type, e.amount_minor, e.balance_after_minor,
                               e.actor_uuid, e.actor_name, e.note, e.created_at,
                               NULL, NULL, NULL, NULL, hex(e.entry_id)
                          FROM town_ledger e
                         WHERE e.entry_type NOT IN ('JOBS_TAX', 'QUICKSHOP_TAX', 'GLOBALMARKETPLUS_TAX')
                           AND (e.entry_type <> 'SERVER_TAX_SUBSIDY' OR NOT EXISTS (
                               SELECT 1 FROM town_ledger t
                                WHERE t.entry_type IN ('JOBS_TAX', 'QUICKSHOP_TAX', 'GLOBALMARKETPLUS_TAX')
                                  AND e.business_key = t.business_key || ':subsidy'
                           ))
                    )
                    SELECT * FROM displayed ORDER BY created_at DESC, sort_key
                     LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(townId));
                statement.setInt(2, pageSize);
                statement.setInt(3, Math.multiplyExact(page, pageSize));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        long periodStart = rows.getLong("period_start");
                        TaxIncomeSummary summary = rows.wasNull() ? null : new TaxIncomeSummary(
                                Instant.ofEpochMilli(periodStart), Instant.ofEpochMilli(periodStart + 86400000L),
                                rows.getLong("tax_minor"), rows.getLong("subsidy_minor"),
                                rows.getLong("transaction_count"));
                        byte[] actor = rows.getBytes("actor_uuid");
                        result.add(new DisplayLedgerEntry(rows.getString("entry_type"),
                                rows.getLong("amount_minor"),
                                summary == null ? rows.getLong("balance_after_minor") : null,
                                actor == null ? null : EconomyPersistence.uuid(actor),
                                rows.getString("actor_name"), rows.getString("note"),
                                EconomyPersistence.instant(rows, "created_at"), summary));
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

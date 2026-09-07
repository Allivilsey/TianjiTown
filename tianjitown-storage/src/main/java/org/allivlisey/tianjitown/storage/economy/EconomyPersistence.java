package org.allivlisey.tianjitown.storage.economy;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ConflictException;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.LedgerMutation;

/** Account and ledger primitives executed within the caller's transaction. */
final class EconomyPersistence {
    private EconomyPersistence() {}

    static LedgerMutation postLedger(Connection connection, UUID townId, String type,
                                      long amountMinor, UUID actorId, String actorName,
                                      String businessKey, String note, boolean bypassLock)
            throws SQLException {
        AccountState account = requireAccount(connection, townId);
        if (amountMinor < 0 && !bypassLock) {
            requireUnlocked(account);
        }
        long after = Math.addExact(account.balanceMinor(), amountMinor);
        if (after < 0 || (amountMinor < 0 && Math.addExact(
                AccountReservations.available(connection, townId, account.balanceMinor()),
                amountMinor) < 0)) {
            throw new ConflictException("小镇余额不足");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE town_accounts SET balance_minor = ?, version = version + 1
                 WHERE town_id = ? AND version = ?
                """)) {
            statement.setLong(1, after);
            statement.setBytes(2, uuid(townId));
            statement.setLong(3, account.version());
            requireUpdated(statement, "小镇账户已被其他操作修改，请重试");
        }
        UUID entryId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries
                    (entry_id, town_id, entry_type, amount_minor, balance_after_minor,
                     actor_uuid, actor_name, business_key, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setBytes(1, uuid(entryId));
            statement.setBytes(2, uuid(townId));
            statement.setString(3, type);
            statement.setLong(4, amountMinor);
            statement.setLong(5, after);
            statement.setBytes(6, actorId == null ? null : uuid(actorId));
            statement.setString(7, actorName == null ? "SYSTEM" : actorName);
            statement.setString(8, businessKey);
            statement.setString(9, safe(note));
            statement.executeUpdate();
        }
        return new LedgerMutation(entryId, townId, amountMinor, after, businessKey);
    }

    static Optional<LedgerMutation> findLedgerByBusinessKey(Connection connection,
                                                                    String businessKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT entry_id, town_id, amount_minor, balance_after_minor, business_key
                  FROM ledger_entries WHERE business_key = ?
                """)) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(new LedgerMutation(readUuid(row, "entry_id"),
                        readUuid(row, "town_id"), row.getLong("amount_minor"),
                        row.getLong("balance_after_minor"), row.getString("business_key")))
                        : Optional.empty();
            }
        }
    }

    static AccountState requireAccount(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement ensure = connection.prepareStatement("""
                INSERT INTO town_accounts (town_id) VALUES (?) ON CONFLICT (town_id) DO NOTHING
                """)) {
            ensure.setBytes(1, uuid(townId));
            ensure.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM town_accounts WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("小镇账户不存在");
                }
                return new AccountState(townId, row.getLong("balance_minor"),
                        row.getBoolean("locked"), row.getString("lock_reason"),
                        row.getLong("version"));
            }
        }
    }

    static void audit(Connection connection, UUID actorId, String actorName, String action,
                              UUID townId, String reason, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_logs
                    (actor_uuid, actor_name, action, target_type, target_id, reason, detail)
                VALUES (?, ?, ?, 'TOWN', ?, ?, ?)
                """)) {
            statement.setBytes(1, actorId == null ? null : uuid(actorId));
            statement.setString(2, actorName == null ? "SYSTEM" : actorName);
            statement.setString(3, action);
            statement.setString(4, townId.toString());
            statement.setString(5, reason);
            statement.setString(6, detail);
            statement.executeUpdate();
        }
    }

    static void requireUnlocked(AccountState account) {
        if (account.locked()) {
            throw new ConflictException("小镇资金已锁定: " + account.lockReason());
        }
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

    record AccountState(UUID townId, long balanceMinor, boolean locked,
                                String lockReason, long version) {
    }
}

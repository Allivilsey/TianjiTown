package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ActiveBuff;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ConflictException;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.PlayerContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.instant;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.requireUpdated;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.safe;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.uuid;

/** Shared connection-level Buff queries, account posting and audit writes. */
final class CommercePersistence {
    private CommercePersistence() {
    }

    static List<ActiveBuff> listActiveBuffs(Connection connection, UUID townId,
                                            Instant now) throws SQLException {
        List<ActiveBuff> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM active_buffs
                 WHERE town_id = ? AND status = 'ACTIVE' AND expires_at > ?
                   AND starts_at <= ?
                 ORDER BY buff_key
                 """)) {
            statement.setBytes(1, uuid(townId));
            statement.setLong(2, now.toEpochMilli());
            statement.setLong(3, now.toEpochMilli());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readBuff(rows));
                }
            }
        }
        return List.copyOf(result);
    }

    static void expireTownBuff(Connection connection, UUID townId, String buffKey,
                               Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE active_buffs SET status = 'EXPIRED'
                 WHERE town_id = ? AND buff_key = ? AND status = 'ACTIVE' AND expires_at <= ?
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setString(2, buffKey);
            statement.setLong(3, now.toEpochMilli());
            statement.executeUpdate();
        }
    }

    static Optional<ActiveBuff> findActiveBuff(Connection connection, UUID townId,
                                               String buffKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM active_buffs
                 WHERE town_id = ? AND buff_key = ? AND status = 'ACTIVE'
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setString(2, buffKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readBuff(row)) : Optional.empty();
            }
        }
    }

    static Optional<ActiveBuff> findBuffByBusinessKey(Connection connection,
                                                      String businessKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM active_buffs WHERE business_key = ?")) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readBuff(row)) : Optional.empty();
            }
        }
    }

    static ActiveBuff requireBuff(Connection connection, UUID buffId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM active_buffs WHERE buff_id = ?")) {
            statement.setBytes(1, uuid(buffId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("Buff 记录不存在");
                }
                return readBuff(row);
            }
        }
    }

    static PlayerContext requirePlayer(Connection connection, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.town_id, t.name, m.role FROM town_members m
                JOIN towns t ON t.town_id = m.town_id
                WHERE m.player_uuid = ? AND t.status = 'ACTIVE'
                """)) {
            statement.setBytes(1, uuid(playerId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("玩家不属于任何正常运行的小镇");
                }
                return new PlayerContext(readUuid(row, "town_id"), row.getString("name"),
                        MemberRole.valueOf(row.getString("role")));
            }
        }
    }

    static PlayerContext requireTownContext(Connection connection, UUID townId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name FROM towns WHERE town_id = ? AND status = 'ACTIVE'
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("小镇不存在或已归档");
                }
                return new PlayerContext(townId, row.getString("name"), MemberRole.MAYOR);
            }
        }
    }

    static Account requireAccount(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM town_accounts WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("小镇公共账户不存在");
                }
                return new Account(townId, row.getLong("balance_minor"), row.getBoolean("locked"),
                        row.getString("lock_reason"), row.getLong("version"));
            }
        }
    }

    static long postLedger(Connection connection, UUID townId, String type,
                           long amountMinor, UUID actorId, String actorName,
                           String businessKey, String note, boolean bypassLock)
            throws SQLException {
        Account account = requireAccount(connection, townId);
        if (amountMinor < 0 && account.locked() && !bypassLock) {
            throw new ConflictException("小镇资金已锁定: " + account.lockReason());
        }
        long after = Math.addExact(account.balanceMinor(), amountMinor);
        if (after < 0) {
            throw new ConflictException("小镇公共余额不足");
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
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries
                    (entry_id, town_id, entry_type, amount_minor, balance_after_minor,
                     actor_uuid, actor_name, business_key, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setBytes(1, uuid(UUID.randomUUID()));
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
        return after;
    }

    static void audit(Connection connection, UUID actorId, String actorName,
                      String action, String targetType, String targetId,
                      String reason, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_logs
                    (actor_uuid, actor_name, action, target_type, target_id, reason, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setBytes(1, actorId == null ? null : uuid(actorId));
            statement.setString(2, actorName == null ? "SYSTEM" : actorName);
            statement.setString(3, action);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            statement.setString(6, safe(reason));
            statement.setString(7, safe(detail));
            statement.executeUpdate();
        }
    }

    static ActiveBuff readBuff(ResultSet row) throws SQLException {
        byte[] purchaser = row.getBytes("purchased_by");
        String worlds = row.getString("allowed_worlds");
        Set<String> allowed = worlds == null || worlds.isBlank() ? Set.of()
                : java.util.Arrays.stream(worlds.split("\\n"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ActiveBuff(readUuid(row, "buff_id"), readUuid(row, "town_id"),
                row.getString("buff_key"), BuffDefinition.EffectKind.valueOf(
                row.getString("effect_kind")), row.getString("effect_key"),
                row.getString("effect_operation"), row.getInt("level"),
                row.getInt("stack_count"), row.getDouble("amount_per_level"), allowed,
                row.getLong("price_minor"), purchaser == null ? null : uuid(purchaser),
                row.getString("purchased_by_name"), row.getString("business_key"),
                instant(row, "starts_at"), instant(row, "expires_at"), row.getString("status"),
                row.getString("last_error"));
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("原因必须为 1~500 个字符");
        }
    }

    record Account(UUID townId, long balanceMinor, boolean locked, String lockReason,
                   long version) {
    }
}

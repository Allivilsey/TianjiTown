package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ActiveBuff;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.BuffPurchase;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ConflictException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.audit;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.listActiveBuffs;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.postLedger;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.readBuff;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.requireBuff;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.requireReason;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.requireUpdated;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.safe;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.uuid;

/** Active Buff lookup, expiration and compensating refunds. */
final class BuffLifecycleStore {
    private final CommerceDatabase database;

    BuffLifecycleStore(CommerceDatabase database) {
        this.database = database;
    }

    List<ActiveBuff> activeBuffsForPlayer(UUID playerId, Instant now) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<ActiveBuff> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT b.* FROM active_buffs b
                    JOIN town_members m ON m.town_id = b.town_id
                    JOIN towns t ON t.town_id = b.town_id
                    WHERE m.player_uuid = ? AND t.status = 'ACTIVE'
                      AND b.status = 'ACTIVE' AND b.starts_at <= ? AND b.expires_at > ?
                      ORDER BY b.buff_key
                    """)) {
                statement.setBytes(1, uuid(playerId));
                statement.setLong(2, now.toEpochMilli());
                statement.setLong(3, now.toEpochMilli());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readBuff(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    List<ActiveBuff> activeBuffsForTown(UUID townId, Instant now) {
        database.requireWorkerThread();
        return database.query(connection -> listActiveBuffs(connection, townId, now));
    }

    Set<UUID> expireBuffs(Instant now) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            Set<UUID> affected = new LinkedHashSet<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT DISTINCT town_id FROM active_buffs
                     WHERE status = 'ACTIVE' AND expires_at <= ?
                    """)) {
                select.setLong(1, now.toEpochMilli());
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        affected.add(readUuid(rows, "town_id"));
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE active_buffs SET status = 'EXPIRED'
                     WHERE status = 'ACTIVE' AND expires_at <= ?
                    """)) {
                update.setLong(1, now.toEpochMilli());
                update.executeUpdate();
            }
            return Set.copyOf(affected);
        });
    }

    int expireBuffsForPlayer(UUID playerId, Instant now) {
        database.requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(now, "now");
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE active_buffs SET status = 'EXPIRED'
                     WHERE status = 'ACTIVE' AND expires_at <= ?
                       AND town_id IN (
                           SELECT town_id FROM town_members WHERE player_uuid = ?
                       )
                    """)) {
                statement.setLong(1, now.toEpochMilli());
                statement.setBytes(2, uuid(playerId));
                return statement.executeUpdate();
            }
        });
    }

    BuffPurchase refundActiveBuff(UUID buffId, UUID actorId, String actorName,
                                  String reason) {
        database.requireWorkerThread();
        requireReason(reason);
        return database.transaction(connection -> {
            ActiveBuff buff = requireBuff(connection, buffId);
            if (buff.status().equals("CANCELLED")) {
                throw new ConflictException("Buff 已取消并退款，不能重复操作");
            }
            if (!buff.status().equals("ACTIVE")) {
                throw new ConflictException("只有当前生效的 Buff 可以退款取消");
            }
            UUID replacedId = null;
            long replacementRefund = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT replaced_buff_id, replacement_refund_minor FROM active_buffs WHERE buff_id = ?")) {
                statement.setBytes(1, uuid(buffId));
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new ConflictException("Buff 不存在");
                    byte[] replaced = row.getBytes("replaced_buff_id");
                    replacedId = replaced == null ? null : CommerceSqlValues.uuid(replaced);
                    replacementRefund = row.getLong("replacement_refund_minor");
                }
            }
            long balance = CommercePersistence.requireAccount(connection, buff.townId()).balanceMinor();
            if (buff.priceMinor() > 0) {
                balance = postLedger(connection, buff.townId(), "BUFF_REFUND", buff.priceMinor(),
                        actorId, actorName, "buff-refund:" + buffId, "撤销 Buff 新扣款；" + reason, true);
            }
            if (replacementRefund > 0) {
                balance = postLedger(connection, buff.townId(), "BUFF_REFUND", -replacementRefund,
                        actorId, actorName, "buff-refund-reversal:" + buffId,
                        "撤销本次替换的剩余时间退款；" + reason, true);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE active_buffs SET status = 'CANCELLED', last_error = ?
                     WHERE buff_id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setString(1, safe(reason));
                statement.setBytes(2, uuid(buffId));
                requireUpdated(statement, "Buff 状态已变化");
            }
            if (replacedId != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE active_buffs SET status = CASE WHEN expires_at > ? THEN 'ACTIVE' ELSE 'EXPIRED' END
                         WHERE buff_id = ? AND town_id = ? AND buff_key = ? AND status = 'SUPERSEDED'
                        """)) {
                    statement.setLong(1, Instant.now().toEpochMilli());
                    statement.setBytes(2, uuid(replacedId));
                    statement.setBytes(3, uuid(buff.townId()));
                    statement.setString(4, buff.buffKey());
                    requireUpdated(statement, "被替换的 Buff 已变化，不能补偿");
                }
            }
            audit(connection, actorId, actorName, "BUFF_REFUND", "BUFF", buffId.toString(),
                    reason, "退回公共资金 " + buff.priceMinor());
            return new BuffPurchase(requireBuff(connection, buffId), balance);
        });
    }
}

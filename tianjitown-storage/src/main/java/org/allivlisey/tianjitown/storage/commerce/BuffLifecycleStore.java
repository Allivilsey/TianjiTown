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
            String businessKey = "buff-refund:" + buff.buffId();
            long balance = postLedger(connection, buff.townId(), "BUFF_REFUND",
                    buff.priceMinor(), actorId, actorName, businessKey,
                    "Buff 退款: " + buff.buffKey() + "；" + reason, true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE active_buffs SET status = 'CANCELLED', last_error = ?
                     WHERE buff_id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setString(1, safe(reason));
                statement.setBytes(2, uuid(buffId));
                requireUpdated(statement, "Buff 状态已变化");
            }
            // 退款当前叠加层后恢复最近一层仍未到期的快照，避免较早购买的权益一并丢失。
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE active_buffs SET status = 'ACTIVE'
                     WHERE buff_id = (
                         SELECT buff_id FROM active_buffs
                          WHERE town_id = ? AND buff_key = ? AND status = 'SUPERSEDED'
                            AND expires_at > ?
                          ORDER BY starts_at DESC, created_at DESC LIMIT 1
                     )
                    """)) {
                statement.setBytes(1, uuid(buff.townId()));
                statement.setString(2, buff.buffKey());
                statement.setLong(3, Instant.now().toEpochMilli());
                statement.executeUpdate();
            }
            audit(connection, actorId, actorName, "BUFF_REFUND", "BUFF", buffId.toString(),
                    reason, "退回公共资金 " + buff.priceMinor());
            return new BuffPurchase(requireBuff(connection, buffId), balance);
        });
    }
}

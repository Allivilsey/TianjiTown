package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffPricing;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ActiveBuff;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.BuffPurchase;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ConflictException;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.PlayerContext;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.SelectedBuffQuote;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.audit;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.expireTownBuff;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.findActiveBuff;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.findBuffByBusinessKey;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.postLedger;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.requireAccount;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.requireBuff;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.requirePlayer;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.requireReason;
import static org.allivlisey.tianjitown.storage.commerce.CommercePersistence.requireTownContext;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.requireUpdated;
import static org.allivlisey.tianjitown.storage.commerce.CommerceSqlValues.uuid;

/** Buff pricing, member and administrator purchases within one transaction. */
final class BuffPurchaseStore {
    private final CommerceDatabase database;

    BuffPurchaseStore(CommerceDatabase database) {
        this.database = database;
    }

    SelectedBuffQuote quoteBuff(UUID playerId, BuffDefinition definition, int weeks,
                                int level, int moneyScale, Instant now) {
        database.requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        return database.query(connection -> quoteSelectedBuff(connection,
                requirePlayer(connection, playerId), definition, weeks, level, moneyScale, now));
    }

    BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                              int weeks, int level, int moneyScale,
                              String businessKey, Instant now) {
        database.requireWorkerThread();
        return database.transaction(connection -> purchaseSelectedBuff(connection,
                requirePlayer(connection, playerId), playerId, actorName, definition,
                definition.displayName(), weeks, level, moneyScale, businessKey, now, "成员通过公共 Buff 商店购买"));
    }

    BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                              String buffLabel, int weeks, int level, int moneyScale,
                              String businessKey, Instant now) {
        database.requireWorkerThread();
        return database.transaction(connection -> purchaseSelectedBuff(connection,
                requirePlayer(connection, playerId), playerId, actorName, definition, buffLabel,
                weeks, level, moneyScale, businessKey, now, "成员通过公共 Buff 商店购买"));
    }

    BuffPurchase purchaseBuffForTown(UUID townId, UUID actorId, String actorName,
                                     BuffDefinition definition, int moneyScale,
                                     int weeks, int level, String businessKey,
                                     Instant now, String reason) {
        database.requireWorkerThread();
        requireReason(reason);
        return database.transaction(connection -> purchaseSelectedBuff(connection,
                requireTownContext(connection, townId), actorId, actorName, definition,
                definition.displayName(), weeks, level, moneyScale, businessKey, now, reason));
    }

    BuffPurchase purchaseBuffForTown(UUID townId, UUID actorId, String actorName,
                                     BuffDefinition definition, String buffLabel,
                                     int moneyScale, int weeks, int level,
                                     String businessKey, Instant now, String reason) {
        database.requireWorkerThread();
        requireReason(reason);
        return database.transaction(connection -> purchaseSelectedBuff(connection,
                requireTownContext(connection, townId), actorId, actorName, definition, buffLabel,
                weeks, level, moneyScale, businessKey, now, reason));
    }

    private BuffPurchase purchaseSelectedBuff(Connection connection, PlayerContext context,
                                              UUID actorId, String actorName,
                                              BuffDefinition definition, String buffLabel,
                                              int weeks, int level,
                                              int moneyScale, String businessKey, Instant now, String reason)
            throws SQLException {
        return replaceBuff(connection, context, actorId, actorName, definition, buffLabel,
                weeks, level, moneyScale, businessKey, now, reason, false, false, null);
    }

    BuffPurchase purchaseConfirmedBuff(UUID playerId, String actorName, BuffDefinition definition,
            String buffLabel, int weeks, int level, int moneyScale, String businessKey,
            Instant now, UUID expectedTown, UUID expectedBuff) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            PlayerContext context = requirePlayer(connection, playerId);
            if (!context.townId().equals(expectedTown)) throw new ConflictException("所属小镇已变化，请重新确认");
            return replaceBuff(connection, context, playerId, actorName, definition, buffLabel,
                    weeks, level, moneyScale, businessKey, now, "成员通过公共 Buff 商店购买",
                    false, true, expectedBuff);
        });
    }

    BuffPurchase setBuffForTown(UUID townId, UUID actorId, String actorName,
            BuffDefinition definition, int weeks, int level, String businessKey, Instant now) {
        database.requireWorkerThread();
        return database.transaction(connection -> replaceBuff(connection,
                requireTownContext(connection, townId), actorId, actorName, definition,
                definition.displayName(), weeks, level, 2, businessKey, now, "管理员调整",
                true, false, null));
    }

    private BuffPurchase replaceBuff(Connection connection, PlayerContext context,
            UUID actorId, String actorName, BuffDefinition definition, String buffLabel,
            int weeks, int level, int moneyScale, String businessKey, Instant now, String reason,
            boolean free, boolean checkExpected, UUID expectedBuff) throws SQLException {
        if (businessKey == null || businessKey.isBlank()) throw new IllegalArgumentException("缺少交易幂等键");
        Optional<ActiveBuff> existing = findBuffByBusinessKey(connection, businessKey);
        if (existing.isPresent()) {
            if (!existing.get().townId().equals(context.townId())
                    || !existing.get().buffKey().equals(definition.key())
                    || !Objects.equals(existing.get().purchasedBy(), actorId)) {
                throw new ConflictException("交易幂等键与请求不一致");
            }
            return new BuffPurchase(existing.get(),
                    requireAccount(connection, context.townId()).balanceMinor());
        }
        SelectedBuffQuote quote = quoteSelectedBuff(connection, context, definition, weeks, level,
                moneyScale, now);
        // Compare the identity before expiry is normalized; an expired quote remains safe to replace.
        ActiveBuff previous = findLatestCurrent(connection, context.townId(), definition.key());
        if (checkExpected && !Objects.equals(expectedBuff,
                previous == null ? null : previous.buffId())) {
            throw new ConflictException("Buff 已被其他操作替换，请重新确认");
        }
        long price = free ? 0 : quote.priceMinor();
        if (!free && price <= 0) throw new IllegalArgumentException("付费 Buff 价格必须大于 0");
        long refund = free ? 0 : quote.refundMinor();
        UUID buffId = UUID.randomUUID();
        if (quote.current() != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE active_buffs SET status = 'SUPERSEDED'
                     WHERE buff_id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setBytes(1, uuid(quote.current().buffId()));
                requireUpdated(statement, "Buff 状态已被其他购买修改，请刷新后重试");
            }
        }
        if (refund > 0) {
            postLedger(connection, context.townId(), "BUFF_REFUND", refund, actorId, actorName,
                    "buff-replacement-refund:" + quote.current().buffId() + ":" + buffId,
                    "替换 Buff 剩余时间退款", true);
        }
        long balance = free ? requireAccount(connection, context.townId()).balanceMinor()
                : postLedger(connection, context.townId(), "BUFF_PURCHASE",
                -price, actorId, actorName, businessKey,
                "购买 Buff " + buffLabel + " 强度 " + level + "，" + weeks
                        + " 周", false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO active_buffs
                    (buff_id, town_id, buff_key, effect_kind, effect_key, effect_operation,
                     level, amount_per_level, price_minor,
                     purchased_by, purchased_by_name, business_key, starts_at, expires_at, status,
                     replaced_buff_id, replacement_refund_minor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """)) {
            statement.setBytes(1, uuid(buffId));
            statement.setBytes(2, uuid(context.townId()));
            statement.setString(3, definition.key());
            statement.setString(4, definition.effectKind().name());
            statement.setString(5, definition.effectKey());
            statement.setString(6, definition.effectOperation());
            statement.setInt(7, level);
            statement.setDouble(8, definition.amountPerLevel());
            statement.setLong(9, price);
            statement.setBytes(10, actorId == null ? null : uuid(actorId));
            statement.setString(11, actorName == null ? "SYSTEM" : actorName);
            statement.setString(12, businessKey);
            statement.setLong(13, now.toEpochMilli());
            statement.setLong(14, quote.expiresAt().toEpochMilli());
            statement.setBytes(15, quote.current() == null ? null : uuid(quote.current().buffId()));
            statement.setLong(16, refund);
            statement.executeUpdate();
        }
        audit(connection, actorId, actorName, free ? "BUFF_SET" : "BUFF_PURCHASE", "TOWN",
                context.townId().toString(), reason,
                definition.key() + " level=" + level + " weeks=" + weeks
                        + " price=" + price + " refund=" + refund);
        return new BuffPurchase(requireBuff(connection, buffId), balance);
    }

    private ActiveBuff findLatestCurrent(Connection connection, UUID townId, String key) throws SQLException {
        return findActiveBuff(connection, townId, key).orElse(null);
    }

    private SelectedBuffQuote quoteSelectedBuff(Connection connection, PlayerContext context,
                                                BuffDefinition definition, int weeks, int level,
                                                int moneyScale, Instant now) throws SQLException {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(now, "now");
        if (!definition.allowsRole(context.role())) {
            throw new ConflictException("只有镇长或副镇长可以购买公共 Buff");
        }
        ActiveBuff current = findActiveBuff(connection, context.townId(), definition.key())
                .orElse(null);
        long price = BuffPricing.weeklyPrice(definition, weeks, level, moneyScale).minorUnits();
        return new SelectedBuffQuote(context, current, level, weeks, price,
                now.plus(Duration.ofDays(7L * weeks)), current == null ? 0
                        : BuffPricing.remainingRefund(current.priceMinor(), current.startsAt(),
                                current.expiresAt(), now));
    }

}

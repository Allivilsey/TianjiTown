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
        Optional<ActiveBuff> existing = findBuffByBusinessKey(connection, businessKey);
        if (existing.isPresent()) {
            return new BuffPurchase(existing.get(),
                    requireAccount(connection, context.townId()).balanceMinor());
        }
        SelectedBuffQuote quote = quoteSelectedBuff(connection, context, definition, weeks, level,
                moneyScale, now);
        if (quote.current() != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE active_buffs SET status = 'SUPERSEDED'
                     WHERE buff_id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setBytes(1, uuid(quote.current().buffId()));
                requireUpdated(statement, "Buff 状态已被其他购买修改，请刷新后重试");
            }
        }
        long balance = postLedger(connection, context.townId(), "BUFF_PURCHASE",
                -quote.priceMinor(), actorId, actorName, businessKey,
                "购买 Buff " + buffLabel + " 强度 " + level + "，" + weeks
                        + " 周", false);
        UUID buffId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO active_buffs
                    (buff_id, town_id, buff_key, effect_kind, effect_key, effect_operation,
                     level, amount_per_level, price_minor,
                     purchased_by, purchased_by_name, business_key, starts_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """)) {
            statement.setBytes(1, uuid(buffId));
            statement.setBytes(2, uuid(context.townId()));
            statement.setString(3, definition.key());
            statement.setString(4, definition.effectKind().name());
            statement.setString(5, definition.effectKey());
            statement.setString(6, definition.effectOperation());
            statement.setInt(7, level);
            statement.setDouble(8, definition.amountPerLevel());
            statement.setLong(9, quote.priceMinor());
            statement.setBytes(10, actorId == null ? null : uuid(actorId));
            statement.setString(11, actorName == null ? "SYSTEM" : actorName);
            statement.setString(12, businessKey);
            statement.setLong(13, now.toEpochMilli());
            statement.setLong(14, quote.expiresAt().toEpochMilli());
            statement.executeUpdate();
        }
        audit(connection, actorId, actorName, "BUFF_PURCHASE", "TOWN",
                context.townId().toString(), reason,
                definition.key() + " level=" + level + " weeks=" + weeks
                        + " price=" + quote.priceMinor());
        return new BuffPurchase(requireBuff(connection, buffId), balance);
    }

    private SelectedBuffQuote quoteSelectedBuff(Connection connection, PlayerContext context,
                                                BuffDefinition definition, int weeks, int level,
                                                int moneyScale, Instant now) throws SQLException {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(now, "now");
        if (!definition.allowsRole(context.role())) {
            throw new ConflictException("只有镇长或副镇长可以购买公共 Buff");
        }
        expireTownBuff(connection, context.townId(), definition.key(), now);
        ActiveBuff current = findActiveBuff(connection, context.townId(), definition.key())
                .orElse(null);
        long price = BuffPricing.weeklyPrice(definition, weeks, level, moneyScale).minorUnits();
        return new SelectedBuffQuote(context, current, level, weeks, price,
                now.plus(Duration.ofDays(7L * weeks)));
    }

}

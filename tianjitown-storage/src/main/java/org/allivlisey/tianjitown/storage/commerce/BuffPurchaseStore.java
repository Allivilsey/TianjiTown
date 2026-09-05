package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.core.consumption.BuffPricing;
import org.allivlisey.tianjitown.core.consumption.BuffStackingRule;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ActiveBuff;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.BuffPurchase;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.BuffQuote;
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

    BuffQuote quoteBuff(UUID playerId, BuffDefinition definition,
                        BuffDurationOption duration, int moneyScale, Instant now) {
        database.requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        return database.query(connection -> quoteBuff(connection, requirePlayer(connection, playerId),
                definition, duration, moneyScale, now, false));
    }

    BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                              BuffDurationOption duration, int moneyScale,
                              String businessKey, Instant now) {
        database.requireWorkerThread();
        return database.transaction(connection -> purchaseBuff(connection,
                requirePlayer(connection, playerId), playerId, actorName, definition,
                definition.displayName(), moneyScale, duration, businessKey, now, false,
                "成员通过公共 Buff 商店购买"));
    }

    BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                              String buffLabel, BuffDurationOption duration, int moneyScale,
                              String businessKey, Instant now) {
        database.requireWorkerThread();
        return database.transaction(connection -> purchaseBuff(connection,
                requirePlayer(connection, playerId), playerId, actorName, definition, buffLabel,
                moneyScale, duration, businessKey, now, false,
                "成员通过公共 Buff 商店购买"));
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
                definition.displayName(), weeks, level, moneyScale, businessKey, now));
    }

    BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                              String buffLabel, int weeks, int level, int moneyScale,
                              String businessKey, Instant now) {
        database.requireWorkerThread();
        return database.transaction(connection -> purchaseSelectedBuff(connection,
                requirePlayer(connection, playerId), playerId, actorName, definition, buffLabel,
                weeks, level, moneyScale, businessKey, now));
    }

    BuffPurchase purchaseBuffForTown(UUID townId, UUID actorId, String actorName,
                                     BuffDefinition definition, int moneyScale,
                                     BuffDurationOption duration, String businessKey,
                                     Instant now, String reason) {
        database.requireWorkerThread();
        requireReason(reason);
        return database.transaction(connection -> purchaseBuff(connection,
                requireTownContext(connection, townId), actorId, actorName, definition,
                definition.displayName(), moneyScale, duration, businessKey, now, true, reason));
    }

    BuffPurchase purchaseBuffForTown(UUID townId, UUID actorId, String actorName,
                                     BuffDefinition definition, String buffLabel,
                                     int moneyScale, BuffDurationOption duration,
                                     String businessKey, Instant now, String reason) {
        database.requireWorkerThread();
        requireReason(reason);
        return database.transaction(connection -> purchaseBuff(connection,
                requireTownContext(connection, townId), actorId, actorName, definition, buffLabel,
                moneyScale, duration, businessKey, now, true, reason));
    }

    private BuffPurchase purchaseBuff(Connection connection, PlayerContext context,
                                      UUID actorId, String actorName,
                                      BuffDefinition definition, String buffLabel, int moneyScale,
                                      BuffDurationOption duration, String businessKey,
                                      Instant now, boolean bypassRole,
                                      String reason) throws SQLException {
        Optional<ActiveBuff> existing = findBuffByBusinessKey(connection, businessKey);
        if (existing.isPresent()) {
            return new BuffPurchase(existing.get(),
                    requireAccount(connection, context.townId()).balanceMinor());
        }
        BuffQuote quote = quoteBuff(connection, context, definition, duration, moneyScale, now,
                bypassRole);
        ActiveBuff current = quote.current();
        if (current != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE active_buffs SET status = 'SUPERSEDED'
                     WHERE buff_id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setBytes(1, uuid(current.buffId()));
                requireUpdated(statement, "Buff 状态已被其他购买修改，请刷新后重试");
            }
        }
        long balance = postLedger(connection, context.townId(), "BUFF_PURCHASE",
                -quote.priceMinor(), actorId, actorName, businessKey,
                "购买 Buff " + buffLabel + " 等级 " + quote.nextLevel(), false);
        UUID buffId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO active_buffs
                    (buff_id, town_id, buff_key, effect_kind, effect_key, effect_operation,
                     level, stack_count, amount_per_level, allowed_worlds, price_minor,
                     purchased_by, purchased_by_name, business_key, starts_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """)) {
            statement.setBytes(1, uuid(buffId));
            statement.setBytes(2, uuid(context.townId()));
            statement.setString(3, definition.key());
            statement.setString(4, definition.effectKind().name());
            statement.setString(5, definition.effectKey());
            statement.setString(6, definition.effectOperation());
            statement.setInt(7, quote.nextLevel());
            statement.setInt(8, quote.nextStacks());
            statement.setDouble(9, definition.amountPerLevel());
            statement.setString(10, "");
            statement.setLong(11, quote.priceMinor());
            statement.setBytes(12, actorId == null ? null : uuid(actorId));
            statement.setString(13, actorName == null ? "SYSTEM" : actorName);
            statement.setString(14, businessKey);
            statement.setLong(15, now.toEpochMilli());
            statement.setLong(16, quote.expiresAt().toEpochMilli());
            statement.executeUpdate();
        }
        audit(connection, actorId, actorName, "BUFF_PURCHASE", "TOWN",
                context.townId().toString(), reason,
                definition.key() + " level=" + quote.nextLevel() + " price=" + quote.priceMinor());
        return new BuffPurchase(requireBuff(connection, buffId), balance);
    }

    private BuffPurchase purchaseSelectedBuff(Connection connection, PlayerContext context,
                                              UUID actorId, String actorName,
                                              BuffDefinition definition, String buffLabel,
                                              int weeks, int level,
                                              int moneyScale, String businessKey, Instant now)
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
                     level, stack_count, amount_per_level, allowed_worlds, price_minor,
                     purchased_by, purchased_by_name, business_key, starts_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """)) {
            statement.setBytes(1, uuid(buffId));
            statement.setBytes(2, uuid(context.townId()));
            statement.setString(3, definition.key());
            statement.setString(4, definition.effectKind().name());
            statement.setString(5, definition.effectKey());
            statement.setString(6, definition.effectOperation());
            statement.setInt(7, level);
            statement.setInt(8, level);
            statement.setDouble(9, definition.amountPerLevel());
            statement.setString(10, "");
            statement.setLong(11, quote.priceMinor());
            statement.setBytes(12, actorId == null ? null : uuid(actorId));
            statement.setString(13, actorName == null ? "SYSTEM" : actorName);
            statement.setString(14, businessKey);
            statement.setLong(15, now.toEpochMilli());
            statement.setLong(16, quote.expiresAt().toEpochMilli());
            statement.executeUpdate();
        }
        audit(connection, actorId, actorName, "BUFF_PURCHASE", "TOWN",
                context.townId().toString(), "成员通过公共 Buff 商店购买",
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

    private BuffQuote quoteBuff(Connection connection, PlayerContext context,
                                BuffDefinition definition, BuffDurationOption duration,
                                int moneyScale, Instant now, boolean bypassRole) throws SQLException {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(now, "now");
        if (!bypassRole && !definition.allowsRole(context.role())) {
            throw new ConflictException("你的成员角色没有购买该 Buff 的权限");
        }
        expireTownBuff(connection, context.townId(), definition.key(), now);
        ActiveBuff current = findActiveBuff(connection, context.townId(), definition.key())
                .orElse(null);
        int currentLevel = current == null ? 0 : current.level();
        int currentStacks = current == null ? 0 : current.stackCount();
        BuffPricing.NextStack next = BuffPricing.next(definition, currentLevel, currentStacks);
        long price = BuffPricing.price(definition, duration, next.level(), moneyScale).minorUnits();
        Instant base = definition.stackingRule() == BuffStackingRule.REFRESH || current == null
                ? now : current.expiresAt();
        Instant expiry = base.plus(duration.duration());
        return new BuffQuote(context, current, next.level(), next.stacks(), duration,
                price, expiry);
    }
}

package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.core.town.MemberRole;

import javax.sql.DataSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Public facade for Buff purchases, active effects and refunds. */
public final class CommerceRepository {
    private final BuffPurchaseStore purchases;
    private final BuffLifecycleStore lifecycle;

    public CommerceRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        CommerceDatabase database = new CommerceDatabase(dataSource, forbiddenThread);
        this.purchases = new BuffPurchaseStore(database);
        this.lifecycle = new BuffLifecycleStore(database);
    }

    public BuffQuote quoteBuff(UUID playerId, BuffDefinition definition,
                               BuffDurationOption duration, int moneyScale, Instant now) {
        return purchases.quoteBuff(playerId, definition, duration, moneyScale, now);
    }

    public BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                                     BuffDurationOption duration, int moneyScale,
                                     String businessKey, Instant now) {
        return purchases.purchaseBuff(playerId, actorName, definition, duration, moneyScale,
                businessKey, now);
    }

    public BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                                     String buffLabel, BuffDurationOption duration, int moneyScale,
                                     String businessKey, Instant now) {
        return purchases.purchaseBuff(playerId, actorName, definition, buffLabel, duration,
                moneyScale, businessKey, now);
    }

    public SelectedBuffQuote quoteBuff(UUID playerId, BuffDefinition definition, int weeks,
                                       int level, int moneyScale, Instant now) {
        return purchases.quoteBuff(playerId, definition, weeks, level, moneyScale, now);
    }

    public BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                                     int weeks, int level, int moneyScale,
                                     String businessKey, Instant now) {
        return purchases.purchaseBuff(playerId, actorName, definition, weeks, level, moneyScale,
                businessKey, now);
    }

    public BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                                     String buffLabel, int weeks, int level, int moneyScale,
                                     String businessKey, Instant now) {
        return purchases.purchaseBuff(playerId, actorName, definition, buffLabel, weeks, level,
                moneyScale, businessKey, now);
    }

    public BuffPurchase purchaseBuffForTown(UUID townId, UUID actorId, String actorName,
                                            BuffDefinition definition, int moneyScale,
                                            BuffDurationOption duration, String businessKey,
                                            Instant now, String reason) {
        return purchases.purchaseBuffForTown(townId, actorId, actorName, definition, moneyScale,
                duration, businessKey, now, reason);
    }

    public BuffPurchase purchaseBuffForTown(UUID townId, UUID actorId, String actorName,
                                            BuffDefinition definition, String buffLabel,
                                            int moneyScale, BuffDurationOption duration,
                                            String businessKey, Instant now, String reason) {
        return purchases.purchaseBuffForTown(townId, actorId, actorName, definition, buffLabel,
                moneyScale, duration, businessKey, now, reason);
    }

    public List<ActiveBuff> activeBuffsForPlayer(UUID playerId, Instant now) {
        return lifecycle.activeBuffsForPlayer(playerId, now);
    }

    public List<ActiveBuff> activeBuffsForTown(UUID townId, Instant now) {
        return lifecycle.activeBuffsForTown(townId, now);
    }

    public Set<UUID> expireBuffs(Instant now) {
        return lifecycle.expireBuffs(now);
    }

    public int expireBuffsForPlayer(UUID playerId, Instant now) {
        return lifecycle.expireBuffsForPlayer(playerId, now);
    }

    public BuffPurchase refundActiveBuff(UUID buffId, UUID actorId, String actorName,
                                         String reason) {
        return lifecycle.refundActiveBuff(buffId, actorId, actorName, reason);
    }

    public record PlayerContext(UUID townId, String townName, MemberRole role) {
    }

    public record BuffQuote(PlayerContext context, ActiveBuff current, int nextLevel,
                            int nextStacks, BuffDurationOption duration,
                            long priceMinor, Instant expiresAt) {
    }

    public record SelectedBuffQuote(PlayerContext context, ActiveBuff current, int level,
                                    int weeks, long priceMinor, Instant expiresAt) {
    }

    public record BuffPurchase(ActiveBuff buff, long balanceAfterMinor) {
    }

    public record ActiveBuff(UUID buffId, UUID townId, String buffKey,
                             BuffDefinition.EffectKind effectKind, String effectKey,
                             String effectOperation, int level, int stackCount,
                             double amountPerLevel, Set<String> allowedWorlds, long priceMinor,
                             UUID purchasedBy, String purchasedByName, String businessKey,
                             Instant startsAt, Instant expiresAt, String status, String lastError) {
        public boolean allowsWorld(String worldName) {
            return allowedWorlds.isEmpty() || allowedWorlds.stream()
                    .anyMatch(worldName::equalsIgnoreCase);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }

        public ConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

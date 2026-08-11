package cn.tianji.town.storage.phase4;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffPricing;
import cn.tianji.town.core.consumption.BuffStackingRule;
import cn.tianji.town.core.consumption.ResourceDefinition;
import cn.tianji.town.core.economy.MoneyAmount;
import cn.tianji.town.core.town.MemberRole;

import javax.sql.DataSource;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class PhaseFourRepository {
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public PhaseFourRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    public BuffQuote quoteBuff(UUID playerId, BuffDefinition definition, int moneyScale,
                               Instant now) {
        requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        return query(connection -> quoteBuff(connection, requirePlayer(connection, playerId),
                definition, moneyScale, now, false));
    }

    public BuffPurchase purchaseBuff(UUID playerId, String actorName, BuffDefinition definition,
                                     int moneyScale, String businessKey, Instant now) {
        requireWorkerThread();
        return transaction(connection -> purchaseBuff(connection,
                requirePlayer(connection, playerId), playerId, actorName, definition, moneyScale,
                businessKey, now, false, "成员通过公共 Buff 商店购买"));
    }

    public BuffPurchase purchaseBuffForTown(UUID townId, UUID actorId, String actorName,
                                            BuffDefinition definition, int moneyScale,
                                            String businessKey, Instant now, String reason) {
        requireWorkerThread();
        requireReason(reason);
        return transaction(connection -> purchaseBuff(connection,
                requireTownContext(connection, townId), actorId, actorName, definition, moneyScale,
                businessKey, now, true, reason));
    }

    public List<ActiveBuff> activeBuffsForPlayer(UUID playerId, Instant now) {
        requireWorkerThread();
        return query(connection -> {
            List<ActiveBuff> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT b.* FROM active_buffs b
                    JOIN town_members m ON m.town_id = b.town_id
                    JOIN towns t ON t.town_id = b.town_id
                    WHERE m.player_uuid = ? AND t.status = 'ACTIVE'
                      AND b.status = 'ACTIVE' AND b.expires_at > ?
                    ORDER BY b.buff_key
                    """)) {
                statement.setBytes(1, uuid(playerId));
                statement.setLong(2, now.toEpochMilli());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readBuff(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    public List<ActiveBuff> activeBuffsForTown(UUID townId, Instant now) {
        requireWorkerThread();
        return query(connection -> listActiveBuffs(connection, townId, now));
    }

    public Set<UUID> expireBuffs(Instant now) {
        requireWorkerThread();
        return transaction(connection -> {
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

    public BuffPurchase refundActiveBuff(UUID buffId, UUID actorId, String actorName,
                                         String reason) {
        requireWorkerThread();
        requireReason(reason);
        return transaction(connection -> {
            ActiveBuff buff = requireBuff(connection, buffId);
            Account account = requireAccount(connection, buff.townId());
            if (buff.status().equals("CANCELLED")) {
                return new BuffPurchase(buff, account.balanceMinor());
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

    public ResourceQuote quoteResource(UUID playerId, ResourceDefinition definition, int quantity,
                                       int moneyScale, Instant dayStart, Instant dayEnd) {
        requireWorkerThread();
        return query(connection -> quoteResource(connection, requirePlayer(connection, playerId),
                definition, quantity, moneyScale, dayStart, dayEnd, false));
    }

    public ResourceOrder createOrder(UUID playerId, String playerName,
                                     ResourceDefinition definition, int quantity, int moneyScale,
                                     Instant dayStart, Instant dayEnd, String businessKey) {
        requireWorkerThread();
        return transaction(connection -> createOrder(connection,
                requirePlayer(connection, playerId), playerId, playerName, definition, quantity,
                moneyScale, dayStart, dayEnd, businessKey, false,
                "成员通过资源商店采购"));
    }

    public ResourceOrder createOrderForTown(UUID townId, UUID buyerId, String buyerName,
                                            UUID actorId, String actorName,
                                            ResourceDefinition definition, int quantity,
                                            int moneyScale, Instant dayStart, Instant dayEnd,
                                            String businessKey, String reason) {
        requireWorkerThread();
        requireReason(reason);
        return transaction(connection -> {
            PlayerContext buyer = requirePlayer(connection, buyerId);
            if (!buyer.townId().equals(townId)) {
                throw new ConflictException("目标玩家不是该小镇成员");
            }
            return createOrder(connection, buyer, buyerId, buyerName, definition, quantity,
                    moneyScale, dayStart, dayEnd, businessKey, true,
                    actorName + " 代办: " + reason, actorId, actorName);
        });
    }

    public List<ResourceOrder> ordersForPlayer(UUID playerId, int page, int pageSize) {
        requireWorkerThread();
        if (page < 0 || pageSize < 1 || pageSize > 45) {
            throw new IllegalArgumentException("订单分页参数无效");
        }
        return query(connection -> {
            List<ResourceOrder> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM resource_orders WHERE buyer_uuid = ?
                     ORDER BY created_at DESC, order_id LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, uuid(playerId));
                statement.setInt(2, pageSize);
                statement.setInt(3, Math.multiplyExact(page, pageSize));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readOrder(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    public List<ResourceOrder> openOrders(int limit) {
        requireWorkerThread();
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("订单查询上限无效");
        }
        return query(connection -> {
            List<ResourceOrder> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM resource_orders
                     WHERE status IN ('PENDING', 'CLAIMING', 'REFUND_REQUIRED')
                     ORDER BY created_at, order_id LIMIT ?
                    """)) {
                statement.setInt(1, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readOrder(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    public int pendingOrderCount(UUID playerId) {
        requireWorkerThread();
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) AS total FROM resource_orders
                     WHERE buyer_uuid = ? AND status IN ('PENDING', 'CLAIMING')
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    return row.getInt("total");
                }
            }
        });
    }

    public ResourceOrder reserveClaim(UUID orderId, UUID playerId, Instant now) {
        requireWorkerThread();
        return transaction(connection -> {
            ResourceOrder order = requireOrder(connection, orderId);
            requireOrderOwner(order, playerId);
            if (order.status().equals("CLAIMED") || order.status().equals("CLAIMING")) {
                return order;
            }
            if (!order.status().equals("PENDING")) {
                throw new ConflictException("订单当前不可领取: " + order.status());
            }
            UUID token = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_orders
                       SET status = 'CLAIMING', claim_token = ?, claim_started_at = ?, last_error = NULL
                     WHERE order_id = ? AND status = 'PENDING'
                    """)) {
                statement.setBytes(1, uuid(token));
                statement.setLong(2, now.toEpochMilli());
                statement.setBytes(3, uuid(orderId));
                requireUpdated(statement, "订单已被其他领取请求占用");
            }
            return requireOrder(connection, orderId);
        });
    }

    public ResourceOrder completeClaim(UUID orderId, UUID playerId, UUID claimToken,
                                       Instant now) {
        requireWorkerThread();
        return transaction(connection -> {
            ResourceOrder order = requireOrder(connection, orderId);
            requireOrderOwner(order, playerId);
            if (order.status().equals("CLAIMED")) {
                return order;
            }
            if (!order.status().equals("CLAIMING") || !Objects.equals(order.claimToken(), claimToken)) {
                throw new ConflictException("领取标记与订单状态不一致");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_orders SET status = 'CLAIMED', claimed_at = ?, last_error = NULL
                     WHERE order_id = ? AND status = 'CLAIMING' AND claim_token = ?
                    """)) {
                statement.setLong(1, now.toEpochMilli());
                statement.setBytes(2, uuid(orderId));
                statement.setBytes(3, uuid(claimToken));
                requireUpdated(statement, "订单领取状态已变化");
            }
            audit(connection, playerId, order.buyerName(), "RESOURCE_CLAIM", "ORDER",
                    orderId.toString(), "玩家领取", order.resourceName() + " x" + order.quantity());
            return requireOrder(connection, orderId);
        });
    }

    public ResourceOrder releaseClaim(UUID orderId, UUID playerId, UUID claimToken, String error) {
        requireWorkerThread();
        return transaction(connection -> {
            ResourceOrder order = requireOrder(connection, orderId);
            requireOrderOwner(order, playerId);
            if (order.status().equals("PENDING")) {
                return order;
            }
            if (!order.status().equals("CLAIMING") || !Objects.equals(order.claimToken(), claimToken)) {
                throw new ConflictException("订单领取状态已变化");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_orders
                       SET status = 'PENDING', claim_token = NULL, claim_started_at = NULL,
                           last_error = ?
                     WHERE order_id = ? AND status = 'CLAIMING' AND claim_token = ?
                    """)) {
                statement.setString(1, safe(error));
                statement.setBytes(2, uuid(orderId));
                statement.setBytes(3, uuid(claimToken));
                requireUpdated(statement, "订单领取状态已变化");
            }
            return requireOrder(connection, orderId);
        });
    }

    public ResourceOrder requireOrderRefund(UUID orderId, String error) {
        requireWorkerThread();
        return transaction(connection -> {
            ResourceOrder order = requireOrder(connection, orderId);
            if (order.status().equals("REFUND_REQUIRED")) {
                return order;
            }
            if (!order.status().equals("PENDING")) {
                throw new ConflictException("只有未开始领取的订单能标记为待退款");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_orders SET status = 'REFUND_REQUIRED', last_error = ?
                     WHERE order_id = ? AND status = 'PENDING'
                    """)) {
                statement.setString(1, safe(error));
                statement.setBytes(2, uuid(orderId));
                requireUpdated(statement, "订单状态已变化");
            }
            return requireOrder(connection, orderId);
        });
    }

    public ResourceOrder refundOrder(UUID orderId, UUID actorId, String actorName, String reason) {
        requireWorkerThread();
        requireReason(reason);
        return transaction(connection -> {
            ResourceOrder order = requireOrder(connection, orderId);
            if (order.status().equals("REFUNDED")) {
                return order;
            }
            if (!order.status().equals("PENDING") && !order.status().equals("REFUND_REQUIRED")) {
                throw new ConflictException("领取中或已领取订单不能退款");
            }
            postLedger(connection, order.townId(), "RESOURCE_REFUND", order.totalMinor(),
                    actorId, actorName, "resource-refund:" + order.orderId(),
                    "资源订单退款: " + order.resourceName() + " x" + order.quantity()
                            + "；" + reason, true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE resource_orders SET status = 'REFUNDED', last_error = ?
                     WHERE order_id = ? AND status IN ('PENDING', 'REFUND_REQUIRED')
                    """)) {
                statement.setString(1, safe(reason));
                statement.setBytes(2, uuid(orderId));
                requireUpdated(statement, "订单状态已变化");
            }
            audit(connection, actorId, actorName, "RESOURCE_REFUND", "ORDER",
                    orderId.toString(), reason, "退回公共资金 " + order.totalMinor());
            return requireOrder(connection, orderId);
        });
    }

    private BuffPurchase purchaseBuff(Connection connection, PlayerContext context,
                                      UUID actorId, String actorName,
                                      BuffDefinition definition, int moneyScale,
                                      String businessKey, Instant now, boolean bypassRole,
                                      String reason) throws SQLException {
        Optional<ActiveBuff> existing = findBuffByBusinessKey(connection, businessKey);
        if (existing.isPresent()) {
            return new BuffPurchase(existing.get(),
                    requireAccount(connection, context.townId()).balanceMinor());
        }
        BuffQuote quote = quoteBuff(connection, context, definition, moneyScale, now, bypassRole);
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
                "购买 Buff " + definition.displayName() + " 等级 " + quote.nextLevel(), false);
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
            statement.setString(10, String.join("\n", definition.allowedWorlds()));
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

    private BuffQuote quoteBuff(Connection connection, PlayerContext context,
                                BuffDefinition definition, int moneyScale, Instant now,
                                boolean bypassRole) throws SQLException {
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
        long price = BuffPricing.price(definition, currentStacks, moneyScale).minorUnits();
        Instant base = definition.stackingRule() == BuffStackingRule.REFRESH || current == null
                ? now : current.expiresAt();
        Instant expiry = base.plus(definition.duration());
        return new BuffQuote(context, current, next.level(), next.stacks(), price, expiry);
    }

    private ResourceOrder createOrder(Connection connection, PlayerContext context,
                                      UUID buyerId, String buyerName,
                                      ResourceDefinition definition, int quantity, int moneyScale,
                                      Instant dayStart, Instant dayEnd, String businessKey,
                                      boolean bypassRole, String reason) throws SQLException {
        return createOrder(connection, context, buyerId, buyerName, definition, quantity,
                moneyScale, dayStart, dayEnd, businessKey, bypassRole, reason, buyerId, buyerName);
    }

    private ResourceOrder createOrder(Connection connection, PlayerContext context,
                                      UUID buyerId, String buyerName,
                                      ResourceDefinition definition, int quantity, int moneyScale,
                                      Instant dayStart, Instant dayEnd, String businessKey,
                                      boolean bypassRole, String reason, UUID actorId,
                                      String actorName) throws SQLException {
        Optional<ResourceOrder> existing = findOrderByBusinessKey(connection, businessKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        ResourceQuote quote = quoteResource(connection, context, definition, quantity, moneyScale,
                dayStart, dayEnd, bypassRole);
        postLedger(connection, context.townId(), "RESOURCE_PURCHASE", -quote.totalMinor(),
                actorId, actorName, businessKey,
                "采购资源 " + definition.displayName() + " x" + quantity, false);
        UUID orderId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO resource_orders
                    (order_id, town_id, buyer_uuid, buyer_name, resource_key, resource_name,
                     material_key, quantity, total_minor, business_key, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """)) {
            statement.setBytes(1, uuid(orderId));
            statement.setBytes(2, uuid(context.townId()));
            statement.setBytes(3, uuid(buyerId));
            statement.setString(4, buyerName);
            statement.setString(5, definition.key());
            statement.setString(6, definition.displayName());
            statement.setString(7, definition.materialKey());
            statement.setInt(8, quantity);
            statement.setLong(9, quote.totalMinor());
            statement.setString(10, businessKey);
            statement.executeUpdate();
        }
        audit(connection, actorId, actorName, "RESOURCE_PURCHASE", "ORDER",
                orderId.toString(), reason,
                definition.key() + " quantity=" + quantity + " price=" + quote.totalMinor());
        return requireOrder(connection, orderId);
    }

    private ResourceQuote quoteResource(Connection connection, PlayerContext context,
                                         ResourceDefinition definition, int quantity,
                                         int moneyScale, Instant dayStart, Instant dayEnd,
                                         boolean bypassRole) throws SQLException {
        Objects.requireNonNull(definition, "definition");
        if (quantity < 1 || quantity > definition.maximumPerOrder()) {
            throw new IllegalArgumentException("采购数量超出每次上限");
        }
        if (!bypassRole && !definition.allowsRole(context.role())) {
            throw new ConflictException("你的成员角色没有采购该资源的权限");
        }
        int used = dailyUsage(connection, context.townId(), definition.key(), dayStart, dayEnd);
        if (Math.addExact(used, quantity) > definition.dailyLimit()) {
            throw new ConflictException("该资源今日采购额度不足，已用 " + used + "/"
                    + definition.dailyLimit());
        }
        long total = MoneyAmount.rounded(definition.unitPrice().multiply(
                        java.math.BigDecimal.valueOf(quantity)), moneyScale, RoundingMode.CEILING)
                .minorUnits();
        return new ResourceQuote(context, quantity, used, definition.dailyLimit(), total);
    }

    private static int dailyUsage(Connection connection, UUID townId, String resourceKey,
                                  Instant dayStart, Instant dayEnd) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(quantity), 0) AS total FROM resource_orders
                 WHERE town_id = ? AND resource_key = ? AND created_at >= ? AND created_at < ?
                   AND status <> 'REFUNDED'
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setString(2, resourceKey);
            statement.setLong(3, dayStart.toEpochMilli());
            statement.setLong(4, dayEnd.toEpochMilli());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt("total");
            }
        }
    }

    private static List<ActiveBuff> listActiveBuffs(Connection connection, UUID townId,
                                                     Instant now) throws SQLException {
        List<ActiveBuff> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM active_buffs
                 WHERE town_id = ? AND status = 'ACTIVE' AND expires_at > ?
                 ORDER BY buff_key
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setLong(2, now.toEpochMilli());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readBuff(rows));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void expireTownBuff(Connection connection, UUID townId, String buffKey,
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

    private static Optional<ActiveBuff> findActiveBuff(Connection connection, UUID townId,
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

    private static Optional<ActiveBuff> findBuffByBusinessKey(Connection connection,
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

    private static ActiveBuff requireBuff(Connection connection, UUID buffId) throws SQLException {
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

    private static Optional<ResourceOrder> findOrderByBusinessKey(Connection connection,
                                                                   String businessKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM resource_orders WHERE business_key = ?")) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readOrder(row)) : Optional.empty();
            }
        }
    }

    private static ResourceOrder requireOrder(Connection connection, UUID orderId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM resource_orders WHERE order_id = ?")) {
            statement.setBytes(1, uuid(orderId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("资源订单不存在");
                }
                return readOrder(row);
            }
        }
    }

    private static PlayerContext requirePlayer(Connection connection, UUID playerId)
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

    private static PlayerContext requireTownContext(Connection connection, UUID townId)
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

    private static Account requireAccount(Connection connection, UUID townId) throws SQLException {
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

    private static long postLedger(Connection connection, UUID townId, String type,
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

    private static void audit(Connection connection, UUID actorId, String actorName,
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

    private static ActiveBuff readBuff(ResultSet row) throws SQLException {
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

    private static ResourceOrder readOrder(ResultSet row) throws SQLException {
        byte[] token = row.getBytes("claim_token");
        return new ResourceOrder(readUuid(row, "order_id"), readUuid(row, "town_id"),
                readUuid(row, "buyer_uuid"), row.getString("buyer_name"),
                row.getString("resource_key"), row.getString("resource_name"),
                row.getString("material_key"), row.getInt("quantity"),
                row.getLong("total_minor"), row.getString("business_key"),
                row.getString("status"), token == null ? null : uuid(token),
                nullableInstant(row, "claim_started_at"),
                nullableInstant(row, "claimed_at"),
                row.getString("last_error"), instant(row, "created_at"));
    }

    private static void requireOrderOwner(ResourceOrder order, UUID playerId) {
        if (!order.buyerId().equals(playerId)) {
            throw new ConflictException("该订单不属于当前玩家");
        }
    }

    private void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("禁止在 Paper 主线程执行数据库 I/O");
        }
    }

    private <T> T query(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private static RuntimeException translate(SQLException exception) {
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 19) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("SQLite 操作失败: " + exception.getMessage(),
                exception);
    }

    private static void requireUpdated(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new ConflictException(message);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("原因必须为 1~500 个字符");
        }
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID readUuid(ResultSet row, String column) throws SQLException {
        return uuid(row.getBytes(column));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return Instant.ofEpochMilli(row.getLong(column));
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record Account(UUID townId, long balanceMinor, boolean locked, String lockReason,
                           long version) {
    }

    public record PlayerContext(UUID townId, String townName, MemberRole role) {
    }

    public record BuffQuote(PlayerContext context, ActiveBuff current, int nextLevel,
                            int nextStacks, long priceMinor, Instant expiresAt) {
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

    public record ResourceQuote(PlayerContext context, int quantity, int usedToday,
                                int dailyLimit, long totalMinor) {
    }

    public record ResourceOrder(UUID orderId, UUID townId, UUID buyerId, String buyerName,
                                String resourceKey, String resourceName, String materialKey,
                                int quantity, long totalMinor, String businessKey, String status,
                                UUID claimToken, Instant claimStartedAt, Instant claimedAt,
                                String lastError, Instant createdAt) {
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

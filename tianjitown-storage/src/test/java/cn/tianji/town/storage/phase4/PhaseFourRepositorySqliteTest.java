package cn.tianji.town.storage.phase4;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.core.consumption.BuffStackingRule;
import cn.tianji.town.core.consumption.ResourceDefinition;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseFourRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsBuffPurchasesAndResourceClaimsIdempotent() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("phase4.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID mayorId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, memberId);
            PhaseFourRepository repository = new PhaseFourRepository(gate.dataSource(),
                    () -> false);
            Instant now = Instant.now();
            BuffDefinition buff = new BuffDefinition("speed", "公共迅捷",
                    BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                    new BigDecimal("100.00"), 2, BuffStackingRule.LEVEL_UP, 1,
                    Set.of(MemberRole.MAYOR));

            PhaseFourRepository.BuffPurchase first = repository.purchaseBuff(mayorId, "Mayor",
                    buff, BuffDurationOption.ONE_HOUR, 2, "buff:test:1", now);
            assertEquals(1, first.buff().level());
            assertEquals(90_000, first.balanceAfterMinor());
            assertEquals(first.buff().buffId(), repository.purchaseBuff(mayorId, "Mayor", buff,
                    BuffDurationOption.ONE_HOUR, 2, "buff:test:1", now).buff().buffId());
            assertThrows(PhaseFourRepository.ConflictException.class,
                    () -> repository.purchaseBuff(memberId, "Member", buff,
                            BuffDurationOption.ONE_HOUR, 2,
                            "buff:denied", now));

            PhaseFourRepository.BuffPurchase second = repository.purchaseBuff(mayorId, "Mayor",
                    buff, BuffDurationOption.ONE_HOUR, 2, "buff:test:2", now.plusSeconds(1));
            assertEquals(2, second.buff().level());
            assertEquals(70_000, second.balanceAfterMinor());
            assertThrows(IllegalArgumentException.class,
                    () -> repository.purchaseBuff(mayorId, "Mayor", buff,
                            BuffDurationOption.ONE_HOUR, 2,
                            "buff:test:3", now.plusSeconds(2)));
            assertEquals(1, repository.activeBuffsForPlayer(memberId, now).size());

            ResourceDefinition resource = new ResourceDefinition("iron", "铁锭补给",
                    "minecraft:iron_ingot", new BigDecimal("5.00"), 64, 80,
                    List.of(16, 32, 64), Set.of(MemberRole.MAYOR));
            Instant dayEnd = now.plus(Duration.ofDays(1));
            PhaseFourRepository.ResourceOrder order = repository.createOrder(mayorId, "Mayor",
                    resource, 32, 2, now, dayEnd, "resource:test:1");
            assertEquals(order.orderId(), repository.createOrder(mayorId, "Mayor", resource,
                    32, 2, now, dayEnd, "resource:test:1").orderId());
            assertEquals(1, repository.pendingOrderCount(mayorId));
            PhaseFourRepository.ResourceOrder claiming = repository.reserveClaim(order.orderId(),
                    mayorId, now.plusSeconds(3));
            assertNotNull(claiming.claimToken());
            assertEquals(claiming.claimToken(), repository.reserveClaim(order.orderId(), mayorId,
                    now.plusSeconds(4)).claimToken());
            PhaseFourRepository.ResourceOrder claimed = repository.completeClaim(order.orderId(),
                    mayorId, claiming.claimToken(), now.plusSeconds(5));
            assertEquals("CLAIMED", claimed.status());
            assertEquals("CLAIMED", repository.completeClaim(order.orderId(), mayorId,
                    claiming.claimToken(), now.plusSeconds(6)).status());

            PhaseFourRepository.ResourceOrder refundable = repository.createOrder(mayorId,
                    "Mayor", resource, 16, 2, now, dayEnd, "resource:test:2");
            assertEquals("REFUNDED", repository.refundOrder(refundable.orderId(), mayorId,
                    "Mayor", "测试退款").status());
            assertEquals(1, repository.ordersForPlayer(mayorId, 0, 45).stream()
                    .filter(value -> value.status().equals("CLAIMED")).count());

            PhaseFourRepository.BuffPurchase refundedBuff = repository.refundActiveBuff(
                    second.buff().buffId(), mayorId, "Mayor", "效果应用失败测试");
            assertEquals("CANCELLED", refundedBuff.buff().status());
            assertThrows(PhaseFourRepository.ConflictException.class,
                    () -> repository.refundActiveBuff(second.buff().buffId(), mayorId, "Mayor",
                            "重复退款测试"));
            assertEquals(refundedBuff.balanceAfterMinor(), accountBalance(gate, townId));
            assertEquals(1, ledgerCount(gate, townId, "BUFF_REFUND"));
            assertEquals(1, repository.activeBuffsForPlayer(memberId, now).getFirst().level());
        }
    }

    private static long accountBalance(DatabaseGate gate, UUID townId) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT balance_minor FROM town_accounts WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static int ledgerCount(DatabaseGate gate, UUID townId, String entryType)
            throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM ledger_entries WHERE town_id = ? AND entry_type = ?
                     """)) {
            statement.setBytes(1, uuid(townId));
            statement.setString(2, entryType);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void insertTown(DatabaseGate gate, UUID townId, UUID mayorId, UUID memberId)
            throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, '阶段四镇', '阶段四镇', '四', '四', '测试', '规则', 'ACTIVE', ?)
                     """);
             PreparedStatement member = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, ?)
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setBytes(2, uuid(mayorId));
            town.executeUpdate();
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(mayorId));
            member.setString(3, "MAYOR");
            member.executeUpdate();
            member.setBytes(2, uuid(memberId));
            member.setString(3, "MEMBER");
            member.executeUpdate();
            try (PreparedStatement balance = connection.prepareStatement("""
                    UPDATE town_accounts SET balance_minor = 100000 WHERE town_id = ?
                    """)) {
                balance.setBytes(1, uuid(townId));
                balance.executeUpdate();
            }
        }
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}

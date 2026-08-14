package cn.tianji.town.storage.commerce;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.core.consumption.BuffStackingRule;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsBuffPurchasesAndCompensatingRefundsIdempotent() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("phase4.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID mayorId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, memberId);
            CommerceRepository repository = new CommerceRepository(gate.dataSource(),
                    () -> false);
            Instant now = Instant.now();
            BuffDefinition buff = new BuffDefinition("speed", "公共迅捷",
                    BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                    new BigDecimal("100.00"), 2, BuffStackingRule.LEVEL_UP, 1,
                    Set.of(MemberRole.MAYOR));

            CommerceRepository.BuffPurchase first = repository.purchaseBuff(mayorId, "Mayor",
                    buff, BuffDurationOption.ONE_HOUR, 2, "buff:test:1", now);
            assertEquals(1, first.buff().level());
            assertEquals(90_000, first.balanceAfterMinor());
            assertEquals(first.buff().buffId(), repository.purchaseBuff(mayorId, "Mayor", buff,
                    BuffDurationOption.ONE_HOUR, 2, "buff:test:1", now).buff().buffId());
            assertThrows(CommerceRepository.ConflictException.class,
                    () -> repository.purchaseBuff(memberId, "Member", buff,
                            BuffDurationOption.ONE_HOUR, 2,
                            "buff:denied", now));

            CommerceRepository.BuffPurchase second = repository.purchaseBuff(mayorId, "Mayor",
                    buff, BuffDurationOption.ONE_HOUR, 2, "buff:test:2", now.plusSeconds(1));
            assertEquals(2, second.buff().level());
            assertEquals(70_000, second.balanceAfterMinor());
            assertThrows(IllegalArgumentException.class,
                    () -> repository.purchaseBuff(mayorId, "Mayor", buff,
                            BuffDurationOption.ONE_HOUR, 2,
                            "buff:test:3", now.plusSeconds(2)));
            assertEquals(1, repository.activeBuffsForPlayer(memberId, now).size());

            CommerceRepository.BuffPurchase refundedBuff = repository.refundActiveBuff(
                    second.buff().buffId(), mayorId, "Mayor", "效果应用失败测试");
            assertEquals("CANCELLED", refundedBuff.buff().status());
            assertThrows(CommerceRepository.ConflictException.class,
                    () -> repository.refundActiveBuff(second.buff().buffId(), mayorId, "Mayor",
                            "重复退款测试"));
            assertEquals(refundedBuff.balanceAfterMinor(), accountBalance(gate, townId));
            assertEquals(90_000, refundedBuff.balanceAfterMinor());
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
                     VALUES (?, '消费测试镇', '消费测试镇', '消', '消', '测试', '规则', 'ACTIVE', ?)
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

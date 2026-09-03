package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.core.consumption.BuffStackingRule;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
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
    void purchasesSelectedWeeklyDurationAndRomanIntensity() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("selected-buff.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID mayorId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, memberId);
            CommerceRepository repository = new CommerceRepository(gate.dataSource(), () -> false);
            BuffDefinition buff = new BuffDefinition("health", "生命",
                    BuffDefinition.EffectKind.ATTRIBUTE, "minecraft:max_health", "ADD_NUMBER",
                    new BigDecimal("1.00"), 5, BuffStackingRule.LEVEL_UP, 4,
                    Set.of(MemberRole.MAYOR));
            Instant now = Instant.parse("2026-08-27T00:00:00Z");

            CommerceRepository.SelectedBuffQuote quote = repository.quoteBuff(mayorId, buff,
                    2, 3, 2, now);
            assertEquals(80_640, quote.priceMinor());
            assertEquals(now.plus(Duration.ofDays(14)), quote.expiresAt());

            CommerceRepository.BuffPurchase purchase = repository.purchaseBuff(mayorId, "Mayor",
                    buff, 2, 3, 2, "buff:selected", now);
            assertEquals(3, purchase.buff().level());
            assertEquals(3, purchase.buff().stackCount());
            assertEquals(19_360, purchase.balanceAfterMinor());
            assertEquals(4, purchase.buff().amountPerLevel());
            assertThrows(IllegalArgumentException.class,
                    () -> repository.quoteBuff(mayorId, buff, 5, 3, 2, now));
            assertThrows(CommerceRepository.ConflictException.class,
                    () -> repository.quoteBuff(memberId, buff, 1, 1, 2, now));
        }
    }

    @Test
    void keepsBuffPurchasesAndCompensatingRefundsIdempotent() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("commerce-repository.db");
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
            assertEquals(1, repository.activeBuffsForPlayer(memberId, now.plusSeconds(1)).size());

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

            BuffDefinition extend = new BuffDefinition("extend", "延长测试",
                    BuffDefinition.EffectKind.POTION, "minecraft:haste", "AMPLIFIER",
                    new BigDecimal("10.00"), 2, BuffStackingRule.EXTEND, 1,
                    Set.of(MemberRole.MAYOR));
            CommerceRepository.BuffPurchase extendFirst = repository.purchaseBuff(mayorId,
                    "Mayor", extend, BuffDurationOption.ONE_HOUR, 2, "buff:extend:1", now);
            CommerceRepository.BuffPurchase extendSecond = repository.purchaseBuff(mayorId,
                    "Mayor", extend, BuffDurationOption.ONE_HOUR, 2, "buff:extend:2",
                    now.plusSeconds(60));
            assertEquals(1, extendSecond.buff().level());
            assertEquals(2, extendSecond.buff().stackCount());
            assertEquals(1_000, extendSecond.buff().priceMinor());
            assertEquals(extendFirst.buff().expiresAt().plusSeconds(3_600).toEpochMilli(),
                    extendSecond.buff().expiresAt().toEpochMilli());
            assertThrows(IllegalArgumentException.class,
                    () -> repository.purchaseBuff(mayorId, "Mayor", extend,
                            BuffDurationOption.ONE_HOUR, 2, "buff:extend:3",
                            now.plusSeconds(120)));

            BuffDefinition refresh = new BuffDefinition("refresh", "刷新测试",
                    BuffDefinition.EffectKind.POTION, "minecraft:luck", "AMPLIFIER",
                    new BigDecimal("10.00"), 2, BuffStackingRule.REFRESH, 1,
                    Set.of(MemberRole.MAYOR));
            CommerceRepository.BuffPurchase refreshFirst = repository.purchaseBuff(mayorId,
                    "Mayor", refresh, BuffDurationOption.ONE_HOUR, 2, "buff:refresh:1", now);
            Instant refreshAt = now.plusSeconds(60);
            CommerceRepository.BuffPurchase refreshSecond = repository.purchaseBuff(mayorId,
                    "Mayor", refresh, BuffDurationOption.ONE_HOUR, 2, "buff:refresh:2",
                    refreshAt);
            assertEquals(1, refreshSecond.buff().level());
            assertEquals(1, refreshSecond.buff().stackCount());
            assertEquals(1_000, refreshSecond.buff().priceMinor());
            assertEquals(refreshAt.plusSeconds(3_600).toEpochMilli(),
                    refreshSecond.buff().expiresAt().toEpochMilli());
            assertTrue(refreshSecond.buff().expiresAt().isAfter(refreshFirst.buff().expiresAt()));
        }
    }

    @Test
    void rollsBackBuffPurchaseAndRefundWhenLateAuditWriteFails() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("buff-rollback.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID mayorId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, memberId);
            CommerceRepository repository = new CommerceRepository(gate.dataSource(),
                    () -> false);
            BuffDefinition buff = new BuffDefinition("rollback", "事务测试",
                    BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                    new BigDecimal("100.00"), 2, BuffStackingRule.LEVEL_UP, 1,
                    Set.of(MemberRole.MAYOR));
            Instant now = Instant.now();

            installAuditFailure(gate, "fail_buff_purchase", "BUFF_PURCHASE");
            assertThrows(CommerceRepository.ConflictException.class,
                    () -> repository.purchaseBuff(mayorId, "Mayor", buff,
                            BuffDurationOption.ONE_HOUR, 2, "buff:rollback:purchase", now));
            assertEquals(100_000, accountBalance(gate, townId));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key = 'buff:rollback:purchase'"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM active_buffs "
                    + "WHERE business_key = 'buff:rollback:purchase'"));
            execute(gate, "DROP TRIGGER fail_buff_purchase");

            CommerceRepository.BuffPurchase purchased = repository.purchaseBuff(mayorId,
                    "Mayor", buff, BuffDurationOption.ONE_HOUR, 2,
                    "buff:rollback:success", now);
            installAuditFailure(gate, "fail_buff_refund", "BUFF_REFUND");
            assertThrows(CommerceRepository.ConflictException.class,
                    () -> repository.refundActiveBuff(purchased.buff().buffId(), mayorId,
                            "Mayor", "退款注入失败"));
            assertEquals(90_000, accountBalance(gate, townId));
            assertEquals("ACTIVE", scalarText(gate, "SELECT status FROM active_buffs "
                    + "WHERE business_key = 'buff:rollback:success'"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key LIKE 'buff-refund:%'"));
        }
    }

    @Test
    void restoresUnexpiredBuffsAndExpiresBoundaryRecordsAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("buff-restart.db");
        String url = "jdbc:sqlite:" + database;
        UUID townId = UUID.randomUUID();
        UUID mayorId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        Instant purchasedAt = Instant.parse("2026-08-22T00:00:00Z");
        Instant expiresAt;
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            insertTown(gate, townId, mayorId, memberId);
            CommerceRepository repository = new CommerceRepository(gate.dataSource(),
                    () -> false);
            BuffDefinition buff = new BuffDefinition("restart", "重启测试",
                    BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                    new BigDecimal("10.00"), 2, BuffStackingRule.LEVEL_UP, 1,
                    Set.of(MemberRole.MAYOR));
            expiresAt = repository.purchaseBuff(mayorId, "Mayor", buff,
                    BuffDurationOption.ONE_HOUR, 2, "buff:restart:once", purchasedAt)
                    .buff().expiresAt();
        }

        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            CommerceRepository repository = new CommerceRepository(gate.dataSource(),
                    () -> false);
            assertEquals(1, repository.activeBuffsForPlayer(memberId,
                    expiresAt.minusMillis(1)).size());
            assertTrue(repository.expireBuffs(expiresAt.minusMillis(1)).isEmpty());
            assertEquals(Set.of(townId), repository.expireBuffs(expiresAt));
            assertTrue(repository.activeBuffsForPlayer(memberId, expiresAt).isEmpty());
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key='buff:restart:once'"));
            assertEquals("EXPIRED", scalarText(gate, "SELECT status FROM active_buffs "
                    + "WHERE business_key='buff:restart:once'"));
        }
    }

    @Test
    void expiresPlayerBuffsAtTheirScheduledDeadlines() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("buff-expiration.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID mayorId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, memberId);
            CommerceRepository repository = new CommerceRepository(gate.dataSource(),
                    () -> false);
            BuffDefinition firstBuff = new BuffDefinition("expiration-one", "第一项到期测试",
                    BuffDefinition.EffectKind.POTION, "minecraft:speed", "AMPLIFIER",
                    new BigDecimal("10.00"), 1, BuffStackingRule.LEVEL_UP, 1,
                    Set.of(MemberRole.MAYOR));
            BuffDefinition secondBuff = new BuffDefinition("expiration-two", "第二项到期测试",
                    BuffDefinition.EffectKind.POTION, "minecraft:jump_boost", "AMPLIFIER",
                    new BigDecimal("10.00"), 1, BuffStackingRule.LEVEL_UP, 1,
                    Set.of(MemberRole.MAYOR));
            Instant now = Instant.parse("2026-08-27T00:00:00Z");
            CommerceRepository.BuffPurchase firstPurchase = repository.purchaseBuff(mayorId,
                    "Mayor", firstBuff, BuffDurationOption.ONE_HOUR, 2,
                    "buff:expiration:one", now);
            CommerceRepository.BuffPurchase secondPurchase = repository.purchaseBuff(mayorId,
                    "Mayor", secondBuff, BuffDurationOption.ONE_HOUR, 2,
                    "buff:expiration:two", now.plus(Duration.ofMinutes(30)));

            assertEquals(1, repository.expireBuffsForPlayer(memberId,
                    firstPurchase.buff().expiresAt()));
            assertEquals("EXPIRED", scalarText(gate, "SELECT status FROM active_buffs "
                    + "WHERE business_key = 'buff:expiration:one'"));
            assertEquals(1, repository.activeBuffsForPlayer(memberId,
                    firstPurchase.buff().expiresAt()).size());
            assertEquals("expiration-two", repository.activeBuffsForPlayer(memberId,
                    firstPurchase.buff().expiresAt()).get(0).buffKey());

            assertEquals(1, repository.expireBuffsForPlayer(memberId,
                    secondPurchase.buff().expiresAt()));
            assertTrue(repository.activeBuffsForPlayer(memberId,
                    secondPurchase.buff().expiresAt()).isEmpty());
            assertEquals(0, repository.expireBuffsForPlayer(memberId,
                    secondPurchase.buff().expiresAt()));
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

    private static void installAuditFailure(DatabaseGate gate, String triggerName, String action)
            throws Exception {
        execute(gate, "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON audit_logs "
                + "WHEN NEW.action = '" + action + "' BEGIN "
                + "SELECT RAISE(ABORT, 'injected audit failure'); END");
    }

    private static void execute(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String scalarText(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}

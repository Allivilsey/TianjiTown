package org.allivlisey.tianjitown.storage.economy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.*;
import org.allivlisey.tianjitown.storage.database.*;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EconomyOperationRecoveryTest {
    @TempDir Path directory;
    private final UUID administrator = UUID.randomUUID();

    @Test void startupCancelsUnstartedWorkAndIsolatesOnlyTownWithUncertainExternalPayment() throws Exception {
        try (var gate = database()) {
            var townRepository = new TownRepository(gate.dataSource(), () -> false);
            var alpha = town(townRepository, "alpha");
            var bravo = town(townRepository, "bravo");
            var repository = new EconomyRepository(gate.dataSource(), () -> false);
            var prepared = repository.prepareOperation(bravo.id(), "DONATION", 100, bravo.member(), "Member",
                    "prepared", "未调用外部支付");
            var external = repository.prepareOperation(alpha.id(), "DONATION", 200, alpha.member(), "Member",
                    "external", "外部调用结果未记账");
            repository.markOperationExternalApplied(external.operationId());

            repository.recoverInterruptedOperations();

            assertEquals("CANCELLED", operationStatus(gate, prepared.operationId()));
            assertEquals("COMPENSATION_REQUIRED", operationStatus(gate, external.operationId()));
            assertEquals(1, repository.pendingOperations().size());
            assertTrue(repository.findFinanceByTown(alpha.id()).orElseThrow().locked());
            assertFalse(repository.findFinanceByTown(bravo.id()).orElseThrow().locked());
            assertTrue(repository.findFinanceByTown(alpha.id()).orElseThrow().lockReason().startsWith("ECONOMY_COMPENSATION:"));
            assertEquals(5000, repository.findFinanceByTown(alpha.id()).orElseThrow().balanceMinor());
            repository.recoverInterruptedOperations();
            assertEquals(1, repository.pendingOperations().size());
            assertEquals(0, countByKey(gate, "ledger_entries", "business_key", "external"));
        }
    }

    @Test void verifiedAppliedOutcomePostsExactlyOneLedgerEntryAndOneAuditThenReconciliationUnlocks() throws Exception {
        try (var gate = database()) {
            var townRepository = new TownRepository(gate.dataSource(), () -> false);
            var alpha = town(townRepository, "alpha");
            var bravo = town(townRepository, "bravo");
            var repository = new EconomyRepository(gate.dataSource(), () -> false);
            var operation = repository.prepareOperation(alpha.id(), "DONATION", 200, alpha.member(), "Member",
                    "resolved-paid", "捐款");
            repository.markOperationExternalApplied(operation.operationId());
            repository.recoverInterruptedOperations();
            var expected = repository.pendingOperations().getFirst();

            var completed = repository.resolveOperation(expected, true, administrator, "Admin", "已核实玩家扣款与清算入账均成功");
            assertEquals("COMPLETED", completed.status());
            assertEquals(completed, repository.resolveOperation(expected, true, administrator, "Admin", "重复回调"));
            assertEquals(5200, repository.findFinanceByTown(alpha.id()).orElseThrow().balanceMinor());
            assertEquals(5000, repository.findFinanceByTown(bravo.id()).orElseThrow().balanceMinor());
            assertEquals(1, countByKey(gate, "ledger_entries", "business_key", "resolved-paid"));
            assertEquals(1, countByKey(gate, "audit_logs", "target_id", operation.operationId().toString()));
            assertTrue(repository.findFinanceByTown(alpha.id()).orElseThrow().locked());
            assertTrue(repository.reconcileSettlement(10_200).healthy());
            assertFalse(repository.findFinanceByTown(alpha.id()).orElseThrow().locked());
            assertFalse(repository.findFinanceByTown(bravo.id()).orElseThrow().locked());
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.resolveOperation(expected, false, administrator, "Admin", "旧确认不能改写已完成付款"));
        }
    }

    @Test void verifiedNoNetPaymentCancelsWithoutLedgerAndUnlocksAfterReconciliation() throws Exception {
        try (var gate = database()) {
            var alpha = town(new TownRepository(gate.dataSource(), () -> false), "alpha");
            var repository = new EconomyRepository(gate.dataSource(), () -> false);
            var operation = repository.prepareOperation(alpha.id(), "DONATION", 200, alpha.member(), "Member",
                    "resolved-unpaid", "捐款");
            repository.markOperationExternalApplied(operation.operationId());
            repository.recoverInterruptedOperations();
            var expected = repository.pendingOperations().getFirst();

            var cancelled = repository.resolveOperation(expected, false, administrator, "Admin", "核对双方余额均无净变化");
            assertEquals("CANCELLED", cancelled.status());
            assertEquals(cancelled, repository.resolveOperation(expected, false, administrator, "Admin", "重复回调"));
            assertEquals(5000, repository.findFinanceByTown(alpha.id()).orElseThrow().balanceMinor());
            assertEquals(0, countByKey(gate, "ledger_entries", "business_key", "resolved-unpaid"));
            assertEquals(1, countByKey(gate, "audit_logs", "target_id", operation.operationId().toString()));
            assertTrue(repository.findFinanceByTown(alpha.id()).orElseThrow().locked());
            assertTrue(repository.reconcileSettlement(5000).healthy());
            assertFalse(repository.findFinanceByTown(alpha.id()).orElseThrow().locked());
        }
    }

    @Test void balanceLedgerMismatchLocksOnlyAffectedTownEvenWhenSharedSettlementHasEnoughFunds() throws Exception {
        try (var gate = database()) {
            var townRepository = new TownRepository(gate.dataSource(), () -> false);
            var alpha = town(townRepository, "alpha");
            var bravo = town(townRepository, "bravo");
            var repository = new EconomyRepository(gate.dataSource(), () -> false);
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.prepareStatement("UPDATE town_accounts SET balance_minor = balance_minor + 1 WHERE town_id = ?")) {
                statement.setBytes(1, EconomyPersistence.uuid(alpha.id()));
                assertEquals(1, statement.executeUpdate());
            }
            repository.recoverInterruptedOperations();
            assertTrue(repository.findFinanceByTown(alpha.id()).orElseThrow().locked());
            assertTrue(repository.findFinanceByTown(alpha.id()).orElseThrow().lockReason().startsWith("LEDGER_RECONCILIATION:"));
            assertFalse(repository.findFinanceByTown(bravo.id()).orElseThrow().locked());
            repository.reconcileSettlement(100_000);
            assertTrue(repository.findFinanceByTown(alpha.id()).orElseThrow().locked());
            assertFalse(repository.findFinanceByTown(bravo.id()).orElseThrow().locked());
        }
    }

    @Test void oldDonationTargetIsRejectedInsideTransactionAfterPlayerWasTransferred() throws Exception {
        try (var gate = database()) {
            var townRepository = new TownRepository(gate.dataSource(), () -> false);
            var alpha = town(townRepository, "alpha");
            var bravo = town(townRepository, "bravo");
            var repository = new EconomyRepository(gate.dataSource(), () -> false);
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.prepareStatement("UPDATE town_members SET town_id = ? WHERE player_uuid = ?")) {
                statement.setBytes(1, EconomyPersistence.uuid(bravo.id()));
                statement.setBytes(2, EconomyPersistence.uuid(alpha.member()));
                assertEquals(1, statement.executeUpdate());
            }
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.prepareOperation(alpha.id(), "DONATION", 100, alpha.member(), "Member", "stale-donation", "旧界面确认"));
            assertEquals(0, countByKey(gate, "economy_operations", "business_key", "stale-donation"));
            assertEquals(5000, repository.findFinanceByTown(alpha.id()).orElseThrow().balanceMinor());
            var valid = repository.prepareOperation(bravo.id(), "DONATION", 100, alpha.member(), "Member", "current-donation", "新界面确认");
            assertEquals(bravo.id(), valid.townId());
        }
    }

    private Fixture town(TownRepository repository, String code) {
        UUID mayor = UUID.randomUUID(), member = UUID.randomUUID(), other = UUID.randomUUID();
        var application = repository.createDraft(mayor, new ApplicationText("测试" + code, code, "测试简介", List.of("测试规则")),
                List.of(member, other), Duration.ZERO);
        UUID token = application.initialMembers().getFirst().invitationToken();
        repository.respondInitialMember(application.id(), member, token, true);
        repository.respondInitialMember(application.id(), other, token, true);
        repository.selectSite(application.id(), mayor, new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0)),
                Instant.now().plusSeconds(3600), 1);
        repository.submit(application.id(), mayor);
        var provision = repository.beginProvision(application.id(), administrator, "Admin", "批准", "approve:" + code, 5000);
        repository.finishProvision(application.id(), true, "完成");
        return new Fixture(provision.town().id(), member);
    }

    private String operationStatus(DatabaseGate gate, UUID operation) throws Exception {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.prepareStatement("SELECT status FROM economy_operations WHERE operation_id = ?")) {
            statement.setBytes(1, EconomyPersistence.uuid(operation));
            try (var row = statement.executeQuery()) { assertTrue(row.next()); return row.getString(1); }
        }
    }

    private long countByKey(DatabaseGate gate, String table, String column, String value) throws Exception {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            statement.setString(1, value);
            try (var row = statement.executeQuery()) { assertTrue(row.next()); return row.getLong(1); }
        }
    }

    private DatabaseGate database() {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("operations.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(gate.verifyAndMigrate().healthy());
        return gate;
    }

    private record Fixture(UUID id, UUID member) { }
}

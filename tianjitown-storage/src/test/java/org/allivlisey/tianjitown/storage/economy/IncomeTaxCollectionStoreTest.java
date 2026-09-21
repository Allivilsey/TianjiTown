package org.allivlisey.tianjitown.storage.economy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.*;
import org.allivlisey.tianjitown.storage.database.*;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExternalIncomeTax;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.IncomeTaxCollection;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class IncomeTaxCollectionStoreTest {
    @TempDir Path directory;
    private final UUID administrator = UUID.randomUUID();

    @Test void collectionHasOneDurableClaimAndOnlyMatchingTaxLedgerCanCompleteIt() {
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            var tax = tax(fixture, "collected");
            var prepared = repository.prepareIncomeTaxCollection(tax);
            assertEquals(prepared, repository.prepareIncomeTaxCollection(tax));
            var attempted = repository.claimIncomeTaxCollection(prepared);
            assertEquals("ATTEMPTED", attempted.status());
            assertEquals(prepared.version() + 1, attempted.version());
            assertThrows(EconomyRepository.ConflictException.class, () -> repository.claimIncomeTaxCollection(prepared));
            assertThrows(EconomyRepository.ConflictException.class, () -> repository.markIncomeTaxRecorded(prepared.operationId()));

            var succeeded = repository.finishIncomeTaxCollection(attempted, "SUCCEEDED", "完整收款");
            assertEquals(succeeded, repository.finishIncomeTaxCollection(attempted, "SUCCEEDED", "重试相同结果"));
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.finishIncomeTaxCollection(attempted, "FAILED", "迟到不同结果"));
            assertThrows(EconomyRepository.ConflictException.class, () -> repository.markIncomeTaxRecorded(succeeded.operationId()));
            reserveAndRecord(repository, tax);
            repository.markIncomeTaxRecorded(succeeded.operationId());
            repository.markIncomeTaxRecorded(succeeded.operationId());
            assertTrue(repository.pendingIncomeTaxCollections().isEmpty());
            assertEquals(5200, repository.findFinanceByTown(fixture.townId()).orElseThrow().balanceMinor());
            assertEquals(1, repository.ledger(fixture.townId(), 0, 45).stream()
                    .filter(entry -> entry.entryType().equals("JOBS_TAX")).count());
        }
    }

    @Test void sameBusinessKeyCannotBeReusedForAnotherAmountOrPlayer() {
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            var original = tax(fixture, "same-key");
            repository.prepareIncomeTaxCollection(original);
            var changed = new ExternalIncomeTax(original.townId(), original.businessKey(), original.source(),
                    original.receiverId(), original.receiverName(), 3000, 1000, 300);
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.prepareIncomeTaxCollection(changed));
            assertEquals(1, repository.pendingIncomeTaxCollections().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void claimRechecksMembershipAndTownStateAndCancelsWithoutStartingPayment(boolean archived) {
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            var prepared = repository.prepareIncomeTaxCollection(tax(fixture, "eligibility"));
            var towns = new TownRepository(gate.dataSource(), () -> false);
            if (archived) towns.deleteTown(fixture.townId(), administrator, "Admin", "归档");
            else towns.leaveTown(fixture.member());
            var failed = repository.claimIncomeTaxCollection(prepared);
            assertEquals("FAILED", failed.status());
            assertTrue(repository.pendingIncomeTaxCollections().isEmpty());
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.prepareIncomeTaxCollection(tax(fixture, "new-ineligible")));
            assertEquals(5000, repository.findFinanceByTown(fixture.townId()).orElseThrow().balanceMinor());
        }
    }

    @Test void partialDebitSurvivesRestartAndRefundClaimsCannotBeRepeatedOrOverwritten() throws Exception {
        IncomeTaxCollection refundRequired;
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            var attempted = repository.claimIncomeTaxCollection(repository.prepareIncomeTaxCollection(tax(fixture, "partial")));
            refundRequired = repository.finishIncomeTaxCollection(attempted, "REFUND_REQUIRED", "玩家扣款成功，清算与原路退款均失败");
            assertFalse(repository.findFinanceByTown(fixture.townId()).orElseThrow().locked());
        }
        try (var gate = database()) {
            var repository = repository(gate);
            assertEquals(refundRequired, repository.pendingIncomeTaxCollections().getFirst());
            var firstRefund = repository.claimIncomeTaxRefund(refundRequired, administrator, "Admin");
            assertThrows(EconomyRepository.ConflictException.class, () -> repository.claimIncomeTaxRefund(refundRequired));
            var stillRequired = repository.finishIncomeTaxRefund(firstRefund, "REFUND_REQUIRED", "外部未退款，允许稍后重试");
            var secondRefund = repository.claimIncomeTaxRefund(stillRequired, administrator, "Admin");
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.finishIncomeTaxRefund(firstRefund, "REFUNDED", "旧回调不可覆盖新认领"));
            var refunded = repository.finishIncomeTaxRefund(secondRefund, "REFUNDED", "直接返还玩家成功");
            assertEquals(refunded, repository.finishIncomeTaxRefund(secondRefund, "REFUNDED", "重复记账"));
            assertTrue(repository.pendingIncomeTaxCollections().isEmpty());
            assertEquals(5000, repository.findFinanceByTown(refunded.tax().townId()).orElseThrow().balanceMinor());
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE action = 'INCOME_TAX_REFUND' AND actor_name = 'Admin' AND target_id = ?")) {
                statement.setString(1, refunded.operationId().toString());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); }
            }
        }
    }

    @Test void knownSuccessfulCollectionMayBeConfirmedForPostingButCannotBeCancelled() throws Exception {
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            var attempted = repository.claimIncomeTaxCollection(repository.prepareIncomeTaxCollection(tax(fixture, "known-paid")));
            var succeeded = repository.finishIncomeTaxCollection(attempted, "SUCCEEDED", "已完整到账");
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.resolveIncomeTaxCollection(succeeded, false, administrator, "Admin", "不能抹去已收款"));
            var confirmed = repository.resolveIncomeTaxCollection(succeeded, true, administrator, "Admin", "重试补记已确认到账的税款");
            assertEquals("SUCCEEDED", confirmed.status());
            assertEquals(succeeded.version() + 1, confirmed.version());
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.resolveIncomeTaxCollection(succeeded, true, administrator, "Admin", "旧确认"));
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE action = 'INCOME_TAX_COLLECTION_RESOLVE' AND target_id = ?")) {
                statement.setString(1, confirmed.operationId().toString());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
            assertEquals(5000, repository.findFinanceByTown(fixture.townId()).orElseThrow().balanceMinor());
        }
    }

    @Test void startupCancelsUnattemptedWorkAndMakesInterruptedPaymentsAmbiguousWithoutTownLocks() {
        IncomeTaxCollection unattempted, attempted, refundAttempted, succeeded;
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            unattempted = repository.prepareIncomeTaxCollection(tax(fixture, "prepared"));
            attempted = repository.claimIncomeTaxCollection(repository.prepareIncomeTaxCollection(tax(fixture, "attempted")));
            var partial = repository.claimIncomeTaxCollection(repository.prepareIncomeTaxCollection(tax(fixture, "partial")));
            refundAttempted = repository.claimIncomeTaxRefund(repository.finishIncomeTaxCollection(partial, "REFUND_REQUIRED", "欠退玩家"));
            var paid = repository.claimIncomeTaxCollection(repository.prepareIncomeTaxCollection(tax(fixture, "succeeded")));
            succeeded = repository.finishIncomeTaxCollection(paid, "SUCCEEDED", "已完整到账待记账");
        }
        try (var gate = database()) {
            var repository = repository(gate);
            repository.recoverInterruptedIncomeTaxCollections();
            var pending = repository.pendingIncomeTaxCollections();
            assertEquals(3, pending.size());
            assertEquals("FAILED", repository.prepareIncomeTaxCollection(unattempted.tax()).status());
            assertEquals("AMBIGUOUS", repository.prepareIncomeTaxCollection(attempted.tax()).status());
            assertEquals("AMBIGUOUS", repository.prepareIncomeTaxCollection(refundAttempted.tax()).status());
            assertEquals(succeeded, repository.prepareIncomeTaxCollection(succeeded.tax()));
            assertThrows(EconomyRepository.ConflictException.class, () -> repository.claimIncomeTaxCollection(attempted));
            assertThrows(EconomyRepository.ConflictException.class, () -> repository.claimIncomeTaxRefund(refundAttempted));
            repository.recoverInterruptedIncomeTaxCollections();
            assertEquals(pending, repository.pendingIncomeTaxCollections());
            assertFalse(repository.findFinanceByTown(succeeded.tax().townId()).orElseThrow().locked());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualResolutionUsesVersionSnapshotAndAuditWithoutMovingOrPostingMoney(boolean paid) throws Exception {
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            var attempted = repository.claimIncomeTaxCollection(repository.prepareIncomeTaxCollection(tax(fixture, "manual")));
            var ambiguous = repository.finishIncomeTaxCollection(attempted, "AMBIGUOUS", "返回丢失");
            var resolved = repository.resolveIncomeTaxCollection(ambiguous, paid, administrator, "Admin", "核实玩家及清算双方流水，已处理全部补偿");
            assertEquals(paid ? "SUCCEEDED" : "FAILED", resolved.status());
            assertEquals(ambiguous.version() + 1, resolved.version());
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.resolveIncomeTaxCollection(ambiguous, !paid, administrator, "Admin", "过期确认"));
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.finishIncomeTaxCollection(attempted, "FAILED", "过期回调"));
            assertEquals(5000, repository.findFinanceByTown(fixture.townId()).orElseThrow().balanceMinor());
            assertEquals(1, repository.ledger(fixture.townId(), 0, 45).size());
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT actor_name, reason FROM audit_logs WHERE action = 'INCOME_TAX_COLLECTION_RESOLVE' AND target_id = ?")) {
                statement.setString(1, ambiguous.operationId().toString());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("Admin", rows.getString("actor_name"));
                    assertTrue(rows.getString("reason").contains("双方流水"));
                    assertFalse(rows.next());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ATTEMPTED", "REFUND_ATTEMPTED"})
    void inactiveOrphanedClaimCanBeResolvedWithoutRestartUsingVersionAndAudit(String status) throws Exception {
        try (var gate = database()) {
            var fixture = fixture(gate);
            var repository = repository(gate);
            var collection = repository.claimIncomeTaxCollection(repository.prepareIncomeTaxCollection(tax(fixture, "orphaned-claim")));
            var expected = status.equals("ATTEMPTED") ? collection
                    : repository.claimIncomeTaxRefund(repository.finishIncomeTaxCollection(collection,
                            "REFUND_REQUIRED", "原扣款待退"), administrator, "Admin");
            // The runtime excludes any live Vault call before invoking this storage-only operation.
            boolean paid = status.equals("ATTEMPTED");
            var resolved = repository.resolveIncomeTaxCollection(expected, paid, administrator, "Admin",
                    "操作认领响应丢失，确认无在途调用并核对双方最终余额");
            assertEquals(paid ? "SUCCEEDED" : "FAILED", resolved.status());
            assertEquals(expected.version() + 1, resolved.version());
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.resolveIncomeTaxCollection(expected, !paid, administrator, "Admin", "旧确认不能覆盖"));
            if (paid) {
                assertThrows(EconomyRepository.ConflictException.class,
                        () -> repository.finishIncomeTaxCollection(expected, "FAILED", "迟到相反结果"));
            } else {
                assertThrows(EconomyRepository.ConflictException.class,
                        () -> repository.finishIncomeTaxRefund(expected, "REFUNDED", "迟到不同状态"));
            }
            assertEquals(5000, repository.findFinanceByTown(fixture.townId()).orElseThrow().balanceMinor());
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE action = 'INCOME_TAX_COLLECTION_RESOLVE' AND actor_name = 'Admin' AND target_id = ?")) {
                statement.setString(1, expected.operationId().toString());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
        }
    }

    private void reserveAndRecord(EconomyRepository repository, ExternalIncomeTax tax) {
        repository.reserveTaxSubsidy(tax.townId(), tax.businessKey(), tax.taxMinor(), 100_000, 100_000,
                Instant.now(), ZoneId.of("Asia/Shanghai"));
        repository.recordExternalIncomeTaxWithoutSubsidy(tax, "仅记已收税款");
    }

    private ExternalIncomeTax tax(Fixture fixture, String key) {
        return new ExternalIncomeTax(fixture.townId(), key, "JOBS", fixture.member(), "Member", 2000, 1000, 200);
    }

    private Fixture fixture(DatabaseGate gate) {
        var towns = new TownRepository(gate.dataSource(), () -> false);
        UUID mayor = UUID.randomUUID(), one = UUID.randomUUID(), two = UUID.randomUUID();
        var application = towns.createDraft(mayor,
                new ApplicationText("收税测试镇", "taxrec", "测试简介", List.of("测试规则")), List.of(one, two), Duration.ZERO);
        UUID token = application.initialMembers().getFirst().invitationToken();
        towns.respondInitialMember(application.id(), one, token, true);
        towns.respondInitialMember(application.id(), two, token, true);
        towns.selectSite(application.id(), mayor,
                new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0)), Instant.now().plusSeconds(3600), 1);
        towns.submit(application.id(), mayor);
        var provision = towns.beginProvision(application.id(), administrator, "Admin", "批准", "approve", 5000);
        towns.finishProvision(application.id(), true, "完成");
        return new Fixture(provision.town().id(), one);
    }

    private DatabaseGate database() {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("collections.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        var health = gate.verifyAndMigrate();
        assertTrue(health.healthy(), health.detail());
        return gate;
    }

    private EconomyRepository repository(DatabaseGate gate) { return new EconomyRepository(gate.dataSource(), () -> false); }
    private record Fixture(UUID townId, UUID member) { }
}

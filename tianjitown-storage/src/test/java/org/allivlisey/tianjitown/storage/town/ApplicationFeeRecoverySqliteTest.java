package org.allivlisey.tianjitown.storage.town;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.*;
import org.allivlisey.tianjitown.core.land.*;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.database.*;
import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationFeeRecoverySqliteTest {
    @TempDir Path directory;
    private final UUID admin = UUID.randomUUID();

    @Test void lateProvisionResultCannotReactivateArchivedTownOrChangeItsApplication() {
        try (var gate = database()) {
            var repository = repository(gate);
            var application = submitted(repository);
            var provision = repository.beginProvision(application.id(), admin, "Admin", "批准", "approve", 5000);
            repository.deleteTown(provision.town().id(), admin, "Admin", "删除在途小镇");
            var before = repository.findApplication(application.id()).orElseThrow();
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.provisioningForProjection(application.id()));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.finishProvision(application.id(), true, "迟到成功"));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.finishProvision(application.id(), false, "迟到失败"));
            assertEquals(before, repository.findApplication(application.id()).orElseThrow());
            assertEquals(TownStatus.ARCHIVED, repository.findTown(provision.town().id()).orElseThrow().status());
        }
    }

    @Test void partialDebitSurvivesRestartAndCannotBeChargedAgainUntilDirectRefundCompletes() {
        UUID id;
        try (var gate = database()) {
            var repository = repository(gate);
            var application = submitted(repository);
            id = application.id();
            var claim = repository.claimApplicationFeeCollection(id, application.version(), 5000, admin, "Admin");
            repository.completeApplicationFeeOperation(claim, Outcome.PLAYER_REFUND_REQUIRED, "清算入账及退款均失败", admin, "Admin");
        }
        try (var gate = database()) {
            var repository = repository(gate);
            var current = repository.findApplication(id).orElseThrow();
            assertEquals(State.PLAYER_REFUND_PENDING, repository.applicationFeeOperation(id).state());
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.claimApplicationFeeCollection(id, current.version(), 5000, admin, "Admin"));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.beginProvision(id, admin, "Admin", "再次批准", "again", 5000));
            var refund = repository.claimApplicationFeeRefund(id, admin, "Admin");
            assertEquals(State.PLAYER_REFUNDING, refund.state());
            repository.completeApplicationFeeOperation(refund, Outcome.SUCCESS, "已直接返还玩家", admin, "Admin");
            var fresh = repository.findApplication(id).orElseThrow();
            assertEquals(State.COLLECTING, repository.claimApplicationFeeCollection(id, fresh.version(), 5000, admin, "Admin").state());
        }
    }

    @Test void unknownCollectionRequiresAuditedResolutionAndStaleConfirmationCannotOverwrite() {
        try (var gate = database()) {
            var repository = repository(gate);
            var application = submitted(repository);
            var claim = repository.claimApplicationFeeCollection(application.id(), application.version(), 5000, admin, "Admin");
            var unknown = repository.completeApplicationFeeOperation(claim, Outcome.UNKNOWN, "Vault 超时", admin, "Admin");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.claimApplicationFeeRefund(application.id(), admin, "Admin"));
            var resolved = repository.resolveApplicationFee(application.id(), unknown.version(), Resolution.COLLECTED,
                    admin, "Admin", "核对双方交易流水，玩家扣款和清算入账均成功");
            assertEquals(State.ESCROWED, resolved.state());
            assertEquals(5000, repository.findApplication(application.id()).orElseThrow().applicationFeeMinor());
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.resolveApplicationFee(application.id(), unknown.version(), Resolution.NO_PAYMENT,
                            admin, "Admin", "过期确认"));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.completeApplicationFeeOperation(claim, Outcome.FAILED, "迟到失败", admin, "Admin"));
        }
    }

    @Test void interruptedCollectionCannotBeReplayedAfterRestartButCanBeConfirmedUnpaid() {
        UUID id;
        try (var gate = database()) {
            var repository = repository(gate);
            var application = submitted(repository);
            id = application.id();
            repository.claimApplicationFeeCollection(id, application.version(), 5000, admin, "Admin");
        }
        try (var gate = database()) {
            var repository = repository(gate);
            var operation = repository.pendingApplicationFees(100).getFirst();
            assertEquals(State.COLLECTING, operation.state());
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.claimApplicationFeeCollection(id, repository.findApplication(id).orElseThrow().version(), 5000, admin, "Admin"));
            repository.resolveApplicationFee(id, operation.version(), Resolution.NO_PAYMENT, admin, "Admin", "双方流水确认无净变化");
            assertEquals(State.COLLECTING, repository.claimApplicationFeeCollection(id,
                    repository.findApplication(id).orElseThrow().version(), 5000, admin, "Admin").state());
        }
    }

    @Test void cancelledFailedProvisionRefundCanResumeAndUnknownRefundCannotBePaidTwice() {
        try (var gate = database()) {
            var repository = repository(gate);
            var application = submitted(repository);
            repository.beginProvision(application.id(), admin, "Admin", "批准", "approve", 5000);
            repository.finishProvision(application.id(), false, "领地失败");
            repository.recoverFailedProvision(application.id(), admin, "Admin", "取消并退款", TownRepository.RecoveryMode.CANCEL_AND_REFUND);
            var first = repository.claimApplicationFeeRefund(application.id(), admin, "Admin");
            repository.completeApplicationFeeOperation(first, Outcome.FAILED, "经济插件暂不可用", admin, "Admin");
            var second = repository.claimApplicationFeeRefund(application.id(), admin, "Admin");
            var unknown = repository.completeApplicationFeeOperation(second, Outcome.UNKNOWN, "外部返回丢失", admin, "Admin");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.claimApplicationFeeRefund(application.id(), admin, "Admin"));
            repository.resolveApplicationFee(application.id(), unknown.version(), Resolution.REFUNDED, admin, "Admin", "玩家到账且清算已扣");
            assertEquals(ApplicationSnapshot.FeeStatus.REFUNDED,
                    repository.findApplication(application.id()).orElseThrow().applicationFeeStatus());
            assertTrue(repository.pendingApplicationFees(100).isEmpty());
        }
    }

    @Test void collectionClaimsUseBothApplicationVersionAndDurableState() {
        try (var gate = database()) {
            var repository = repository(gate);
            var application = submitted(repository);
            repository.claimApplicationFeeCollection(application.id(), application.version(), 5000, admin, "Admin");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.claimApplicationFeeCollection(application.id(), application.version(), 5000, UUID.randomUUID(), "Other"));
            var fresh = repository.findApplication(application.id()).orElseThrow();
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.claimApplicationFeeCollection(application.id(), fresh.version(), 5000, UUID.randomUUID(), "Other"));
        }
    }

    @Test void preparationFailureLeavesEscrowRefundableWithoutCreatingATown() {
        try (var gate = database()) {
            var repository = repository(gate);
            var application = submitted(repository);
            var claim = repository.claimApplicationFeeCollection(application.id(), application.version(), 5000, admin, "Admin");
            repository.completeApplicationFeeOperation(claim, Outcome.SUCCESS, "到账", admin, "Admin");
            repository.forceDeleteApplication(application.id().toString(), admin, "Admin", "付款后取消申请");
            assertThrows(IllegalStateException.class,
                    () -> repository.beginProvision(application.id(), admin, "Admin", "迟到审批", "late", 5000));
            assertEquals(State.ESCROWED, repository.pendingApplicationFees(100).getFirst().state());
            var refund = repository.claimApplicationFeeRefund(application.id(), admin, "Admin");
            repository.completeApplicationFeeOperation(refund, Outcome.SUCCESS, "已退款", admin, "Admin");
            assertEquals(State.REFUNDED, repository.applicationFeeOperation(application.id()).state());
        }
    }

    private ApplicationSnapshot submitted(TownRepository repository) {
        UUID applicant = UUID.randomUUID(), one = UUID.randomUUID(), two = UUID.randomUUID();
        var draft = repository.createDraft(applicant,
                new ApplicationText("恢复测试镇", "recovery", "测试简介", List.of("测试规则")),
                List.of(one, two), Duration.ZERO);
        UUID token = draft.initialMembers().getFirst().invitationToken();
        repository.respondInitialMember(draft.id(), one, token, true);
        repository.respondInitialMember(draft.id(), two, token, true);
        repository.selectSite(draft.id(), applicant,
                new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 30, 40)), Instant.now().plusSeconds(3600), 1);
        return repository.submit(draft.id(), applicant);
    }

    private DatabaseGate database() {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("recovery.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(gate.verifyAndMigrate().healthy());
        return gate;
    }

    private TownRepository repository(DatabaseGate gate) { return new TownRepository(gate.dataSource(), () -> false); }
}

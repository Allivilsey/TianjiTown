package org.allivlisey.tianjitown.storage.town;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ArchivedApplicationCancellationTest {
    @TempDir Path directory;
    private final UUID reviewer = UUID.randomUUID();

    @Test
    void cancelsDeletedTownsFailedApplicationWithoutRefundAndReleasesApplicantAndName() throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            UUID applicationId = provision.applicationId();
            var failed = repository.finishProvision(applicationId, false, "默认落脚空间不安全");
            repository.deleteTown(provision.town().id(), reviewer, "Admin", "删除失败小镇");
            repository.completeTownDeletion(provision.town().id(), reviewer, "Admin", "领地已清理");
            assertTrue(repository.findOpenApplication(failed.applicantId()).isPresent());
            var townBefore = repository.findTown(provision.town().id()).orElseThrow();
            long ledgerBefore = count(gate, "ledger_entries");

            var cancelled = repository.forceDeleteApplication("ReCoVeR", reviewer, "Admin", "只取消");

            assertEquals(ApplicationStatus.CANCELLED, cancelled.status());
            assertEquals(failed.townId(), cancelled.townId());
            assertEquals(failed.applicationFeeMinor(), cancelled.applicationFeeMinor());
            assertEquals(ApplicationSnapshot.FeeStatus.ESCROWED, cancelled.applicationFeeStatus());
            assertEquals(townBefore, repository.findTown(provision.town().id()).orElseThrow());
            assertEquals(ledgerBefore, count(gate, "ledger_entries"));
            assertTrue(repository.findOpenApplication(failed.applicantId()).isEmpty());
            assertTrue(repository.listReviewQueue(100).isEmpty());
            assertEquals(ApplicationStatus.CANCELLED,
                    repository.forceDeleteApplication("recover", reviewer, "Admin", "重复取消").status());
            var replacement = repository.createDraft(failed.applicantId(), failed.text(),
                    List.of(UUID.randomUUID(), UUID.randomUUID()), Duration.ofDays(1));
            assertNotEquals(applicationId, replacement.id());
        }
    }

    @Test
    void previousEmergencyCancellationRemainsExemptAfterReopeningDatabase() throws Exception {
        ApplicationSnapshot cancelled;
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            repository.finishProvision(provision.applicationId(), false, "失败");
            repository.deleteTown(provision.town().id(), reviewer, "Admin", "归档");
            repository.completeTownDeletion(provision.town().id(), reviewer, "Admin", "领地已清理");
            cancelled = repository.forceDeleteApplication("recover", reviewer, "Admin", "只取消");
            try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE audit_logs SET action = 'APPLICATION_CANCEL_ARCHIVED' "
                        + "WHERE action = 'APPLICATION_FORCE_DELETE'");
            }
        }
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            // The old command wrote the same cancellation and audit records, with no exemption flag.
            var replacement = repository.createDraft(cancelled.applicantId(), cancelled.text(),
                    List.of(UUID.randomUUID(), UUID.randomUUID()), Duration.ofDays(1));
            assertNotEquals(cancelled.id(), replacement.id());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emergencyExemptionDoesNotRemoveCooldownForLaterNormalCancellationOrRejection(boolean reject)
            throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            repository.finishProvision(provision.applicationId(), false, "失败");
            repository.deleteTown(provision.town().id(), reviewer, "Admin", "归档");
            repository.completeTownDeletion(provision.town().id(), reviewer, "Admin", "领地已清理");
            var cancelled = repository.forceDeleteApplication("recover", reviewer, "Admin", "只取消");
            UUID one = UUID.randomUUID(), two = UUID.randomUUID();
            var draft = repository.createDraft(cancelled.applicantId(), cancelled.text(),
                    List.of(one, two), Duration.ofDays(1));
            if (reject) {
                UUID token = draft.initialMembers().getFirst().invitationToken();
                repository.respondInitialMember(draft.id(), one, token, true);
                repository.respondInitialMember(draft.id(), two, token, true);
                repository.selectSite(draft.id(), draft.applicantId(),
                        new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 30, 40)),
                        Instant.now().plusSeconds(3600), 1);
                repository.submit(draft.id(), draft.applicantId());
                repository.reject(draft.id(), reviewer, "Admin", "普通拒绝");
            } else {
                repository.cancel(draft.id(), draft.applicantId(), "普通撤回");
            }
            var error = assertThrows(TownRepository.ConflictException.class, () -> repository.createDraft(
                    draft.applicantId(), draft.text(), List.of(one, two), Duration.ofDays(1)));
            assertEquals("申请冷却尚未结束", error.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "archived-active", "archived-creating"})
    void cancelsOtherApplicationAndTownStates(String state) throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            if (state.equals("failed")) repository.finishProvision(provision.applicationId(), false, "失败");
            if (state.endsWith("active")) repository.finishProvision(provision.applicationId(), true, "成功");
            if (state.startsWith("archived-")) {
                repository.deleteTown(provision.town().id(), reviewer, "Admin", "归档");
            }
            var before = repository.findApplication(provision.applicationId()).orElseThrow();
            var townBefore = repository.findTown(provision.town().id()).orElseThrow();
            var cancelled = repository.forceDeleteApplication("recover", reviewer, "Admin", "只取消");
            assertEquals(ApplicationStatus.CANCELLED, cancelled.status());
            assertEquals(before.applicationFeeStatus(), cancelled.applicationFeeStatus());
            assertEquals(townBefore, repository.findTown(provision.town().id()).orElseThrow());
            Class<? extends RuntimeException> rejected = townBefore.status() == TownStatus.PROVISIONING
                    ? IllegalStateException.class : TownRepository.ConflictException.class;
            assertThrows(rejected,
                    () -> repository.finishProvision(provision.applicationId(), true, "迟到的创建回调"));
            assertEquals(cancelled, repository.findApplication(provision.applicationId()).orElseThrow());
            assertEquals(townBefore, repository.findTown(provision.town().id()).orElseThrow());
        }
    }

    @Test
    void cancellationDoesNotReleaseArchivedLandStillAwaitingCleanup() throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            repository.finishProvision(provision.applicationId(), false, "失败");
            repository.deleteTown(provision.town().id(), reviewer, "Admin", "归档");
            long chunks = count(gate, "territory_chunks");
            assertTrue(chunks > 0);
            repository.forceDeleteApplication("recover", reviewer, "Admin", "只取消");
            assertEquals(chunks, count(gate, "territory_chunks"));
            assertEquals(TownStatus.ARCHIVED, repository.findTown(provision.town().id()).orElseThrow().status());
        }
    }

    private DatabaseGate database() {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("cancel.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(gate.verifyAndMigrate().healthy());
        return gate;
    }

    @ParameterizedTest
    @CsvSource({"CANCEL_AND_REFUND, false", "CANCEL_AND_REFUND, true",
            "FORCE_CLEANUP, false", "FORCE_CLEANUP, true"})
    void guiRecoveryAllowsImmediateReapplicationWithoutChangingRefundBehavior(
            TownRepository.RecoveryMode mode, boolean reopenDatabase) throws Exception {
        ApplicationSnapshot refunded;
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            repository.finishProvision(provision.applicationId(), false, "传送点失败");
            var cancelled = repository.recoverFailedProvision(provision.applicationId(), reviewer,
                    "Admin", "管理员清理失败申请", mode);
            assertEquals(ApplicationStatus.CANCELLED, cancelled.status());
            assertEquals(ApplicationSnapshot.FeeStatus.REFUND_PENDING, cancelled.applicationFeeStatus());
            assertTrue(repository.findTown(provision.town().id()).isEmpty());
            refunded = repository.completeApplicationFeeRefund(cancelled.id(), reviewer, "Admin", "已退款");
            assertEquals(ApplicationSnapshot.FeeStatus.REFUNDED, refunded.applicationFeeStatus());
            if (!reopenDatabase) {
                assertRecoveryExemptsOnlyRecoveredApplication(repository, refunded);
                return;
            }
        }
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            assertRecoveryExemptsOnlyRecoveredApplication(repository, refunded);
        }
    }

    private void assertRecoveryExemptsOnlyRecoveredApplication(TownRepository repository,
                                                              ApplicationSnapshot refunded) {
        var replacement = repository.createDraft(refunded.applicantId(), refunded.text(),
                List.of(UUID.randomUUID(), UUID.randomUUID()), Duration.ofDays(1));
        assertNotEquals(refunded.id(), replacement.id());
        repository.cancel(replacement.id(), replacement.applicantId(), "玩家普通撤回");
        var error = assertThrows(TownRepository.ConflictException.class, () -> repository.createDraft(
                replacement.applicantId(), replacement.text(), List.of(UUID.randomUUID(), UUID.randomUUID()),
                Duration.ofDays(1)));
        assertEquals("申请冷却尚未结束", error.getMessage());
    }

    private TownRepository.Provisioning provision(TownRepository repository) {
        UUID applicant = UUID.randomUUID(), one = UUID.randomUUID(), two = UUID.randomUUID();
        var draft = repository.createDraft(applicant,
                new ApplicationText("恢复测试镇", "recover", "测试简介", List.of("测试规则")),
                List.of(one, two), Duration.ZERO);
        UUID token = draft.initialMembers().getFirst().invitationToken();
        repository.respondInitialMember(draft.id(), one, token, true);
        repository.respondInitialMember(draft.id(), two, token, true);
        repository.selectSite(draft.id(), applicant,
                new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 30, 40)),
                Instant.now().plusSeconds(3600), 1);
        repository.submit(draft.id(), applicant);
        return repository.beginProvision(draft.id(), reviewer, "Admin", "批准", "provision", 500_000);
    }

    private long count(DatabaseGate gate, String table) throws Exception {
        try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}

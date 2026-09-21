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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationProvisioningCancellationGuardTest {
    @TempDir Path directory;
    private final UUID admin = UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsCancellationWithoutBreakingCompletionOrFailedProvisionRecovery(boolean failed) throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            if (failed) repository.finishProvision(provision.applicationId(), false, "领地创建失败");
            var applicationBefore = repository.findApplication(provision.applicationId()).orElseThrow();
            var townBefore = repository.findTown(provision.town().id()).orElseThrow();
            var feeBefore = repository.applicationFeeOperation(provision.applicationId());
            long ledgerBefore = count(gate, "ledger_entries");
            long membersBefore = count(gate, "town_members");
            long auditsBefore = count(gate, "audit_logs");

            var error = assertThrows(TownRepository.ConflictException.class,
                    () -> repository.forceDeleteApplication("recover", admin, "Admin", "强制取消"));

            assertTrue(error.getMessage().contains("先恢复失败建镇"));
            assertEquals(applicationBefore, repository.findApplication(provision.applicationId()).orElseThrow());
            assertEquals(townBefore, repository.findTown(provision.town().id()).orElseThrow());
            assertEquals(feeBefore, repository.applicationFeeOperation(provision.applicationId()));
            assertEquals(ledgerBefore, count(gate, "ledger_entries"));
            assertEquals(membersBefore, count(gate, "town_members"));
            assertEquals(auditsBefore, count(gate, "audit_logs"));
            if (failed) {
                var recovered = repository.recoverFailedProvision(provision.applicationId(), admin,
                        "Admin", "清理失败建镇", TownRepository.RecoveryMode.CANCEL_AND_REFUND);
                assertEquals(ApplicationStatus.CANCELLED, recovered.status());
                assertEquals(ApplicationSnapshot.FeeStatus.REFUND_PENDING, recovered.applicationFeeStatus());
                assertNull(recovered.townId());
                assertTrue(repository.findTown(provision.town().id()).isEmpty());
            } else {
                assertEquals(ApplicationStatus.ACTIVE,
                        repository.finishProvision(provision.applicationId(), true, "创建完成").status());
                assertEquals(TownStatus.ACTIVE, repository.findTown(provision.town().id()).orElseThrow().status());
            }
        }
    }

    @Test
    void linkedProvisioningTownBlocksCancellationEvenWhenApplicationStatusAlreadyChanged() throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var provision = provision(repository);
            setStatus(gate, provision.applicationId(), ApplicationStatus.CANCELLED);
            var before = repository.findApplication(provision.applicationId()).orElseThrow();

            assertThrows(TownRepository.ConflictException.class, () -> repository.forceDeleteApplication(
                    provision.applicationId().toString(), admin, "Admin", "再次取消"));

            assertEquals(before, repository.findApplication(provision.applicationId()).orElseThrow());
            assertEquals(TownStatus.PROVISIONING,
                    repository.findTown(provision.town().id()).orElseThrow().status());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"APPROVED_PROVISIONING", "PROVISION_FAILED"})
    void provisioningStateWithoutTownMustBeRecoveredBeforeForceCancellation(ApplicationStatus status)
            throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            var draft = draft(repository);
            setStatus(gate, draft.id(), status);
            var before = repository.findApplication(draft.id()).orElseThrow();
            long auditsBefore = count(gate, "audit_logs");

            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.forceDeleteApplication("recover", admin, "Admin", "强制取消"));

            assertEquals(before, repository.findApplication(draft.id()).orElseThrow());
            assertEquals(auditsBefore, count(gate, "audit_logs"));
        }
    }

    private TownRepository.Provisioning provision(TownRepository repository) {
        var draft = draft(repository);
        UUID token = draft.initialMembers().getFirst().invitationToken();
        for (var member : draft.initialMembers()) {
            repository.respondInitialMember(draft.id(), member.playerId(), token, true);
        }
        repository.selectSite(draft.id(), draft.applicantId(),
                new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 30, 40)),
                Instant.now().plusSeconds(3600), 1);
        var submitted = repository.submit(draft.id(), draft.applicantId());
        var claim = repository.claimApplicationFeeCollection(draft.id(), submitted.version(),
                500_000, admin, "Admin");
        repository.completeApplicationFeeOperation(claim, ApplicationFeeOperation.Outcome.SUCCESS,
                "申请费已收取", admin, "Admin");
        return repository.beginProvision(draft.id(), admin, "Admin", "批准", "provision", 500_000);
    }

    private ApplicationSnapshot draft(TownRepository repository) {
        return repository.createDraft(UUID.randomUUID(),
                new ApplicationText("恢复测试镇", "recover", "测试简介", List.of("测试规则")),
                List.of(UUID.randomUUID(), UUID.randomUUID()), Duration.ZERO);
    }

    private void setStatus(DatabaseGate gate, UUID applicationId, ApplicationStatus status) throws Exception {
        try (var connection = gate.dataSource().getConnection(); var statement = connection.prepareStatement(
                "UPDATE town_applications SET status = ? WHERE application_id = ?")) {
            statement.setString(1, status.name());
            statement.setBytes(2, TownSqlValues.uuid(applicationId));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private long count(DatabaseGate gate, String table) throws Exception {
        try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private DatabaseGate database() {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("guard.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(gate.verifyAndMigrate().healthy());
        return gate;
    }
}

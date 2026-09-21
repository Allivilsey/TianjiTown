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
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationAdminManagementTest {
    @TempDir Path directory;
    private final UUID admin = UUID.randomUUID();

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"APPROVED_PROVISIONING", "PROVISION_FAILED"})
    void forceDeletionAcceptsNonProvisioningStatesAndReleasesReservations(ApplicationStatus status) throws Exception {
        try (var gate = database()) {
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var draft = draft(repository, UUID.randomUUID(), "sample");
            repository.selectSite(draft.id(), draft.applicantId(),
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0)),
                    Instant.now().plusSeconds(3600), 1);
            try (var connection = gate.dataSource().getConnection(); var statement = connection.prepareStatement(
                    "UPDATE town_applications SET status = ? WHERE application_id = ?")) {
                statement.setString(1, status.name());
                statement.setBytes(2, TownSqlValues.uuid(draft.id()));
                statement.executeUpdate();
            }
            assertTrue(repository.listApplicationsForCompletion(500).stream()
                    .anyMatch(application -> application.id().equals(draft.id())));
            var cancelled = repository.forceDeleteApplication("SaMpLe", admin, "Admin", "强制删除");
            assertEquals(ApplicationStatus.CANCELLED, cancelled.status());
            assertTrue(repository.findOpenApplication(draft.applicantId()).isEmpty());
            try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT released_at FROM site_reservations")) {
                assertTrue(result.next());
                assertNotNull(result.getObject(1));
            }
            assertNotEquals(draft.id(), draft(repository, draft.applicantId(), "sample").id());
        }
    }

    @Test
    void ambiguousCodesRequireUuidAndMissingTargetsLeaveRecordsUntouched() {
        try (var gate = database()) {
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var first = draft(repository, UUID.randomUUID(), "same");
            var cancelled = repository.forceDeleteApplication("same", admin, "Admin", "删除");
            var second = draft(repository, first.applicantId(), "same");
            var error = assertThrows(TownRepository.ConflictException.class,
                    () -> repository.forceDeleteApplication("same", admin, "Admin", "删除"));
            assertTrue(error.getMessage().contains(first.id().toString()));
            assertTrue(error.getMessage().contains(second.id().toString()));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.forceDeleteApplication("missing", admin, "Admin", "删除"));
            assertEquals(cancelled, repository.findApplication(first.id()).orElseThrow());
            assertEquals(second, repository.findApplication(second.id()).orElseThrow());
            assertEquals(second.id(), repository.forceDeleteApplication(second.id().toString(),
                    admin, "Admin", "删除").id());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearCooldownPersistsAndDoesNotExemptOtherPlayersOrFutureApplications(boolean reject) throws Exception {
        UUID applicant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (var gate = database()) {
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var first = draft(repository, applicant, "first");
            var second = draft(repository, applicant, "second", Duration.ZERO, true);
            if (reject) {
                UUID token = second.initialMembers().getFirst().invitationToken();
                for (var member : second.initialMembers()) {
                    repository.respondInitialMember(second.id(), member.playerId(), token, true);
                }
                repository.selectSite(second.id(), applicant,
                        new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0)),
                        Instant.now().plusSeconds(3600), 1);
                repository.submit(second.id(), applicant);
                repository.reject(second.id(), admin, "Admin", "拒绝");
            } else {
                repository.cancel(second.id(), applicant, "撤回");
            }
            var secondBefore = repository.findApplication(second.id()).orElseThrow();
            var firstBefore = repository.findApplication(first.id()).orElseThrow();
            var otherDraft = draft(repository, other, "other");
            repository.cancel(otherDraft.id(), other, "撤回");
            assertCooldown(repository, applicant, "blocked");
            assertEquals(2, repository.clearApplicationCooldown(applicant, admin, "Admin"));
            assertEquals(0, repository.clearApplicationCooldown(applicant, admin, "Admin"));
            assertEquals(firstBefore, repository.findApplication(first.id()).orElseThrow());
            assertEquals(secondBefore, repository.findApplication(second.id()).orElseThrow());
        }
        try (var gate = database()) {
            var repository = new TownRepository(gate.dataSource(), () -> false);
            assertCooldown(repository, other, "othernew");
            var later = draft(repository, applicant, "later");
            assertEquals(0, repository.clearApplicationCooldown(applicant, admin, "Admin"));
            assertEquals(later, repository.findApplication(later.id()).orElseThrow());
            repository.cancel(later.id(), applicant, "新的撤回");
            assertCooldown(repository, applicant, "laternew");
        }
    }

    @Test
    void deletingHistoricalApplicationKeepsNewFormButDeletingItsTargetRemovesForm() {
        try (var gate = database()) {
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var first = draft(repository, UUID.randomUUID(), "first");
            repository.forceDeleteApplication("first", admin, "Admin", "删除");
            var second = draft(repository, first.applicantId(), "second");
            repository.saveFormDraft(new ApplicationFormDraft(second.applicantId(), second.id(),
                    second.version(), 1, "编辑中的资料", "second", "简介", List.of("规则"),
                    null, "", null, "", null));
            var form = repository.findFormDraft(second.applicantId()).orElseThrow();
            repository.forceDeleteApplication(first.id().toString(), admin, "Admin", "再次删除历史记录");
            assertEquals(form, repository.findFormDraft(second.applicantId()).orElseThrow());
            repository.forceDeleteApplication(second.id().toString(), admin, "Admin", "删除当前申请");
            assertTrue(repository.findFormDraft(second.applicantId()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditFailureRollsBackAdministrativeMutation(boolean clearCooldown) throws Exception {
        try (var gate = database()) {
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var application = draft(repository, UUID.randomUUID(), "rollback");
            if (clearCooldown) repository.cancel(application.id(), application.applicantId(), "撤回");
            var before = repository.findApplication(application.id()).orElseThrow();
            try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_admin_audit BEFORE INSERT ON audit_logs
                        WHEN NEW.action = 'APPLICATION_FORCE_DELETE'
                          OR (NEW.action = 'APPLICATION_CLEAR_COOLDOWN' AND NEW.target_type = 'PLAYER')
                        BEGIN SELECT RAISE(ABORT, 'test audit failure'); END
                        """);
            }
            assertThrows(TownRepository.ConflictException.class, () -> {
                if (clearCooldown) repository.clearApplicationCooldown(application.applicantId(), admin, "Admin");
                else repository.forceDeleteApplication("rollback", admin, "Admin", "删除");
            });
            assertEquals(before, repository.findApplication(application.id()).orElseThrow());
            if (clearCooldown) assertCooldown(repository, application.applicantId(), "blocked");
        }
    }

    private void assertCooldown(TownRepository repository, UUID applicant, String code) {
        var error = assertThrows(TownRepository.ConflictException.class, () -> draft(repository, applicant, code));
        assertEquals("申请冷却尚未结束", error.getMessage());
    }

    private ApplicationSnapshot draft(TownRepository repository, UUID applicant, String code) {
        return draft(repository, applicant, code, Duration.ofDays(1), false);
    }

    private ApplicationSnapshot draft(TownRepository repository, UUID applicant, String code,
                                      Duration cooldown, boolean cancelExisting) {
        if (cancelExisting) repository.findOpenApplication(applicant)
                .ifPresent(application -> repository.cancel(application.id(), applicant, "撤回"));
        return repository.createDraft(applicant, new ApplicationText(code, code, "简介", List.of("规则")),
                List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown);
    }

    private DatabaseGate database() {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("admin.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(gate.verifyAndMigrate().healthy());
        return gate;
    }
}

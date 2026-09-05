package org.allivlisey.tianjitown.storage.town;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationFormDraftSqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsIncompleteDraftAndOverwritesOptionalFields() throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            UUID applicant = UUID.randomUUID();
            assertTrue(repository.findFormDraft(applicant).isEmpty());
            ApplicationFormDraft draft = draft(applicant, UUID.randomUUID(), List.of("友善交流", ""));
            ApplicationFormDraft saved = repository.saveFormDraft(draft);
            assertEquals(draft.memberOneId(), saved.memberOneId());
            assertEquals(draft.rules(), saved.rules());
            assertEquals(draft.name(), saved.name());
            assertEquals(draft.description(), saved.description());
            assertEquals(draft.memberOneName(), saved.memberOneName());
            assertNull(saved.applicationId());
            assertNull(saved.memberTwoId());
            assertNotNull(saved.updatedAt());
            assertEquals(saved, repository.findFormDraft(applicant).orElseThrow());

            ApplicationFormDraft updated = repository.saveFormDraft(draft(applicant, null, List.of()));
            assertNull(updated.memberOneId());
            assertTrue(updated.rules().isEmpty());
            assertEquals(updated, repository.findFormDraft(applicant).orElseThrow());
            assertEquals(2, repository.auditLog(10).size());

            repository.deleteFormDraft(applicant);
            repository.deleteFormDraft(applicant);
            assertTrue(repository.findFormDraft(applicant).isEmpty());
        }
    }

    @Test
    void auditFailureRollsBackDraftOverwrite() throws Exception {
        try (DatabaseGate gate = database()) {
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            UUID applicant = UUID.randomUUID();
            ApplicationFormDraft saved = repository.saveFormDraft(draft(applicant, null, List.of("原规则")));
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_draft_audit BEFORE INSERT ON audit_logs
                        WHEN NEW.action = 'APPLICATION_DRAFT_SAVE'
                        BEGIN SELECT RAISE(ABORT, 'test audit failure'); END
                        """);
            }
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.saveFormDraft(draft(applicant, UUID.randomUUID(), List.of("新规则"))));
            assertEquals(saved, repository.findFormDraft(applicant).orElseThrow());
            assertEquals(1, repository.auditLog(10).size());
        }
    }

    private DatabaseGate database() {
        DatabaseGate gate = new DatabaseGate(new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("drafts.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(gate.verifyAndMigrate().healthy());
        return gate;
    }

    private static ApplicationFormDraft draft(UUID applicant, UUID member, List<String> rules) {
        return new ApplicationFormDraft(applicant, null, 0, 2, "草稿小镇", "", "",
                "尚未完成的表单", rules, member, member == null ? "" : "初始成员", null, "", null);
    }
}

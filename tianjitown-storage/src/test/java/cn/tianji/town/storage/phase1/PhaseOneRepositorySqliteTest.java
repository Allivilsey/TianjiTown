package cn.tianji.town.storage.phase1;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseOneRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completesApplicationAndInvitationLifecycle() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("lifecycle.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            PhaseOneRepository repository = new PhaseOneRepository(gate.dataSource(), () -> false);
            UUID applicantId = UUID.randomUUID();
            UUID reviewerId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            ApplicationText text = new ApplicationText(
                    "天际镇", "TJ", "SKY", "测试简介", List.of("友善交流"));
            ApplicationSnapshot draft = repository.createDraft(
                    applicantId, text, Duration.ofMinutes(5));
            assertThrows(PhaseOneRepository.ConflictException.class, () -> repository.createDraft(
                    UUID.randomUUID(), text, Duration.ofMinutes(5)));
            InitialTerritory territory = new InitialTerritory(
                    new ChunkPosition(UUID.randomUUID(), "world", 10, 20));
            ApplicationSnapshot selected = repository.selectSite(
                    draft.id(), applicantId, territory, Instant.now().plus(Duration.ofHours(1)), 1);
            ApplicationSnapshot submitted = repository.submit(selected.id(), applicantId);
            assertEquals(ApplicationStatus.SUBMITTED, submitted.status());

            PhaseOneRepository.Provisioning provisioning = repository.beginProvision(
                    submitted.id(), reviewerId, "Admin", "审核通过", "test:approve");
            assertEquals(TownStatus.PROVISIONING, provisioning.town().status());
            ApplicationSnapshot active = repository.finishProvision(submitted.id(), true, "ok");
            assertEquals(ApplicationStatus.ACTIVE, active.status());

            InvitationSnapshot invitation = repository.invite(
                    provisioning.town().id(), applicantId, memberId, Duration.ofDays(1));
            repository.acceptInvitation(invitation.id(), memberId);
            assertEquals(2, repository.listMemberIds(provisioning.town().id()).size());
            assertTrue(repository.auditLog(20).size() >= 5);
        }
    }
}

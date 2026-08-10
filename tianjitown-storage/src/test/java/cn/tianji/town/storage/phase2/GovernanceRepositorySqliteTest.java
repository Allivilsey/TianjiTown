package cn.tianji.town.storage.phase2;

import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.governance.VoteStatus;
import cn.tianji.town.core.governance.VoteType;
import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.phase1.ApplicationSnapshot;
import cn.tianji.town.storage.phase1.JoinApplicationSnapshot;
import cn.tianji.town.storage.phase1.PhaseOneRepository;
import cn.tianji.town.storage.phase1.TownSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void handlesOfficerRulesTransferAndVotesTransactionally() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("governance.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            PhaseOneRepository phaseOne = new PhaseOneRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(), () -> false);
            CreatedTown created = createTown(phaseOne);
            UUID officerId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID candidateId = UUID.randomUUID();
            phaseOne.addMember(created.town().id(), officerId, created.mayorId(), "Admin", "测试");
            phaseOne.addMember(created.town().id(), targetId, created.mayorId(), "Admin", "测试");
            phaseOne.addMember(created.town().id(), candidateId, created.mayorId(), "Admin", "测试");
            for (UUID playerId : List.of(created.mayorId(), officerId, targetId, candidateId)) {
                governance.recordActivity(playerId);
            }

            assertEquals(MemberRole.OFFICER, governance.changeRoleByMayor(created.town().id(),
                    officerId, MemberRole.OFFICER, created.mayorId(), "Mayor"));
            UUID applicantId = UUID.randomUUID();
            JoinApplicationSnapshot join = phaseOne.applyToTown(created.town().id(), applicantId,
                    Duration.ofHours(48), Duration.ZERO, Duration.ZERO, 3);
            phaseOne.approveJoinApplication(join.id(), officerId);
            assertTrue(phaseOne.listMemberIds(created.town().id()).contains(applicantId));

            TownSnapshot beforeRules = phaseOne.findTown(created.town().id()).orElseThrow();
            phaseOne.updateTownProfile(beforeRules.id(), new ApplicationText(
                    beforeRules.profile().name(), beforeRules.profile().shortName(),
                    beforeRules.profile().residenceName(), beforeRules.profile().description(),
                    List.of("新规则")), beforeRules.version(), created.mayorId(), "Mayor", "更新规则");
            MemberGovernanceSnapshot pendingRules = governance.dashboard(officerId).orElseThrow();
            assertTrue(pendingRules.requiresRulesConfirmation());
            governance.acknowledgeRules(created.town().id(), officerId,
                    pendingRules.townRulesRevision());
            assertFalse(governance.dashboard(officerId).orElseThrow().requiresRulesConfirmation());

            VoteSnapshot kick = governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                    targetId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                    Duration.ofHours(72), false);
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                            candidateId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                            Duration.ofHours(72), false));
            for (UUID voter : List.of(created.mayorId(), officerId, candidateId, applicantId)) {
                governance.recordActivity(voter);
                VoteSnapshot current = governance.listTownVotes(created.town().id(), voter, false)
                        .stream().filter(vote -> vote.id().equals(kick.id())).findFirst().orElseThrow();
                if (current.status() == VoteStatus.OPEN && current.viewerEligible()
                        && !current.viewerVoted()) {
                    governance.castVote(kick.id(), voter, true);
                }
            }
            VoteSnapshot settledKick = governance.listTownVotes(created.town().id(),
                    created.mayorId(), false).stream().filter(vote -> vote.id().equals(kick.id()))
                    .findFirst().orElseThrow();
            assertEquals(VoteStatus.PASSED, settledKick.status());
            assertFalse(phaseOne.listMemberIds(created.town().id()).contains(targetId));

            TransferSnapshot transfer = governance.requestMayorTransfer(created.town().id(),
                    candidateId, created.mayorId(), Duration.ofHours(24));
            governance.decideMayorTransfer(transfer.id(), candidateId, true);
            TownSnapshot transferred = phaseOne.findTown(created.town().id()).orElseThrow();
            assertEquals(candidateId, transferred.mayorId());
            assertEquals(MemberRole.MAYOR, governance.memberRole(created.town().id(), candidateId));

            VoteSnapshot replace = governance.createVote(created.town().id(),
                    VoteType.REPLACE_MAYOR, created.mayorId(), officerId,
                    Duration.ofDays(30), Duration.ZERO, Duration.ofHours(72), false);
            for (UUID voter : List.of(created.mayorId(), officerId, applicantId)) {
                VoteSnapshot current = governance.listTownVotes(created.town().id(), voter, false)
                        .stream().filter(vote -> vote.id().equals(replace.id()))
                        .findFirst().orElseThrow();
                if (current.status() == VoteStatus.OPEN && current.viewerEligible()
                        && !current.viewerVoted()) {
                    governance.castVote(replace.id(), voter, true);
                }
            }
            VoteSnapshot settledReplace = governance.listTownVotes(created.town().id(),
                    officerId, false).stream().filter(vote -> vote.id().equals(replace.id()))
                    .findFirst().orElseThrow();
            assertEquals(VoteStatus.PASSED, settledReplace.status());
            assertEquals(created.mayorId(), phaseOne.findTown(created.town().id())
                    .orElseThrow().mayorId());

            phaseOne.deleteTown(created.town().id(), UUID.randomUUID(), "Admin", "归档测试");
            try (Connection connection = gate.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM town_archived_members WHERE town_id = ?")) {
                statement.setBytes(1, uuid(created.town().id()));
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(4, result.getInt(1));
                }
            }
        }
    }

    private static CreatedTown createTown(PhaseOneRepository repository) {
        UUID mayorId = UUID.randomUUID();
        ApplicationText text = new ApplicationText("治理测试镇", "治理", "GOV",
                "测试简介", List.of("初始规则"));
        ApplicationSnapshot draft = repository.createDraft(mayorId, text, Duration.ZERO);
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(UUID.randomUUID(),
                "world", 100, 100));
        ApplicationSnapshot selected = repository.selectSite(draft.id(), mayorId, territory,
                Instant.now().plus(Duration.ofHours(1)), 1);
        ApplicationSnapshot submitted = repository.submit(selected.id(), mayorId);
        PhaseOneRepository.Provisioning provisioning = repository.beginProvision(submitted.id(),
                UUID.randomUUID(), "Admin", "审核通过", "phase2:test:" + submitted.id());
        repository.finishProvision(submitted.id(), true, "ok");
        return new CreatedTown(repository.findTown(provisioning.town().id()).orElseThrow(), mayorId);
    }

    private static byte[] uuid(UUID value) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
        return buffer.array();
    }

    private record CreatedTown(TownSnapshot town, UUID mayorId) {
    }
}

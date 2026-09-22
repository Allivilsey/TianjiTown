package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.governance.VoteStatus;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.allivlisey.tianjitown.storage.SqliteTestSupport.execute;
import static org.allivlisey.tianjitown.storage.SqliteTestSupport.installAuditFailure;
import static org.allivlisey.tianjitown.storage.SqliteTestSupport.scalar;
import static org.allivlisey.tianjitown.storage.SqliteTestSupport.scalarText;
import static org.allivlisey.tianjitown.storage.SqliteTestSupport.uuid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "KICK_MEMBER,2,false", "KICK_MEMBER,3,true",
            "REPLACE_MAYOR,2,false", "REPLACE_MAYOR,3,true"})
    void fourVoterThresholdsFreezeEligibilityAndApplyOutcomeOnce(
            VoteType type, int yesCount, boolean passed) throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("four-voters.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(), () -> false);
            CreatedTown created = createTown(towns);
            UUID townId = created.town().id();
            for (int i = 0; i < 2; i++) {
                towns.addMember(townId, UUID.randomUUID(), created.mayorId(), "Admin", "选民夹具");
            }
            for (UUID member : towns.listMemberIds(townId)) governance.recordActivity(member);
            UUID target = towns.listMemberIds(townId).stream()
                    .filter(id -> !id.equals(created.mayorId())).findFirst().orElseThrow();
            VoteSnapshot vote = governance.createVote(townId, type, target, created.mayorId(),
                    Duration.ofDays(30), Duration.ZERO, Duration.ofHours(72), false);
            List<UUID> voters = governance.listVoteVoterIds(vote.id());
            assertEquals(4, voters.size());
            assertEquals(3, vote.requiredYes());
            UUID excluded = type == VoteType.KICK_MEMBER ? target : created.mayorId();
            assertFalse(voters.contains(excluded));
            UUID newcomer = UUID.randomUUID();
            towns.addMember(townId, newcomer, created.mayorId(), "Admin", "冻结后加入");
            governance.recordActivity(newcomer);
            for (UUID ineligible : List.of(excluded, newcomer, UUID.randomUUID())) {
                assertThrows(GovernanceRepository.ConflictException.class,
                        () -> governance.castVote(vote.id(), ineligible, true));
            }
            assertEquals(voters, governance.listVoteVoterIds(vote.id()));
            for (int i = 0; i < voters.size(); i++) {
                governance.castVote(vote.id(), voters.get(i), i < yesCount);
                if (i == 0) {
                    assertThrows(GovernanceRepository.ConflictException.class,
                            () -> governance.castVote(vote.id(), voters.getFirst(), false));
                }
                if (i < voters.size() - 1) {
                    assertEquals(VoteStatus.OPEN, governance.listTownVotes(townId,
                            created.mayorId(), false).getFirst().status());
                }
            }
            VoteSnapshot settled = governance.listTownVotes(townId, created.mayorId(), false)
                    .getFirst();
            assertEquals(passed ? VoteStatus.PASSED : VoteStatus.REJECTED, settled.status());
            assertEquals(yesCount, settled.yesVotes());
            if (type == VoteType.KICK_MEMBER) {
                assertEquals(!passed, towns.listMemberIds(townId).contains(target));
                assertEquals(created.mayorId(), towns.findTown(townId).orElseThrow().mayorId());
            } else {
                assertEquals(passed ? target : created.mayorId(),
                        towns.findTown(townId).orElseThrow().mayorId());
                assertEquals(passed ? MemberRole.MEMBER : MemberRole.MAYOR,
                        governance.memberRole(townId, created.mayorId()));
            }
            governance.settleVote(vote.id(), created.mayorId(), "Admin", true);
            assertEquals(1, scalar(gate,
                    "SELECT COUNT(*) FROM audit_logs WHERE action='VOTE_SETTLE'"));
            assertEquals(1, scalar(gate,
                    "SELECT COUNT(*) FROM town_members WHERE role='MAYOR'"));
        }
    }

    @Test
    void persistsVoteResultsForAllMembersAcrossSettlementPaths() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("vote-results.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(), () -> false);
            CreatedTown created = createTown(towns);
            UUID target = UUID.randomUUID();
            towns.addMember(created.town().id(), target, created.mayorId(), "Admin", "测试");
            governance.recordActivity(created.mayorId());
            for (String path : List.of("ballot", "due", "admin", "cancel")) {
                governance.recordActivity(target);
                VoteSnapshot vote = governance.createVote(created.town().id(),
                        path.equals("ballot") ? VoteType.KICK_MEMBER : VoteType.REPLACE_MAYOR,
                        target, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                        Duration.ofHours(1), true);
                assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM vote_result_notifications"));
                if (path.equals("ballot")) {
                    for (UUID voter : governance.listVoteVoterIds(vote.id())) {
                        governance.castVote(vote.id(), voter, true);
                    }
                } else if (path.equals("due")) {
                    setLongForUuid(gate, "UPDATE governance_votes SET ends_at = ? WHERE vote_id = ?",
                            0, vote.id());
                    governance.settleDueVotes();
                } else if (path.equals("admin")) {
                    installAuditFailure(gate, "fail_result", "VOTE_SETTLE");
                    assertThrows(RuntimeException.class, () -> governance.settleVote(
                            vote.id(), created.mayorId(), "Admin", true));
                    assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM vote_result_notifications"));
                    dropTrigger(gate, "fail_result");
                    governance.settleVote(vote.id(), created.mayorId(), "Admin", true);
                } else {
                    governance.cancelOwnVote(vote.id(), created.mayorId(), "Mayor");
                }
                assertEquals(4, scalar(gate, "SELECT COUNT(*) FROM vote_result_notifications"));
                var result = towns.pendingVoteResults(created.mayorId()).getFirst();
                assertEquals(vote.id(), result.voteId());
                assertEquals(path.equals("ballot") ? "PASSED" : path.equals("cancel")
                        ? "CANCELLED" : "REJECTED", result.status());
                assertEquals(1, towns.pendingVoteResults(target).size());
                governance.settleVote(vote.id(), created.mayorId(), "Admin", true);
                assertEquals(4, scalar(gate, "SELECT COUNT(*) FROM vote_result_notifications"));
                towns.acknowledgeVoteResults(UUID.randomUUID(), List.of(result.id()));
                assertEquals(1, towns.pendingVoteResults(created.mayorId()).size());
                towns.acknowledgeVoteResults(created.mayorId(), List.of(result.id()));
                assertTrue(towns.pendingVoteResults(created.mayorId()).isEmpty());
                execute(gate, "DELETE FROM vote_result_notifications");
                if (path.equals("ballot")) {
                    towns.addMember(created.town().id(), target, created.mayorId(), "Admin", "测试");
                }
            }
        }
    }

    @Test
    void handlesOfficerRulesTransferAndVotesTransactionally() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("governance.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository townRepository = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(), () -> false);
            CreatedTown created = createTown(townRepository);
            String renamedTown = "改名后的治理测试镇";
            renameTown(gate, created.town().id(), renamedTown);
            UUID officerId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();
            UUID candidateId = UUID.randomUUID();
            townRepository.addMember(created.town().id(), officerId, created.mayorId(), "Admin", "测试");
            townRepository.addMember(created.town().id(), targetId, created.mayorId(), "Admin", "测试");
            townRepository.addMember(created.town().id(), candidateId, created.mayorId(), "Admin", "测试");
            for (UUID playerId : List.of(created.mayorId(), officerId, targetId, candidateId)) {
                governance.recordActivity(playerId);
            }

            assertEquals(MemberRole.DEPUTY_MAYOR, governance.changeRoleByMayor(created.town().id(),
                    officerId, MemberRole.DEPUTY_MAYOR, created.mayorId(), "Mayor"));
            UUID secondDeputy = UUID.randomUUID();
            UUID thirdDeputy = UUID.randomUUID();
            UUID fourthDeputy = UUID.randomUUID();
            for (UUID playerId : List.of(secondDeputy, thirdDeputy, fourthDeputy)) {
                townRepository.addMember(created.town().id(), playerId, created.mayorId(), "Admin", "测试");
            }
            governance.changeRoleByMayor(created.town().id(), secondDeputy,
                    MemberRole.DEPUTY_MAYOR, created.mayorId(), "Mayor");
            governance.changeRoleByMayor(created.town().id(), thirdDeputy,
                    MemberRole.DEPUTY_MAYOR, created.mayorId(), "Mayor");
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.changeRoleByMayor(created.town().id(), fourthDeputy,
                            MemberRole.DEPUTY_MAYOR, created.mayorId(), "Mayor"));
            TownPlayerChange removedMember = governance.removeMemberByMayor(
                    created.town().id(), fourthDeputy, officerId, "Deputy");
            assertEquals(renamedTown, removedMember.townName());
            assertEquals(fourthDeputy, removedMember.playerId());
            assertFalse(townRepository.listMemberIds(created.town().id()).contains(fourthDeputy));
            UUID applicantId = UUID.randomUUID();
            JoinApplicationSnapshot join = townRepository.applyToTown(created.town().id(), applicantId,
                    Duration.ofHours(48), Duration.ZERO, Duration.ZERO, 3);
            townRepository.approveJoinApplication(join.id(), officerId);
            assertTrue(townRepository.listMemberIds(created.town().id()).contains(applicantId));

            TownSnapshot beforeRules = townRepository.findTown(created.town().id()).orElseThrow();
            townRepository.updateTownProfile(beforeRules.id(), new ApplicationText(
                    beforeRules.profile().name(),
                    beforeRules.profile().residenceName(), beforeRules.profile().description(),
                    List.of("新规则")), beforeRules.version(), created.mayorId(), "Mayor", "更新规则");
            MemberGovernanceSnapshot pendingRules = governance.dashboard(officerId).orElseThrow();
            assertTrue(pendingRules.requiresRulesConfirmation());
            governance.acknowledgeRules(created.town().id(), officerId,
                    pendingRules.townRulesRevision());
            assertFalse(governance.dashboard(officerId).orElseThrow().requiresRulesConfirmation());

            Duration overflowingDuration = Duration.ofSeconds(Long.MAX_VALUE);
            assertThrows(IllegalArgumentException.class,
                    () -> governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                            targetId, created.mayorId(), overflowingDuration, Duration.ZERO,
                            Duration.ofHours(72), false));
            assertThrows(IllegalArgumentException.class,
                    () -> governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                            targetId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                            overflowingDuration, false));

            VoteSnapshot kick = governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                    targetId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                    Duration.ofHours(72), false);
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                            candidateId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                            Duration.ofHours(72), false));
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.createVote(created.town().id(), VoteType.REPLACE_MAYOR,
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
            assertFalse(townRepository.listMemberIds(created.town().id()).contains(targetId));
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.cancelVote(settledKick.id(), created.mayorId(), "Mayor",
                            "不能取消已结束投票"));

            VoteSnapshot cancellable = governance.createVote(created.town().id(),
                    VoteType.KICK_MEMBER, candidateId, created.mayorId(), Duration.ofDays(30),
                    Duration.ZERO, Duration.ofHours(72), false);
            assertEquals(created.mayorId(), cancellable.createdBy());
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.cancelOwnVote(cancellable.id(), officerId, "Officer"));
            assertEquals(VoteStatus.CANCELLED, governance.cancelOwnVote(cancellable.id(),
                    created.mayorId(), "Mayor").status());

            assertThrows(IllegalArgumentException.class,
                    () -> governance.requestMayorTransfer(created.town().id(), candidateId,
                            created.mayorId(), overflowingDuration));
            TransferSnapshot transfer = governance.requestMayorTransfer(created.town().id(),
                    candidateId, created.mayorId(), Duration.ofHours(24));
            governance.decideMayorTransfer(transfer.id(), candidateId, true);
            TownSnapshot transferred = townRepository.findTown(created.town().id()).orElseThrow();
            assertEquals(candidateId, transferred.mayorId());
            assertEquals(MemberRole.MAYOR, governance.memberRole(created.town().id(), candidateId));

            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.createVote(created.town().id(), VoteType.REPLACE_MAYOR,
                            created.mayorId(), applicantId, Duration.ofDays(30), Duration.ZERO,
                            Duration.ofHours(72), false));
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
            assertEquals(created.mayorId(), townRepository.findTown(created.town().id())
                    .orElseThrow().mayorId());

            townRepository.deleteTown(created.town().id(), UUID.randomUUID(), "Admin", "归档测试");
            try (Connection connection = gate.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT COUNT(*) FROM town_archived_members WHERE town_id = ?")) {
                statement.setBytes(1, uuid(created.town().id()));
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(8, result.getInt(1));
                }
            }
        }
    }

    @Test
    void allowsCurrentMayorToCreateReplacementVoteWithoutVotingEligibility() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("mayor-replacement-vote.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(),
                    () -> false);
            CreatedTown created = createTown(towns);
            UUID candidateId = UUID.randomUUID();
            towns.addMember(created.town().id(), candidateId, created.mayorId(),
                    "Admin", "测试候选人");
            governance.recordActivity(created.mayorId());
            governance.recordActivity(candidateId);

            VoteSnapshot vote = governance.createVote(created.town().id(),
                    VoteType.REPLACE_MAYOR, candidateId, created.mayorId(),
                    Duration.ofDays(30), Duration.ZERO, Duration.ofHours(72), false);

            assertEquals(VoteStatus.OPEN, vote.status());
            assertFalse(vote.viewerEligible());
            assertEquals(1, vote.eligibleVoters());
        }
    }

    @Test
    void rollsBackRoleAndVoteWhenLateAuditWriteFails() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("governance-rollback.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(),
                    () -> false);
            CreatedTown created = createTown(towns);
            UUID targetId = UUID.randomUUID();
            towns.addMember(created.town().id(), targetId, created.mayorId(), "Admin", "测试");

            installAuditFailure(gate, "fail_role_change", "MEMBER_ROLE_CHANGE");
            assertThrows(GovernanceRepository.StorageUnavailableException.class,
                    () -> governance.changeRoleByMayor(created.town().id(), targetId,
                            MemberRole.DEPUTY_MAYOR, created.mayorId(), "Mayor"));
            assertEquals(MemberRole.MEMBER,
                    governance.memberRole(created.town().id(), targetId));
            dropTrigger(gate, "fail_role_change");

            for (UUID playerId : towns.listMemberIds(created.town().id())) {
                governance.recordActivity(playerId);
            }
            installAuditFailure(gate, "fail_vote_create", "VOTE_CREATE");
            assertThrows(GovernanceRepository.StorageUnavailableException.class,
                    () -> governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                            targetId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                            Duration.ofHours(72), false));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM governance_votes"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM governance_vote_voters"));
            dropTrigger(gate, "fail_vote_create");

            VoteSnapshot vote = governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                    targetId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                    Duration.ofHours(72), false);
            governance.castVote(vote.id(), created.mayorId(), true);
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.castVote(vote.id(), created.mayorId(), true));
            assertEquals(1, scalarForUuid(gate,
                    "SELECT COUNT(*) FROM governance_vote_ballots WHERE vote_id = ?", vote.id()));
            VoteSnapshot settled = governance.settleVote(vote.id(), created.mayorId(),
                    "Mayor", true);
            VoteSnapshot repeated = governance.settleVote(vote.id(), created.mayorId(),
                    "Mayor", true);
            assertEquals(settled.status(), repeated.status());
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM audit_logs "
                    + "WHERE action = 'VOTE_SETTLE'"));
        }
    }

    @Test
    void settlesExpiredVotesAtTheBoundaryAndRejectsStaleTargets() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("governance-expiry.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(),
                    () -> false);
            CreatedTown created = createTown(towns);
            UUID targetId = UUID.randomUUID();
            UUID extraVoter = UUID.randomUUID();
            towns.addMember(created.town().id(), targetId, created.mayorId(), "Admin", "测试目标");
            towns.addMember(created.town().id(), extraVoter, created.mayorId(), "Admin", "测试选民");
            for (UUID playerId : towns.listMemberIds(created.town().id())) {
                governance.recordActivity(playerId);
            }

            VoteSnapshot due = governance.createVote(created.town().id(), VoteType.KICK_MEMBER,
                    targetId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                    Duration.ofHours(72), false);
            List<UUID> voters = towns.listMemberIds(created.town().id()).stream()
                    .filter(playerId -> !playerId.equals(targetId)).toList();
            for (UUID voter : voters.subList(0, due.requiredYes())) {
                governance.castVote(due.id(), voter, true);
            }
            assertTrue(governance.settleDueVotes().isEmpty());
            setLongForUuid(gate, "UPDATE governance_votes SET ends_at=? WHERE vote_id=?",
                    Instant.now().toEpochMilli() - 1, due.id());
            assertEquals(VoteStatus.PASSED, governance.settleDueVotes().getFirst().status());
            assertTrue(governance.settleDueVotes().isEmpty());
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM audit_logs "
                    + "WHERE action='VOTE_SETTLE'"));

            UUID staleTarget = UUID.randomUUID();
            towns.addMember(created.town().id(), staleTarget, created.mayorId(),
                    "Admin", "失效目标");
            governance.recordActivity(staleTarget);
            VoteSnapshot stale = governance.createVote(created.town().id(),
                    VoteType.KICK_MEMBER, staleTarget, created.mayorId(), Duration.ofDays(30),
                    Duration.ZERO, Duration.ofHours(72), false);
            List<UUID> staleVoters = towns.listMemberIds(created.town().id()).stream()
                    .filter(playerId -> !playerId.equals(staleTarget)).toList();
            for (UUID voter : staleVoters.subList(0, stale.requiredYes())) {
                governance.castVote(stale.id(), voter, true);
            }
            towns.removeMember(created.town().id(), staleTarget, created.mayorId(),
                    "Mayor", "结算前目标离镇");
            setLongForUuid(gate, "UPDATE governance_votes SET ends_at=? WHERE vote_id=?",
                    Instant.now().toEpochMilli() - 1, stale.id());
            assertEquals(VoteStatus.REJECTED,
                    governance.settleDueVotes().getFirst().status());
        }
    }

    @Test
    void settlesConcurrentFinalBallotAndAdministrativeRequestsOnlyOnce() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("governance-settle-race.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate firstGate = new DatabaseGate(config);
             DatabaseGate secondGate = new DatabaseGate(config);
             DatabaseGate thirdGate = new DatabaseGate(config)) {
            assertTrue(firstGate.verifyAndMigrate().healthy());
            assertTrue(secondGate.verifyAndMigrate().healthy());
            assertTrue(thirdGate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(firstGate.dataSource(), () -> false);
            GovernanceRepository first = new GovernanceRepository(firstGate.dataSource(),
                    () -> false);
            GovernanceRepository second = new GovernanceRepository(secondGate.dataSource(),
                    () -> false);
            GovernanceRepository third = new GovernanceRepository(thirdGate.dataSource(),
                    () -> false);
            CreatedTown created = createTown(towns);
            UUID targetId = UUID.randomUUID();
            UUID lastVoter = UUID.randomUUID();
            towns.addMember(created.town().id(), targetId, created.mayorId(), "Admin", "测试目标");
            towns.addMember(created.town().id(), lastVoter, created.mayorId(), "Admin", "最后选民");
            for (UUID playerId : towns.listMemberIds(created.town().id())) {
                first.recordActivity(playerId);
            }
            VoteSnapshot vote = first.createVote(created.town().id(), VoteType.KICK_MEMBER,
                    targetId, created.mayorId(), Duration.ofDays(30), Duration.ZERO,
                    Duration.ofHours(72), false);
            List<UUID> voters = towns.listMemberIds(created.town().id()).stream()
                    .filter(playerId -> !playerId.equals(targetId)).toList();
            List<UUID> initialVoters = voters.stream()
                    .filter(playerId -> !playerId.equals(lastVoter))
                    .limit(vote.requiredYes()).toList();
            for (UUID voter : initialVoters) {
                first.castVote(vote.id(), voter, true);
            }

            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(3)) {
                var ballot = executor.submit(() -> runAfter(start,
                        () -> first.castVote(vote.id(), lastVoter, false)));
                var admin = executor.submit(() -> runAfter(start,
                        () -> second.settleVote(vote.id(), UUID.randomUUID(), "Admin", true)));
                var scheduler = executor.submit(() -> runAfter(start,
                        () -> third.settleVote(vote.id(), null, "SYSTEM", true)));
                start.countDown();
                ballot.get();
                admin.get();
                scheduler.get();
            }

            VoteSnapshot settled = first.listTownVotes(created.town().id(),
                    created.mayorId(), false).stream()
                    .filter(candidate -> candidate.id().equals(vote.id())).findFirst().orElseThrow();
            assertEquals(VoteStatus.PASSED, settled.status());
            assertFalse(towns.listMemberIds(created.town().id()).contains(targetId));
            assertEquals(1, scalar(firstGate, "SELECT COUNT(*) FROM audit_logs "
                    + "WHERE action='VOTE_SETTLE'"));
        }
    }

    @Test
    void rejectsExpiredOrInvalidatedMayorTransfersAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("transfer-restart.db");
        DatabaseConfig config = new DatabaseConfig("jdbc:sqlite:" + database,
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        UUID townId;
        UUID mayorId;
        UUID expiredCandidate;
        UUID expiredTransfer;
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(),
                    () -> false);
            CreatedTown created = createTown(towns);
            townId = created.town().id();
            mayorId = created.mayorId();
            expiredCandidate = UUID.randomUUID();
            towns.addMember(townId, expiredCandidate, mayorId, "Admin", "到期候选");
            TransferSnapshot transfer = governance.requestMayorTransfer(townId,
                    expiredCandidate, mayorId, Duration.ofHours(24));
            expiredTransfer = transfer.id();
            setLongForUuid(gate,
                    "UPDATE mayor_transfer_requests SET expires_at=? WHERE transfer_id=?",
                    Instant.now().toEpochMilli() - 1, transfer.id());
        }

        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(),
                    () -> false);
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.decideMayorTransfer(expiredTransfer, expiredCandidate, true));
            assertEquals(MemberRole.MAYOR, governance.memberRole(townId, mayorId));
            assertEquals(MemberRole.MEMBER, governance.memberRole(townId, expiredCandidate));
            assertEquals("EXPIRED", scalarText(gate,
                    "SELECT status FROM mayor_transfer_requests WHERE transfer_id=x'"
                            + expiredTransfer.toString().replace("-", "") + "'"));

            UUID leavingCandidate = UUID.randomUUID();
            towns.addMember(townId, leavingCandidate, mayorId, "Admin", "离镇候选");
            TransferSnapshot leavingTransfer = governance.requestMayorTransfer(townId,
                    leavingCandidate, mayorId, Duration.ofHours(24));
            towns.removeMember(townId, leavingCandidate, mayorId, "Mayor", "候选离镇");
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.decideMayorTransfer(leavingTransfer.id(),
                            leavingCandidate, true));
            assertEquals(MemberRole.MAYOR, governance.memberRole(townId, mayorId));
            execute(gate, "UPDATE mayor_transfer_requests SET status='CANCELLED' "
                    + "WHERE transfer_id=x'"
                    + leavingTransfer.id().toString().replace("-", "") + "'");

            UUID archivedCandidate = UUID.randomUUID();
            towns.addMember(townId, archivedCandidate, mayorId, "Admin", "归档候选");
            TransferSnapshot archivedTransfer = governance.requestMayorTransfer(townId,
                    archivedCandidate, mayorId, Duration.ofHours(24));
            execute(gate, "UPDATE towns SET status='ARCHIVED' WHERE town_id=x'"
                    + townId.toString().replace("-", "") + "'");
            assertThrows(GovernanceRepository.ConflictException.class,
                    () -> governance.decideMayorTransfer(archivedTransfer.id(),
                            archivedCandidate, true));
            assertEquals(MemberRole.MAYOR, governance.memberRole(townId, mayorId));
        }
    }

    private static Object runAfter(CountDownLatch start, java.util.concurrent.Callable<?> action)
            throws Exception {
        start.await();
        try {
            return action.call();
        } catch (GovernanceRepository.ConflictException ignored) {
            return null;
        }
    }

    private static CreatedTown createTown(TownRepository repository) {
        UUID mayorId = UUID.randomUUID();
        UUID initialMemberOne = UUID.randomUUID();
        UUID initialMemberTwo = UUID.randomUUID();
        ApplicationText text = new ApplicationText("治理测试镇", "GOV",
                "测试简介", List.of("初始规则"));
        ApplicationSnapshot draft = repository.createDraft(mayorId, text,
                List.of(initialMemberOne, initialMemberTwo), Duration.ZERO);
        repository.respondInitialMember(draft.id(), initialMemberOne, draft.initialMembers().getFirst().invitationToken(), true);
        repository.respondInitialMember(draft.id(), initialMemberTwo, draft.initialMembers().getFirst().invitationToken(), true);
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(UUID.randomUUID(),
                "world", 100, 100));
        ApplicationSnapshot selected = repository.selectSite(draft.id(), mayorId, territory,
                Instant.now().plus(Duration.ofHours(1)), 1);
        ApplicationSnapshot submitted = repository.submit(selected.id(), mayorId);
        TownRepository.Provisioning provisioning = repository.beginProvision(submitted.id(),
                UUID.randomUUID(), "Admin", "审核通过", "governance:test:" + submitted.id(), 200_000);
        repository.finishProvision(submitted.id(), true, "ok");
        return new CreatedTown(repository.findTown(provisioning.town().id()).orElseThrow(), mayorId);
    }

    private static void dropTrigger(DatabaseGate gate, String triggerName) throws Exception {
        execute(gate, "DROP TRIGGER " + triggerName);
    }

    private static void renameTown(DatabaseGate gate, UUID townId, String name) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE towns SET name = ? WHERE town_id = ?")) {
            statement.setString(1, name);
            statement.setBytes(2, uuid(townId));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static long scalarForUuid(DatabaseGate gate, String sql, UUID value) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuid(value));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static void setLongForUuid(DatabaseGate gate, String sql, long number, UUID value)
            throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, number);
            statement.setBytes(2, uuid(value));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private record CreatedTown(TownSnapshot town, UUID mayorId) {
    }
}

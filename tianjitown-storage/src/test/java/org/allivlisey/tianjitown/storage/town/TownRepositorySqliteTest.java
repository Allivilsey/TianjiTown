package org.allivlisey.tianjitown.storage.town;

import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectionPersistsAndInvalidatesTheWholeRoundUntilReselection() throws Exception {
        DatabaseConfig config = new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("invitations.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        UUID applicant = UUID.randomUUID(), one = UUID.randomUUID(), two = UUID.randomUUID();
        UUID applicationId, token;
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 0, 0));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var draft = repository.createDraft(applicant, applicationText("邀请镇", "invite"),
                    List.of(one, two), Duration.ZERO);
            applicationId = draft.id(); token = draft.initialMembers().getFirst().invitationToken();
            repository.selectSite(applicationId, applicant, territory, Instant.now().plusSeconds(3600), 0);
            repository.respondInitialMember(applicationId, one, token, false);
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.respondInitialMember(applicationId, two, token, true));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.respondInitialMember(applicationId, two, token, false));
        }
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            var repository = new TownRepository(gate.dataSource(), () -> false);
            var rejected = repository.findApplication(applicationId).orElseThrow();
            assertTrue(rejected.needsInitialMemberReselection());
            var selected = repository.updateApplicationText(applicationId, applicant, rejected.text(),
                    List.of(one, two), rejected.version());
            assertFalse(selected.needsInitialMemberReselection());
            assertEquals(territory, selected.territory());
            assertTrue(selected.initialMembers().stream().allMatch(member -> member.status() == InitialMemberConfirmation.Status.PENDING));
            UUID newToken = selected.initialMembers().getFirst().invitationToken();
            assertFalse(token.equals(newToken));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.respondInitialMember(applicationId, one, token, true));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> repository.respondInitialMember(applicationId, one, newToken, true));
                var second = pool.submit(() -> repository.respondInitialMember(applicationId, two, newToken, true));
                first.get(); second.get();
            }
            assertTrue(repository.findApplication(applicationId).orElseThrow().initialMembersConfirmed());
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.respondInitialMember(applicationId, one, newToken, false));
        }
    }

    @Test
    void statusFiltersAndCompletedProvisionDoNotDependOnReleasedReservation() throws Exception {
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("status-provision.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            var repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown town = createTown(repository, 0, "amu", "amu");
            UUID applicationId;
            try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT application_id FROM town_applications")) {
                assertTrue(row.next()); applicationId = TownSqlValues.uuid(row.getBytes(1));
            }
            var application = repository.findApplication(applicationId).orElseThrow();
            assertTrue(application.territory() == null);
            var existing = repository.beginProvision(applicationId, UUID.randomUUID(), "Admin", "管理员调整", "reentry", 200000);
            assertEquals(town.town().id(), existing.town().id());
            assertEquals(town.town().territory(), existing.town().territory());
            assertEquals(1, repository.listTowns(TownStatus.ACTIVE).size());
            assertTrue(repository.listTowns(TownStatus.ARCHIVED).isEmpty());
            assertTrue(repository.listTowns(TownStatus.PROVISIONING).isEmpty());
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM ledger_entries WHERE entry_type='APPLICATION_FEE'"));
            repository.deleteTown(town.town().id(), null, "Admin", "管理员调整");
            assertTrue(repository.listTowns(TownStatus.ACTIVE).isEmpty());
            assertEquals(1, repository.listTowns(TownStatus.ARCHIVED).size());
            assertThrows(TownRepository.ConflictException.class, () -> repository.beginProvision(
                    applicationId, UUID.randomUUID(), "Admin", "管理员调整", "archived-reentry", 200000));
        }
    }

    @Test
    void reservesUnactivatedGridAndReleasesItOnlyAfterDeletionCompletes() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("reserved-grid.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown town = createTown(repository, 0, "预留镇", "RESGRID");
            UUID applicant = UUID.randomUUID();
            ApplicationSnapshot draft = repository.createDraft(applicant,
                    applicationText("邻居镇", "NEIGHBOR"),
                    List.of(UUID.randomUUID(), UUID.randomUUID()), Duration.ZERO);
            UUID world = town.town().territory().center().worldId();
            InitialTerritory overlapping = new InitialTerritory(new ChunkPosition(world, "world", 24, 0));
            assertEquals(1, repository.listReservedTowns().size());
            // The initial areas do not touch; the outermost reserved columns overlap.
            assertThrows(TownRepository.ConflictException.class, () -> repository.selectSite(
                    draft.id(), applicant, overlapping, Instant.now().plusSeconds(600), 0));
            repository.selectSite(draft.id(), applicant,
                    new InitialTerritory(new ChunkPosition(world, "world", 25, 0)),
                    Instant.now().plusSeconds(600), 0);
            repository.deleteTown(town.town().id(), UUID.randomUUID(), "Admin", "删除测试");
            assertEquals(1, repository.listReservedTowns().size());
            assertThrows(TownRepository.ConflictException.class, () -> repository.selectSite(
                    draft.id(), applicant, overlapping, Instant.now().plusSeconds(600), 0));
            repository.completeTownDeletion(town.town().id(), UUID.randomUUID(), "Admin", "清理完成");
            assertTrue(repository.listReservedTowns().isEmpty());
            repository.selectSite(draft.id(), applicant, overlapping, Instant.now().plusSeconds(600), 0);
        }
    }

    @Test
    void completesApplicationAndJoinApplicationLifecycle() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("lifecycle.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            UUID applicantId = UUID.randomUUID();
            UUID reviewerId = UUID.randomUUID();
            UUID memberId = UUID.randomUUID();
            UUID initialMemberOne = UUID.randomUUID();
            UUID initialMemberTwo = UUID.randomUUID();
            ApplicationText text = new ApplicationText(
                    "天际镇", "SKY", "测试简介", List.of("友善交流"));
            ApplicationSnapshot draft = repository.createDraft(
                    applicantId, text, List.of(initialMemberOne, initialMemberTwo),
                    Duration.ofMinutes(5));
            assertEquals(List.of(draft.id()), repository
                    .listPendingInitialMemberApplications(initialMemberOne).stream()
                    .map(ApplicationSnapshot::id).toList());
            assertThrows(TownRepository.ConflictException.class, () -> repository.createDraft(
                    UUID.randomUUID(), text, List.of(UUID.randomUUID(), UUID.randomUUID()),
                    Duration.ofMinutes(5)));
            InitialTerritory territory = new InitialTerritory(
                    new ChunkPosition(UUID.randomUUID(), "world", 10, 20));
            ApplicationSnapshot selected = repository.selectSite(
                    draft.id(), applicantId, territory, Instant.now().plus(Duration.ofHours(1)), 1);
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.submit(selected.id(), applicantId));
            repository.respondInitialMember(selected.id(), initialMemberOne, selected.initialMembers().getFirst().invitationToken(), true);
            repository.respondInitialMember(selected.id(), initialMemberTwo, selected.initialMembers().getFirst().invitationToken(), true);
            assertTrue(repository.listPendingInitialMemberApplications(initialMemberOne)
                    .isEmpty());
            ApplicationSnapshot submitted = repository.submit(selected.id(), applicantId);
            assertEquals(ApplicationStatus.SUBMITTED, submitted.status());

            TownRepository.Provisioning provisioning = repository.beginProvision(
                    submitted.id(), reviewerId, "Admin", "审核通过", "test:approve", 200_000);
            assertEquals(TownStatus.PROVISIONING, provisioning.town().status());
            TownRepository.Provisioning repeatedProvisioning = repository.beginProvision(
                    submitted.id(), reviewerId, "Admin", "重复批准", "test:approve", 200_000);
            assertEquals(provisioning.town().id(), repeatedProvisioning.town().id());
            ApplicationSnapshot active = repository.finishProvision(submitted.id(), true, "ok");
            assertEquals(ApplicationStatus.ACTIVE, active.status());
            assertEquals(provisioning.town().id(), repository.findTownByCode("sKy").orElseThrow().id());
            assertTrue(repository.findTownByCode("天际镇").isEmpty());
            assertTrue(repository.findTownByCode("missing").isEmpty());
            assertEquals(active, repository.finishProvision(submitted.id(), true, "重复完成"));

            JoinApplicationSnapshot joinApplication = repository.applyToTown(
                    provisioning.town().id(), memberId, Duration.ofHours(48),
                    Duration.ofHours(24), Duration.ofHours(24), 3);
            repository.approveJoinApplication(joinApplication.id(), applicantId);
            assertEquals(4, repository.listMemberIds(provisioning.town().id()).size());
            assertEquals(200_000, accountBalance(gate, provisioning.town().id()));
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key = 'application-fee:" + submitted.id() + "'"));
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM audit_logs "
                    + "WHERE idempotency_key = 'test:approve'"));
            assertTrue(repository.auditLog(20).size() >= 5);
        }
    }

    @Test
    void recoversFailedProvisionAndReusesEscrowedFeeIdempotently() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("provision-recovery.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            UUID applicantId = UUID.randomUUID();
            UUID firstMember = UUID.randomUUID();
            UUID secondMember = UUID.randomUUID();
            UUID reviewerId = UUID.randomUUID();
            ApplicationText text = applicationText("恢复测试镇", "RECOVER");
            ApplicationSnapshot draft = repository.createDraft(applicantId, text,
                    List.of(firstMember, secondMember), Duration.ZERO);
            repository.respondInitialMember(draft.id(), firstMember, draft.initialMembers().getFirst().invitationToken(), true);
            repository.respondInitialMember(draft.id(), secondMember, draft.initialMembers().getFirst().invitationToken(), true);
            ApplicationSnapshot selected = repository.selectSite(draft.id(), applicantId,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 30, 40)),
                    Instant.now().plus(Duration.ofHours(1)), 1);
            ApplicationSnapshot submitted = repository.submit(selected.id(), applicantId);

            TownRepository.Provisioning firstProvision = repository.beginProvision(
                    submitted.id(), reviewerId, "审核员", "首次批准", "recovery:first",
                    200_000, "申请人");
            ApplicationSnapshot failed = repository.finishProvision(submitted.id(), false,
                    "Residence 投影失败");
            assertEquals(ApplicationStatus.PROVISION_FAILED, failed.status());
            assertEquals(ApplicationSnapshot.FeeStatus.ESCROWED,
                    failed.applicationFeeStatus());
            assertEquals(TownStatus.PROVISIONING,
                    repository.findTown(firstProvision.town().id()).orElseThrow().status());
            assertEquals(firstProvision.town().id(), repository.failedProvision(submitted.id())
                    .town().id());

            ApplicationSnapshot unlocked = repository.recoverFailedProvision(submitted.id(),
                    reviewerId, "审核员", "要求修改资料", TownRepository.RecoveryMode
                            .UNLOCK_FOR_CHANGES);
            assertEquals(ApplicationStatus.NEED_CHANGES, unlocked.status());
            assertEquals(null, unlocked.townId());
            assertEquals(ApplicationSnapshot.FeeStatus.ESCROWED,
                    unlocked.applicationFeeStatus());
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM towns"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM town_accounts"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM territory_units"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM ledger_entries"));

            ApplicationSnapshot selectedAgain = repository.selectSite(unlocked.id(), applicantId,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 50, 60)),
                    Instant.now().plus(Duration.ofHours(1)), 1);
            ApplicationSnapshot submittedAgain = repository.submit(selectedAgain.id(), applicantId);
            TownRepository.Provisioning secondProvision = repository.beginProvision(
                    submittedAgain.id(), reviewerId, "审核员", "重新批准", "recovery:second",
                    200_000, "申请人");
            repository.finishProvision(submittedAgain.id(), false, "再次投影失败");
            assertEquals(TownStatus.PROVISIONING,
                    repository.findTown(secondProvision.town().id()).orElseThrow().status());

            ApplicationSnapshot cancelled = repository.recoverFailedProvision(
                    submittedAgain.id(), reviewerId, "审核员", "取消并退款",
                    TownRepository.RecoveryMode.CANCEL_AND_REFUND);
            assertEquals(ApplicationStatus.CANCELLED, cancelled.status());
            assertEquals(ApplicationSnapshot.FeeStatus.REFUND_PENDING,
                    cancelled.applicationFeeStatus());
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM towns"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM territory_units"));

            ApplicationSnapshot refunded = repository.completeApplicationFeeRefund(
                    cancelled.id(), reviewerId, "审核员", "Vault 已退款");
            assertEquals(ApplicationSnapshot.FeeStatus.REFUNDED,
                    refunded.applicationFeeStatus());
            assertEquals(refunded, repository.completeApplicationFeeRefund(
                    cancelled.id(), reviewerId, "审核员", "重复确认退款"));
            assertEquals(3, scalar(gate, "SELECT COUNT(*) FROM audit_logs "
                    + "WHERE action IN ('PROVISION_UNLOCK_FOR_CHANGES', "
                    + "'PROVISION_CANCEL_AND_REFUND', 'APPLICATION_FEE_REFUND')"));
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM audit_logs "
                    + "WHERE action = 'APPLICATION_FEE_REFUND'"));
        }
    }

    @Test
    void enforcesJoinApplicationLimitsAndCooldowns() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("join-rules.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown first = createTown(repository, 1, "甲镇", "AAA");
            CreatedTown second = createTown(repository, 2, "乙镇", "BBB");
            CreatedTown third = createTown(repository, 3, "丙镇", "CCC");
            CreatedTown fourth = createTown(repository, 4, "丁镇", "DDD");
            UUID playerId = UUID.randomUUID();

            JoinApplicationSnapshot rejected = apply(repository, first.town().id(), playerId);
            apply(repository, second.town().id(), playerId);
            apply(repository, third.town().id(), playerId);
            assertThrows(TownRepository.ConflictException.class,
                    () -> apply(repository, fourth.town().id(), playerId));

            repository.rejectJoinApplication(rejected.id(), first.mayorId());
            assertThrows(TownRepository.ConflictException.class,
                    () -> apply(repository, first.town().id(), playerId));

            UUID leavingPlayer = UUID.randomUUID();
            JoinApplicationSnapshot approved = apply(repository, first.town().id(), leavingPlayer);
            repository.approveJoinApplication(approved.id(), first.mayorId());
            repository.leaveTown(leavingPlayer);
            assertThrows(TownRepository.ConflictException.class,
                    () -> apply(repository, second.town().id(), leavingPlayer));
        }
    }

    @Test
    void activeTownWinsWhenADeletedNameIsReused() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("name-reuse.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown deleted = createTown(repository, 1, "复用镇", "OLD");
            UUID adminId = UUID.randomUUID();
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.disbandTown(deleted.town().id(), adminId,
                            deleted.town().version()));
            repository.listMemberIds(deleted.town().id()).stream()
                    .filter(playerId -> !playerId.equals(deleted.mayorId()))
                    .forEach(playerId -> repository.removeMember(deleted.town().id(), playerId,
                            deleted.mayorId(), "Mayor", "解散测试清理成员"));
            repository.disbandTown(deleted.town().id(), deleted.mayorId(),
                    deleted.town().version());
            repository.completeTownDeletion(deleted.town().id(), adminId, "Admin", "测试删除");

            CreatedTown active = createTown(repository, 2, "复用镇", "OLD");
            assertEquals(active.town().id(), repository.findTownByName("复用镇").orElseThrow().id());
            assertEquals(active.town().id(), repository.listTowns(true).getFirst().id());
        }
    }

    @Test
    void onlyTownLeadersCanUpdateEditableTownProfileFields() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("profile-permissions.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown created = createTown(repository, 1, "资料镇", "PROFILE");
            TownSnapshot original = created.town();
            try (var connection = gate.dataSource().getConnection();
                var statement = connection.prepareStatement(
                         "SELECT residence_name FROM town_applications WHERE town_id = ?")) {
                statement.setBytes(1, java.nio.ByteBuffer.allocate(16)
                        .putLong(original.id().getMostSignificantBits())
                        .putLong(original.id().getLeastSignificantBits()).array());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("profile", rows.getString("residence_name"));
                }
            }
            UUID memberId = UUID.randomUUID();
            repository.addMember(original.id(), memberId, created.mayorId(), "Admin", "测试成员");
            ApplicationText changed = new ApplicationText(original.profile().name(), original.profile().residenceName(),
                    "更新后的简介", List.of("更新后的规则"));
            ApplicationText lockedFieldsChanged = new ApplicationText("资料新镇",
                    "renamed", "更新后的简介", List.of("更新后的规则"));

            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.updateTownProfile(original.id(), changed,
                            original.version(), UUID.randomUUID(), "Outsider", "越权修改"));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.updateTownProfile(original.id(), changed,
                            original.version(), memberId, "Member", "越权修改"));
            TownSnapshot unchanged = repository.findTown(original.id()).orElseThrow();
            assertEquals(original.profile(), unchanged.profile());
            assertEquals(original.version(), unchanged.version());

            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(),
                    () -> false);
            governance.changeRoleByMayor(original.id(), memberId, MemberRole.DEPUTY_MAYOR,
                    created.mayorId(), "Mayor");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.updateTownProfile(original.id(), lockedFieldsChanged,
                            original.version(), memberId, "Deputy", "修改锁定字段"));
            TownSnapshot updated = repository.updateTownProfile(original.id(), changed,
                    original.version(), memberId, "Deputy", "副镇长修改资料");
            assertEquals(changed, updated.profile());
        }
    }

    @Test
    void enforcesOneDayCooldownAfterCancellationOrRejection() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("application-cooldown.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            Duration cooldown = Duration.ofDays(1);

            UUID cancelledApplicant = UUID.randomUUID();
            ApplicationSnapshot cancelled = repository.createDraft(cancelledApplicant,
                    applicationText("撤回镇", "CANCELLED"),
                    List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown);
            repository.cancel(cancelled.id(), cancelledApplicant, "玩家撤回测试");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.createDraft(cancelledApplicant,
                            applicationText("撤回后", "CANEXT"),
                            List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown));

            UUID rejectedApplicant = UUID.randomUUID();
            UUID firstMember = UUID.randomUUID();
            UUID secondMember = UUID.randomUUID();
            ApplicationSnapshot rejected = repository.createDraft(rejectedApplicant,
                    applicationText("拒绝镇", "REJECTED"),
                    List.of(firstMember, secondMember), cooldown);
            repository.respondInitialMember(rejected.id(), firstMember, rejected.initialMembers().getFirst().invitationToken(), true);
            repository.respondInitialMember(rejected.id(), secondMember, rejected.initialMembers().getFirst().invitationToken(), true);
            rejected = repository.selectSite(rejected.id(), rejectedApplicant,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 50, 50)),
                    Instant.now().plus(Duration.ofHours(1)), 1);
            rejected = repository.submit(rejected.id(), rejectedApplicant);
            repository.reject(rejected.id(), UUID.randomUUID(), "Admin", "管理员拒绝测试");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.createDraft(rejectedApplicant,
                            applicationText("拒绝后", "REJNEXT"),
                            List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown));
        }
    }

    @Test
    void bindsSqlMetacharactersWithoutChangingDatabaseStructure() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("sql-injection.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            String payload = "'; DROP TABLE towns; --";
            ApplicationText unsafeName = new ApplicationText(payload, "SQLSAFE",
                    "简介", List.of("规则"));
            assertThrows(IllegalArgumentException.class, unsafeName::requireValid);

            UUID applicantId = UUID.randomUUID();
            UUID firstMember = UUID.randomUUID();
            UUID secondMember = UUID.randomUUID();
            ApplicationText text = new ApplicationText("注入测试镇", "SQLSAFE",
                    payload, List.of(payload));
            text.requireValid();
            ApplicationSnapshot draft = repository.createDraft(applicantId, text,
                    List.of(firstMember, secondMember), Duration.ZERO);
            repository.respondInitialMember(draft.id(), firstMember, draft.initialMembers().getFirst().invitationToken(), true);
            repository.respondInitialMember(draft.id(), secondMember, draft.initialMembers().getFirst().invitationToken(), true);
            ApplicationSnapshot selected = repository.selectSite(draft.id(), applicantId,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 80, 80)),
                    Instant.now().plus(Duration.ofHours(1)), 1);
            ApplicationSnapshot submitted = repository.submit(selected.id(), applicantId);
            String idempotencyKey = "approval:" + payload;
            TownRepository.Provisioning provisioning = repository.beginProvision(submitted.id(),
                    UUID.randomUUID(), payload, payload, idempotencyKey, 200_000);
            repository.finishProvision(submitted.id(), true, payload);
            TownSnapshot town = repository.findTown(provisioning.town().id()).orElseThrow();
            assertEquals(payload, town.profile().description());
            assertEquals(List.of(payload), town.profile().rules());

            try (var connection = gate.dataSource().getConnection();
                 var tables = connection.prepareStatement("""
                         SELECT COUNT(*) FROM sqlite_master
                          WHERE type = 'table' AND name IN ('towns', 'audit_logs', 'ledger_entries')
                         """);
                 var audit = connection.prepareStatement("""
                         SELECT actor_name, reason FROM audit_logs WHERE idempotency_key = ?
                         """)) {
                try (var rows = tables.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(3, rows.getInt(1));
                }
                audit.setString(1, idempotencyKey);
                try (var rows = audit.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(payload, rows.getString("actor_name"));
                    assertEquals(payload, rows.getString("reason"));
                }
            }
        }
    }

    @Test
    void enforcesNormalizedApplicationNamesAcrossConcurrentRepositoryInstances() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("normalized-concurrency.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate firstGate = new DatabaseGate(config);
             DatabaseGate secondGate = new DatabaseGate(config)) {
            assertTrue(firstGate.verifyAndMigrate().healthy());
            assertTrue(secondGate.verifyAndMigrate().healthy());
            TownRepository first = new TownRepository(firstGate.dataSource(), () -> false);
            TownRepository second = new TownRepository(secondGate.dataSource(), () -> false);

            assertSingleConcurrentWinner(
                    () -> createDraft(first, applicationText("并 发 镇", "NORMONE")),
                    () -> createDraft(second, applicationText(" 并发镇 ", "NORMTWO")));
            assertSingleConcurrentWinner(
                    () -> createDraft(first, applicationText("领地甲镇", "MiXeD")),
                    () -> createDraft(second, applicationText("领地乙镇", "mixed")));
            assertSingleConcurrentWinner(
                    () -> createDraft(first, applicationText("Å镇", "NORMFIVE")),
                    () -> createDraft(second, applicationText("A\u030A镇", "NORMSIX")));

            List<Attempt> compatibilityAttempts = runConcurrently(
                    () -> createDraft(first, applicationText("ATown", "HALFWIDTH")),
                    () -> createDraft(second, applicationText("ＡTown", "FULLWIDTH")));
            assertEquals(2, compatibilityAttempts.stream().filter(Attempt::succeeded).count());
        }
    }

    @Test
    void rollsBackApplicationJoinAndDisbandWhenLateAuditWriteFails() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("town-rollback.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);

            UUID applicantId = UUID.randomUUID();
            UUID firstMember = UUID.randomUUID();
            UUID secondMember = UUID.randomUUID();
            ApplicationSnapshot draft = repository.createDraft(applicantId,
                    applicationText("事务申请镇", "ROLLAPP"),
                    List.of(firstMember, secondMember), Duration.ZERO);
            repository.respondInitialMember(draft.id(), firstMember, draft.initialMembers().getFirst().invitationToken(), true);
            repository.respondInitialMember(draft.id(), secondMember, draft.initialMembers().getFirst().invitationToken(), true);
            ApplicationSnapshot selected = repository.selectSite(draft.id(), applicantId,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 100, 100)),
                    Instant.now().plus(Duration.ofHours(1)), 1);
            ApplicationSnapshot submitted = repository.submit(selected.id(), applicantId);
            installAuditFailure(gate, "fail_application_approval", "APPLICATION_APPROVE");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.beginProvision(submitted.id(), UUID.randomUUID(), "Admin",
                            "注入失败", "rollback:application", 200_000));
            ApplicationSnapshot applicationAfter = repository.findApplication(submitted.id())
                    .orElseThrow();
            assertEquals(ApplicationStatus.SUBMITTED, applicationAfter.status());
            assertEquals(null, applicationAfter.townId());
            assertTrue(repository.findTownByName("事务申请镇").isEmpty());
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key = 'rollback:application'"));
            dropTrigger(gate, "fail_application_approval");

            CreatedTown joinTown = createTown(repository, 20, "事务入镇", "ROLLJOIN");
            UUID joiningPlayer = UUID.randomUUID();
            JoinApplicationSnapshot join = apply(repository, joinTown.town().id(), joiningPlayer);
            installAuditFailure(gate, "fail_join_approval", "MEMBER_APPLICATION_APPROVE");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.approveJoinApplication(join.id(), joinTown.mayorId()));
            assertEquals("PENDING", repository.listJoinApplications(joiningPlayer).getFirst()
                    .status().name());
            assertFalse(repository.listMemberIds(joinTown.town().id()).contains(joiningPlayer));
            dropTrigger(gate, "fail_join_approval");

            CreatedTown disbandTown = createTown(repository, 21, "事务解散", "ROLLDIS");
            repository.listMemberIds(disbandTown.town().id()).stream()
                    .filter(playerId -> !playerId.equals(disbandTown.mayorId()))
                    .forEach(playerId -> repository.removeMember(disbandTown.town().id(), playerId,
                            disbandTown.mayorId(), "Mayor", "解散事务测试"));
            TownSnapshot beforeDisband = repository.findTown(disbandTown.town().id()).orElseThrow();
            installAuditFailure(gate, "fail_disband", "TOWN_DISBAND_PREPARE");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.disbandTown(beforeDisband.id(), disbandTown.mayorId(),
                            beforeDisband.version()));
            TownSnapshot afterDisband = repository.findTown(beforeDisband.id()).orElseThrow();
            assertEquals(TownStatus.ACTIVE, afterDisband.status());
            assertEquals(List.of(disbandTown.mayorId()), repository.listMemberIds(beforeDisband.id()));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM town_archived_members"));
        }
    }

    @Test
    void enforcesReservationAndMembershipCooldownBoundaries() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("time-boundaries.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);

            UUID applicantId = UUID.randomUUID();
            UUID firstMember = UUID.randomUUID();
            UUID secondMember = UUID.randomUUID();
            ApplicationSnapshot draft = repository.createDraft(applicantId,
                    applicationText("预留边界镇", "RESTIME"),
                    List.of(firstMember, secondMember), Duration.ZERO);
            repository.respondInitialMember(draft.id(), firstMember, draft.initialMembers().getFirst().invitationToken(), true);
            repository.respondInitialMember(draft.id(), secondMember, draft.initialMembers().getFirst().invitationToken(), true);
            ApplicationSnapshot selected = repository.selectSite(draft.id(), applicantId,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 70, 70)),
                    Instant.now().plusSeconds(60), 1);
            assertEquals(ApplicationStatus.SUBMITTED,
                    repository.submit(selected.id(), applicantId).status());

            UUID expiredApplicant = UUID.randomUUID();
            UUID expiredFirst = UUID.randomUUID();
            UUID expiredSecond = UUID.randomUUID();
            ApplicationSnapshot expiredDraft = repository.createDraft(expiredApplicant,
                    applicationText("过期预留镇", "RESEXPIRE"),
                    List.of(expiredFirst, expiredSecond), Duration.ZERO);
            repository.respondInitialMember(expiredDraft.id(), expiredFirst, expiredDraft.initialMembers().getFirst().invitationToken(), true);
            repository.respondInitialMember(expiredDraft.id(), expiredSecond, expiredDraft.initialMembers().getFirst().invitationToken(), true);
            repository.selectSite(expiredDraft.id(), expiredApplicant,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 80, 80)),
                    Instant.now().plusSeconds(60), 1);
            execute(gate, "UPDATE site_reservations SET expires_at = "
                    + (Instant.now().toEpochMilli() - 1) + " WHERE application_id = x'"
                    + expiredDraft.id().toString().replace("-", "") + "'");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.submit(expiredDraft.id(), expiredApplicant));

            CreatedTown rejectionTown = createTown(repository, 30,
                    "拒绝冷却镇", "REJECTCD");
            UUID rejectedPlayer = UUID.randomUUID();
            JoinApplicationSnapshot rejected = repository.applyToTown(rejectionTown.town().id(),
                    rejectedPlayer, Duration.ofHours(48), Duration.ofHours(24), Duration.ZERO, 3);
            repository.rejectJoinApplication(rejected.id(), rejectionTown.mayorId());
            execute(gate, "UPDATE town_join_applications SET decided_at = "
                    + (Instant.now().minus(Duration.ofHours(24)).toEpochMilli() - 1)
                    + " WHERE join_application_id = x'"
                    + rejected.id().toString().replace("-", "") + "'");
            assertEquals("PENDING", repository.applyToTown(rejectionTown.town().id(),
                    rejectedPlayer, Duration.ofHours(48), Duration.ofHours(24), Duration.ZERO, 3)
                    .status().name());

            CreatedTown departureTown = createTown(repository, 31,
                    "离镇冷却镇", "LEAVECD");
            UUID leavingPlayer = UUID.randomUUID();
            JoinApplicationSnapshot approved = repository.applyToTown(departureTown.town().id(),
                    leavingPlayer, Duration.ofHours(48), Duration.ZERO, Duration.ofHours(24), 3);
            repository.approveJoinApplication(approved.id(), departureTown.mayorId());
            repository.leaveTown(leavingPlayer);
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.applyToTown(rejectionTown.town().id(), leavingPlayer,
                            Duration.ofHours(48), Duration.ZERO, Duration.ofHours(24), 3));
            execute(gate, "UPDATE town_member_departures SET departed_at = "
                    + (Instant.now().minus(Duration.ofHours(24)).toEpochMilli() - 1)
                    + " WHERE player_uuid = x'" + leavingPlayer.toString().replace("-", "")
                    + "'");
            assertEquals("PENDING", repository.applyToTown(rejectionTown.town().id(),
                    leavingPlayer, Duration.ofHours(48), Duration.ZERO, Duration.ofHours(24), 3)
                    .status().name());
        }
    }

    @Test
    void appliesConfiguredBufferAroundFiveByFiveTownUnits() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("site-buffer.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown existing = createTown(repository, 40,
                    "缓冲基准镇", "BUFBASE");
            ApplicationSnapshot draft = createDraft(repository,
                    applicationText("缓冲候选镇", "BUFCAND"));
            ChunkPosition origin = existing.town().territory().center();
            InitialTerritory adjacent = new InitialTerritory(new ChunkPosition(
                    origin.worldId(), origin.worldName(), origin.x() + 5, origin.z()));

            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.selectSite(draft.id(), draft.applicantId(), adjacent,
                            Instant.now().plus(Duration.ofHours(1)), 1));
        }
    }

    @Test
    void managesVisitorsWithoutChangingTownMembership() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("visitors.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(
                    gate.dataSource(), () -> false);
            CreatedTown host = createTown(repository, 50,
                    "访客接待镇", "VISITORS");
            CreatedTown neighboring = createTown(repository, 51,
                    "访客来源镇", "VISOURCE");
            String renamedTown = "改名后的访客接待镇";
            renameTown(gate, host.town().id(), renamedTown);
            List<UUID> hostMembers = repository.listMemberIds(host.town().id());
            UUID deputy = hostMembers.stream()
                    .filter(playerId -> !playerId.equals(host.mayorId()))
                    .findFirst().orElseThrow();
            UUID regularMember = hostMembers.stream()
                    .filter(playerId -> !playerId.equals(host.mayorId()))
                    .filter(playerId -> !playerId.equals(deputy))
                    .findFirst().orElseThrow();
            governance.changeRoleByMayor(host.town().id(), deputy, MemberRole.DEPUTY_MAYOR,
                    host.mayorId(), "Mayor");

            TownPlayerChange added = repository.addVisitorWithTownName(host.town().id(),
                    neighboring.mayorId(), deputy, "Deputy");
            assertEquals(host.town().id(), added.townId());
            assertEquals(neighboring.mayorId(), added.playerId());
            assertEquals(renamedTown, added.townName());
            assertEquals(List.of(neighboring.mayorId()),
                    repository.listVisitorIds(host.town().id()));
            assertFalse(repository.listMemberIds(host.town().id())
                    .contains(neighboring.mayorId()));
            assertTrue(repository.listLandAccessIds(host.town().id())
                    .contains(neighboring.mayorId()));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.addVisitor(host.town().id(), regularMember,
                            host.mayorId(), "Mayor"));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.addVisitor(host.town().id(), neighboring.mayorId(),
                            host.mayorId(), "Mayor"));
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.removeVisitor(host.town().id(), neighboring.mayorId(),
                            regularMember, "Member"));

            TownPlayerChange removed = repository.removeVisitorWithTownName(
                    host.town().id(), neighboring.mayorId(), deputy, "Deputy");
            assertEquals(host.town().id(), removed.townId());
            assertEquals(neighboring.mayorId(), removed.playerId());
            assertEquals(renamedTown, removed.townName());
            assertTrue(repository.listVisitorIds(host.town().id()).isEmpty());
            assertFalse(repository.listLandAccessIds(host.town().id())
                    .contains(neighboring.mayorId()));

            UUID joiningVisitor = UUID.randomUUID();
            repository.addVisitor(host.town().id(), joiningVisitor,
                    host.mayorId(), "Mayor");
            repository.addMember(host.town().id(), joiningVisitor,
                    host.mayorId(), "Mayor", "访客正式入镇");
            assertFalse(repository.listVisitorIds(host.town().id()).contains(joiningVisitor));
            assertTrue(repository.listMemberIds(host.town().id()).contains(joiningVisitor));
            assertEquals(1L, repository.listLandAccessIds(host.town().id()).stream()
                    .filter(joiningVisitor::equals).count());
            TownSnapshot.VisitorPage visitorPage = repository.listVisitors(
                    host.town().id(), 0, 1);
            assertTrue(visitorPage.visitors().isEmpty());
            assertFalse(visitorPage.hasNext());
        }
    }

    private static JoinApplicationSnapshot apply(TownRepository repository, UUID townId,
                                                  UUID playerId) {
        return repository.applyToTown(townId, playerId, Duration.ofHours(48),
                Duration.ofHours(24), Duration.ofHours(24), 3);
    }

    private static ApplicationSnapshot createDraft(TownRepository repository,
                                                    ApplicationText text) {
        return repository.createDraft(UUID.randomUUID(), text,
                List.of(UUID.randomUUID(), UUID.randomUUID()), Duration.ZERO);
    }

    private static void assertSingleConcurrentWinner(Supplier<ApplicationSnapshot> first,
                                                     Supplier<ApplicationSnapshot> second)
            throws Exception {
        List<Attempt> attempts = runConcurrently(first, second);
        assertEquals(1, attempts.stream().filter(Attempt::succeeded).count());
        assertEquals(1, attempts.stream()
                .filter(attempt -> attempt.failure() instanceof TownRepository.ConflictException)
                .count());
    }

    private static List<Attempt> runConcurrently(Supplier<ApplicationSnapshot> first,
                                                 Supplier<ApplicationSnapshot> second)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var firstFuture = executor.submit(() -> attempt(first, ready, start));
            var secondFuture = executor.submit(() -> attempt(second, ready, start));
            ready.await();
            start.countDown();
            return List.of(firstFuture.get(), secondFuture.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Attempt attempt(Supplier<ApplicationSnapshot> action, CountDownLatch ready,
                                   CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new Attempt(action.get(), null);
        } catch (RuntimeException exception) {
            return new Attempt(null, exception);
        }
    }

    private static void installAuditFailure(DatabaseGate gate, String triggerName, String action)
            throws Exception {
        execute(gate, "CREATE TRIGGER " + triggerName + " BEFORE INSERT ON audit_logs "
                + "WHEN NEW.action = '" + action + "' BEGIN "
                + "SELECT RAISE(ABORT, 'injected audit failure'); END");
    }

    private static void dropTrigger(DatabaseGate gate, String triggerName) throws Exception {
        execute(gate, "DROP TRIGGER " + triggerName);
    }

    private static void execute(DatabaseGate gate, String sql) throws Exception {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void renameTown(DatabaseGate gate, UUID townId, String name) throws Exception {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE towns SET name = ? WHERE town_id = ?")) {
            statement.setString(1, name);
            statement.setBytes(2, java.nio.ByteBuffer.allocate(16)
                    .putLong(townId.getMostSignificantBits())
                    .putLong(townId.getLeastSignificantBits()).array());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static long scalar(DatabaseGate gate, String sql) throws Exception {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static ApplicationText applicationText(String name,
                                                   String residenceName) {
        return new ApplicationText(name, residenceName,
                "测试简介", List.of("友善交流"));
    }

    private static CreatedTown createTown(TownRepository repository, int index, String name, String residenceName) {
        UUID mayorId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID initialMemberOne = UUID.randomUUID();
        UUID initialMemberTwo = UUID.randomUUID();
        ApplicationText text = new ApplicationText(name, residenceName,
                "测试简介", List.of("友善交流"));
        ApplicationSnapshot draft = repository.createDraft(mayorId, text,
                List.of(initialMemberOne, initialMemberTwo), Duration.ZERO);
        repository.respondInitialMember(draft.id(), initialMemberOne, draft.initialMembers().getFirst().invitationToken(), true);
        repository.respondInitialMember(draft.id(), initialMemberTwo, draft.initialMembers().getFirst().invitationToken(), true);
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                UUID.fromString("00000000-0000-0000-0000-000000000999"),
                "world", index * 30, index * 30));
        ApplicationSnapshot selected = repository.selectSite(draft.id(), mayorId, territory,
                Instant.now().plus(Duration.ofHours(1)), 1);
        ApplicationSnapshot submitted = repository.submit(selected.id(), mayorId);
        TownRepository.Provisioning provisioning = repository.beginProvision(submitted.id(),
                reviewerId, "Admin", "审核通过", "test:approve:" + submitted.id(), 200_000);
        repository.finishProvision(submitted.id(), true, "ok");
        return new CreatedTown(repository.findTown(provisioning.town().id()).orElseThrow(), mayorId);
    }

    private record CreatedTown(TownSnapshot town, UUID mayorId) {
    }

    private record Attempt(ApplicationSnapshot snapshot, RuntimeException failure) {
        boolean succeeded() {
            return snapshot != null;
        }
    }

    private static long accountBalance(DatabaseGate gate, UUID townId) {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.prepareStatement(
                     "SELECT balance_minor FROM town_accounts WHERE town_id = ?")) {
            statement.setBytes(1, java.nio.ByteBuffer.allocate(16)
                    .putLong(townId.getMostSignificantBits())
                    .putLong(townId.getLeastSignificantBits()).array());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

package cn.tianji.town.storage.town;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.governance.GovernanceRepository;
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
                    "天际镇", "TJ", "SKY", "测试简介", List.of("友善交流"));
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
            repository.respondInitialMember(selected.id(), initialMemberOne, true);
            repository.respondInitialMember(selected.id(), initialMemberTwo, true);
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
    void enforcesJoinApplicationLimitsAndCooldowns() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("join-rules.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown first = createTown(repository, 1, "甲镇", "甲", "AAA");
            CreatedTown second = createTown(repository, 2, "乙镇", "乙", "BBB");
            CreatedTown third = createTown(repository, 3, "丙镇", "丙", "CCC");
            CreatedTown fourth = createTown(repository, 4, "丁镇", "丁", "DDD");
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
            CreatedTown deleted = createTown(repository, 1, "复用镇", "复", "OLD");
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

            CreatedTown active = createTown(repository, 2, "复用镇", "复", "OLD");
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
            CreatedTown created = createTown(repository, 1, "资料镇", "资料", "PROFILE");
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
            ApplicationText changed = new ApplicationText(original.profile().name(),
                    original.profile().shortName(), original.profile().residenceName(),
                    "更新后的简介", List.of("更新后的规则"));
            ApplicationText lockedFieldsChanged = new ApplicationText("资料新镇", "新资料",
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
                    applicationText("撤回镇", "撤回", "CANCELLED"),
                    List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown);
            repository.cancel(cancelled.id(), cancelledApplicant, "玩家撤回测试");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.createDraft(cancelledApplicant,
                            applicationText("撤回后", "撤后", "CANCELNEXT"),
                            List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown));

            UUID rejectedApplicant = UUID.randomUUID();
            UUID firstMember = UUID.randomUUID();
            UUID secondMember = UUID.randomUUID();
            ApplicationSnapshot rejected = repository.createDraft(rejectedApplicant,
                    applicationText("拒绝镇", "拒绝", "REJECTED"),
                    List.of(firstMember, secondMember), cooldown);
            repository.respondInitialMember(rejected.id(), firstMember, true);
            repository.respondInitialMember(rejected.id(), secondMember, true);
            rejected = repository.selectSite(rejected.id(), rejectedApplicant,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 50, 50)),
                    Instant.now().plus(Duration.ofHours(1)), 1);
            rejected = repository.submit(rejected.id(), rejectedApplicant);
            repository.reject(rejected.id(), UUID.randomUUID(), "Admin", "管理员拒绝测试");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.createDraft(rejectedApplicant,
                            applicationText("拒绝后", "拒后", "REJECTNEXT"),
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
            ApplicationText unsafeName = new ApplicationText(payload, "SQL", "SQLSAFE",
                    "简介", List.of("规则"));
            assertThrows(IllegalArgumentException.class, unsafeName::requireValid);

            UUID applicantId = UUID.randomUUID();
            UUID firstMember = UUID.randomUUID();
            UUID secondMember = UUID.randomUUID();
            ApplicationText text = new ApplicationText("注入测试镇", "注入", "SQLSAFE",
                    payload, List.of(payload));
            text.requireValid();
            ApplicationSnapshot draft = repository.createDraft(applicantId, text,
                    List.of(firstMember, secondMember), Duration.ZERO);
            repository.respondInitialMember(draft.id(), firstMember, true);
            repository.respondInitialMember(draft.id(), secondMember, true);
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
                    () -> createDraft(first, applicationText("并 发 镇", "并发甲", "NORMONE")),
                    () -> createDraft(second, applicationText(" 并发镇 ", "并发乙", "NORMTWO")));
            assertSingleConcurrentWinner(
                    () -> createDraft(first, applicationText("简称甲镇", "S P A C", "NORMTHREE")),
                    () -> createDraft(second, applicationText("简称乙镇", " spac ", "NORMFOUR")));
            assertSingleConcurrentWinner(
                    () -> createDraft(first, applicationText("领地甲镇", "领地甲", "MiXeD")),
                    () -> createDraft(second, applicationText("领地乙镇", "领地乙", "mixed")));
            assertSingleConcurrentWinner(
                    () -> createDraft(first, applicationText("Å镇", "NFC甲", "NORMFIVE")),
                    () -> createDraft(second, applicationText("A\u030A镇", "NFC乙", "NORMSIX")));

            List<Attempt> compatibilityAttempts = runConcurrently(
                    () -> createDraft(first, applicationText("ATown", "半角", "HALFWIDTH")),
                    () -> createDraft(second, applicationText("ＡTown", "全角", "FULLWIDTH")));
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
                    applicationText("事务申请镇", "事务申", "ROLLAPP"),
                    List.of(firstMember, secondMember), Duration.ZERO);
            repository.respondInitialMember(draft.id(), firstMember, true);
            repository.respondInitialMember(draft.id(), secondMember, true);
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

            CreatedTown joinTown = createTown(repository, 20, "事务入镇", "事务入", "ROLLJOIN");
            UUID joiningPlayer = UUID.randomUUID();
            JoinApplicationSnapshot join = apply(repository, joinTown.town().id(), joiningPlayer);
            installAuditFailure(gate, "fail_join_approval", "MEMBER_APPLICATION_APPROVE");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.approveJoinApplication(join.id(), joinTown.mayorId()));
            assertEquals("PENDING", repository.listJoinApplications(joiningPlayer).getFirst()
                    .status().name());
            assertFalse(repository.listMemberIds(joinTown.town().id()).contains(joiningPlayer));
            dropTrigger(gate, "fail_join_approval");

            CreatedTown disbandTown = createTown(repository, 21, "事务解散", "事务散", "ROLLDISBAND");
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
                    applicationText("预留边界镇", "预留界", "RESTIME"),
                    List.of(firstMember, secondMember), Duration.ZERO);
            repository.respondInitialMember(draft.id(), firstMember, true);
            repository.respondInitialMember(draft.id(), secondMember, true);
            ApplicationSnapshot selected = repository.selectSite(draft.id(), applicantId,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 70, 70)),
                    Instant.now().plusSeconds(60), 1);
            assertEquals(ApplicationStatus.SUBMITTED,
                    repository.submit(selected.id(), applicantId).status());

            UUID expiredApplicant = UUID.randomUUID();
            UUID expiredFirst = UUID.randomUUID();
            UUID expiredSecond = UUID.randomUUID();
            ApplicationSnapshot expiredDraft = repository.createDraft(expiredApplicant,
                    applicationText("过期预留镇", "过期界", "RESEXPIRE"),
                    List.of(expiredFirst, expiredSecond), Duration.ZERO);
            repository.respondInitialMember(expiredDraft.id(), expiredFirst, true);
            repository.respondInitialMember(expiredDraft.id(), expiredSecond, true);
            repository.selectSite(expiredDraft.id(), expiredApplicant,
                    new InitialTerritory(new ChunkPosition(UUID.randomUUID(), "world", 80, 80)),
                    Instant.now().plusSeconds(60), 1);
            execute(gate, "UPDATE site_reservations SET expires_at = "
                    + (Instant.now().toEpochMilli() - 1) + " WHERE application_id = x'"
                    + expiredDraft.id().toString().replace("-", "") + "'");
            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.submit(expiredDraft.id(), expiredApplicant));

            CreatedTown rejectionTown = createTown(repository, 30,
                    "拒绝冷却镇", "拒却镇", "REJECTCD");
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
                    "离镇冷却镇", "离却镇", "LEAVECD");
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
    void archivesMissingProjectionOnceAndKeepsReuseLocks() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("missing-projection.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            CreatedTown created = createTown(repository, 32,
                    "投影缺失镇", "缺失镇", "MISSPROJ");

            assertTrue(repository.archiveTownForMissingProjection(created.town().id(),
                    "Residence 不存在"));
            assertFalse(repository.archiveTownForMissingProjection(created.town().id(),
                    "重复对账"));
            TownSnapshot archived = repository.findTown(created.town().id()).orElseThrow();
            assertEquals(TownStatus.ARCHIVED, archived.status());
            assertTrue(repository.listMemberIds(created.town().id()).isEmpty());
            assertEquals(3, scalar(gate, "SELECT COUNT(*) FROM town_archived_members"));
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM territory_units WHERE town_id=x'"
                    + created.town().id().toString().replace("-", "")
                    + "' AND reuse_blocked=1"));
            assertEquals(1, scalar(gate, "SELECT COUNT(*) FROM audit_logs "
                    + "WHERE action='TOWN_SAFETY_ARCHIVE'"));
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
                    "缓冲基准镇", "基准镇", "BUFFERBASE");
            ApplicationSnapshot draft = createDraft(repository,
                    applicationText("缓冲候选镇", "候选镇", "BUFCAND"));
            ChunkPosition origin = existing.town().territory().center();
            InitialTerritory adjacent = new InitialTerritory(new ChunkPosition(
                    origin.worldId(), origin.worldName(), origin.x() + 5, origin.z()));

            assertThrows(TownRepository.ConflictException.class,
                    () -> repository.selectSite(draft.id(), draft.applicantId(), adjacent,
                            Instant.now().plus(Duration.ofHours(1)), 1));
        }
    }

    @Test
    void managesVisitorsWithoutChangingTownMembership() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("visitors.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            GovernanceRepository governance = new GovernanceRepository(
                    gate.dataSource(), () -> false);
            CreatedTown host = createTown(repository, 50,
                    "访客接待镇", "接待镇", "VISITORS");
            CreatedTown neighboring = createTown(repository, 51,
                    "访客来源镇", "来源镇", "VISOURCE");
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

            TownSnapshot.Visitor visitor = repository.addVisitor(host.town().id(),
                    neighboring.mayorId(), deputy, "Deputy");
            assertEquals(neighboring.mayorId(), visitor.playerId());
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

            repository.removeVisitor(host.town().id(), neighboring.mayorId(), deputy, "Deputy");
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

    private static long scalar(DatabaseGate gate, String sql) throws Exception {
        try (var connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static ApplicationText applicationText(String name, String shortName,
                                                   String residenceName) {
        return new ApplicationText(name, shortName, residenceName,
                "测试简介", List.of("友善交流"));
    }

    private static CreatedTown createTown(TownRepository repository, int index, String name,
                                          String shortName, String residenceName) {
        UUID mayorId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID initialMemberOne = UUID.randomUUID();
        UUID initialMemberTwo = UUID.randomUUID();
        ApplicationText text = new ApplicationText(name, shortName, residenceName,
                "测试简介", List.of("友善交流"));
        ApplicationSnapshot draft = repository.createDraft(mayorId, text,
                List.of(initialMemberOne, initialMemberTwo), Duration.ZERO);
        repository.respondInitialMember(draft.id(), initialMemberOne, true);
        repository.respondInitialMember(draft.id(), initialMemberTwo, true);
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                UUID.fromString("00000000-0000-0000-0000-000000000999"),
                "world", index * 10, index * 10));
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

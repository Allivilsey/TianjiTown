package cn.tianji.town.storage.phase1;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.phase2.GovernanceRepository;
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
    void completesApplicationAndJoinApplicationLifecycle() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("lifecycle.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            PhaseOneRepository repository = new PhaseOneRepository(gate.dataSource(), () -> false);
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
            assertThrows(PhaseOneRepository.ConflictException.class, () -> repository.createDraft(
                    UUID.randomUUID(), text, List.of(UUID.randomUUID(), UUID.randomUUID()),
                    Duration.ofMinutes(5)));
            InitialTerritory territory = new InitialTerritory(
                    new ChunkPosition(UUID.randomUUID(), "world", 10, 20));
            ApplicationSnapshot selected = repository.selectSite(
                    draft.id(), applicantId, territory, Instant.now().plus(Duration.ofHours(1)), 1);
            assertThrows(PhaseOneRepository.ConflictException.class,
                    () -> repository.submit(selected.id(), applicantId));
            repository.respondInitialMember(selected.id(), initialMemberOne, true);
            repository.respondInitialMember(selected.id(), initialMemberTwo, true);
            ApplicationSnapshot submitted = repository.submit(selected.id(), applicantId);
            assertEquals(ApplicationStatus.SUBMITTED, submitted.status());

            PhaseOneRepository.Provisioning provisioning = repository.beginProvision(
                    submitted.id(), reviewerId, "Admin", "审核通过", "test:approve", 200_000);
            assertEquals(TownStatus.PROVISIONING, provisioning.town().status());
            ApplicationSnapshot active = repository.finishProvision(submitted.id(), true, "ok");
            assertEquals(ApplicationStatus.ACTIVE, active.status());

            JoinApplicationSnapshot joinApplication = repository.applyToTown(
                    provisioning.town().id(), memberId, Duration.ofHours(48),
                    Duration.ofHours(24), Duration.ofHours(24), 3);
            repository.approveJoinApplication(joinApplication.id(), applicantId);
            assertEquals(4, repository.listMemberIds(provisioning.town().id()).size());
            assertEquals(200_000, accountBalance(gate, provisioning.town().id()));
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
            PhaseOneRepository repository = new PhaseOneRepository(gate.dataSource(), () -> false);
            CreatedTown first = createTown(repository, 1, "甲镇", "甲", "AAA");
            CreatedTown second = createTown(repository, 2, "乙镇", "乙", "BBB");
            CreatedTown third = createTown(repository, 3, "丙镇", "丙", "CCC");
            CreatedTown fourth = createTown(repository, 4, "丁镇", "丁", "DDD");
            UUID playerId = UUID.randomUUID();

            JoinApplicationSnapshot rejected = apply(repository, first.town().id(), playerId);
            apply(repository, second.town().id(), playerId);
            apply(repository, third.town().id(), playerId);
            assertThrows(PhaseOneRepository.ConflictException.class,
                    () -> apply(repository, fourth.town().id(), playerId));

            repository.rejectJoinApplication(rejected.id(), first.mayorId());
            assertThrows(PhaseOneRepository.ConflictException.class,
                    () -> apply(repository, first.town().id(), playerId));

            UUID leavingPlayer = UUID.randomUUID();
            JoinApplicationSnapshot approved = apply(repository, first.town().id(), leavingPlayer);
            repository.approveJoinApplication(approved.id(), first.mayorId());
            repository.leaveTown(leavingPlayer);
            assertThrows(PhaseOneRepository.ConflictException.class,
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
            PhaseOneRepository repository = new PhaseOneRepository(gate.dataSource(), () -> false);
            CreatedTown deleted = createTown(repository, 1, "复用镇", "复", "OLD");
            UUID adminId = UUID.randomUUID();
            assertThrows(PhaseOneRepository.ConflictException.class,
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
    void onlyTownLeadersCanUpdateTownProfile() {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("profile-permissions.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            PhaseOneRepository repository = new PhaseOneRepository(gate.dataSource(), () -> false);
            CreatedTown created = createTown(repository, 1, "资料镇", "资料", "PROFILE");
            TownSnapshot original = created.town();
            UUID memberId = UUID.randomUUID();
            repository.addMember(original.id(), memberId, created.mayorId(), "Admin", "测试成员");
            ApplicationText changed = new ApplicationText("资料新镇", "新资料", "profile",
                    "更新后的简介", List.of("更新后的规则"));

            assertThrows(PhaseOneRepository.ConflictException.class,
                    () -> repository.updateTownProfile(original.id(), changed,
                            original.version(), UUID.randomUUID(), "Outsider", "越权修改"));
            assertThrows(PhaseOneRepository.ConflictException.class,
                    () -> repository.updateTownProfile(original.id(), changed,
                            original.version(), memberId, "Member", "越权修改"));
            TownSnapshot unchanged = repository.findTown(original.id()).orElseThrow();
            assertEquals(original.profile(), unchanged.profile());
            assertEquals(original.version(), unchanged.version());

            GovernanceRepository governance = new GovernanceRepository(gate.dataSource(),
                    () -> false);
            governance.changeRoleByMayor(original.id(), memberId, MemberRole.DEPUTY_MAYOR,
                    created.mayorId(), "Mayor");
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
            PhaseOneRepository repository = new PhaseOneRepository(gate.dataSource(), () -> false);
            Duration cooldown = Duration.ofDays(1);

            UUID cancelledApplicant = UUID.randomUUID();
            ApplicationSnapshot cancelled = repository.createDraft(cancelledApplicant,
                    applicationText("撤回镇", "撤回", "CANCELLED"),
                    List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown);
            repository.cancel(cancelled.id(), cancelledApplicant, "玩家撤回测试");
            assertThrows(PhaseOneRepository.ConflictException.class,
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
            assertThrows(PhaseOneRepository.ConflictException.class,
                    () -> repository.createDraft(rejectedApplicant,
                            applicationText("拒绝后", "拒后", "REJECTNEXT"),
                            List.of(UUID.randomUUID(), UUID.randomUUID()), cooldown));
        }
    }

    private static JoinApplicationSnapshot apply(PhaseOneRepository repository, UUID townId,
                                                  UUID playerId) {
        return repository.applyToTown(townId, playerId, Duration.ofHours(48),
                Duration.ofHours(24), Duration.ofHours(24), 3);
    }

    private static ApplicationText applicationText(String name, String shortName,
                                                   String residenceName) {
        return new ApplicationText(name, shortName, residenceName,
                "测试简介", List.of("友善交流"));
    }

    private static CreatedTown createTown(PhaseOneRepository repository, int index, String name,
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
        PhaseOneRepository.Provisioning provisioning = repository.beginProvision(submitted.id(),
                reviewerId, "Admin", "审核通过", "test:approve:" + submitted.id(), 200_000);
        repository.finishProvision(submitted.id(), true, "ok");
        return new CreatedTown(repository.findTown(provisioning.town().id()).orElseThrow(), mayorId);
    }

    private record CreatedTown(TownSnapshot town, UUID mayorId) {
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

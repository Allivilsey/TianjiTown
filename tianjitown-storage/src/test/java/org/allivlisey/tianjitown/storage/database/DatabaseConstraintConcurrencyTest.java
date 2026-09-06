package org.allivlisey.tianjitown.storage.database;

import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConstraintConcurrencyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void serializesCrossRepositoryUniquenessAndKeepsLedgerIdempotent() throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("constraint-concurrency.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate firstGate = new DatabaseGate(config);
             DatabaseGate secondGate = new DatabaseGate(config)) {
            assertTrue(firstGate.verifyAndMigrate().healthy());
            assertTrue(secondGate.verifyAndMigrate().healthy());
            UUID firstTown = UUID.randomUUID();
            UUID firstMayor = UUID.randomUUID();
            UUID secondTown = UUID.randomUUID();
            UUID secondMayor = UUID.randomUUID();
            insertTown(firstGate, firstTown, firstMayor, "约束甲镇", "约甲", "DBA");
            insertTown(firstGate, secondTown, secondMayor, "约束乙镇", "约乙", "DBB");

            TownRepository firstTowns = new TownRepository(firstGate.dataSource(), () -> false);
            TownRepository secondTowns = new TownRepository(secondGate.dataSource(), () -> false);
            UUID sharedPlayer = UUID.randomUUID();
            List<Attempt> membershipAttempts = runConcurrently(
                    () -> addMember(firstTowns, firstTown, sharedPlayer, firstMayor),
                    () -> addMember(secondTowns, secondTown, sharedPlayer, secondMayor));
            assertSingleWinner(membershipAttempts, TownRepository.ConflictException.class);
            assertEquals(1, scalar(firstGate, "SELECT COUNT(*) FROM town_members "
                    + "WHERE player_uuid = x'" + hex(sharedPlayer) + "'"));

            UUID mayorCandidate = UUID.randomUUID();
            firstTowns.addMember(firstTown, mayorCandidate, firstMayor, "Admin", "唯一镇长测试");
            assertThrows(SQLException.class, () -> execute(firstGate,
                    "UPDATE town_members SET role = 'MAYOR' WHERE player_uuid = x'"
                            + hex(mayorCandidate) + "'"));
            assertEquals(1, scalar(firstGate, "SELECT COUNT(*) FROM town_members "
                    + "WHERE town_id = x'" + hex(firstTown) + "' AND role = 'MAYOR'"));

            GovernanceRepository firstGovernance = new GovernanceRepository(
                    firstGate.dataSource(), () -> false);
            GovernanceRepository secondGovernance = new GovernanceRepository(
                    secondGate.dataSource(), () -> false);
            UUID firstDeputy = addMember(firstTowns, firstTown, UUID.randomUUID(), firstMayor);
            UUID secondDeputy = addMember(firstTowns, firstTown, UUID.randomUUID(), firstMayor);
            UUID thirdCandidate = addMember(firstTowns, firstTown, UUID.randomUUID(), firstMayor);
            UUID fourthCandidate = addMember(firstTowns, firstTown, UUID.randomUUID(), firstMayor);
            firstGovernance.changeRoleByMayor(firstTown, firstDeputy,
                    MemberRole.DEPUTY_MAYOR, firstMayor, "Mayor");
            firstGovernance.changeRoleByMayor(firstTown, secondDeputy,
                    MemberRole.DEPUTY_MAYOR, firstMayor, "Mayor");
            List<Attempt> deputyAttempts = runConcurrently(
                    () -> changeRole(firstGovernance, firstTown, thirdCandidate, firstMayor),
                    () -> changeRole(secondGovernance, firstTown, fourthCandidate, firstMayor));
            assertSingleWinner(deputyAttempts, GovernanceRepository.ConflictException.class);
            assertEquals(3, scalar(firstGate, "SELECT COUNT(*) FROM town_members "
                    + "WHERE town_id = x'" + hex(firstTown)
                    + "' AND role = 'DEPUTY_MAYOR'"));

            UUID firstTarget = addMember(firstTowns, firstTown, UUID.randomUUID(), firstMayor);
            UUID secondTarget = addMember(firstTowns, firstTown, UUID.randomUUID(), firstMayor);
            for (UUID playerId : firstTowns.listMemberIds(firstTown)) {
                firstGovernance.recordActivity(playerId);
            }
            List<Attempt> voteAttempts = runConcurrently(
                    () -> createVote(firstGovernance, firstTown, firstTarget, firstMayor),
                    () -> createVote(secondGovernance, firstTown, secondTarget, firstMayor));
            assertSingleWinner(voteAttempts, GovernanceRepository.ConflictException.class);
            assertEquals(1, scalar(firstGate, "SELECT COUNT(*) FROM governance_votes "
                    + "WHERE town_id = x'" + hex(firstTown) + "' AND status = 'OPEN'"));

            EconomyRepository firstEconomy = new EconomyRepository(firstGate.dataSource(),
                    () -> false);
            EconomyRepository secondEconomy = new EconomyRepository(secondGate.dataSource(),
                    () -> false);
            firstEconomy.initializeAccounts();
            EconomyRepository.ExternalIncomeTax tax = new EconomyRepository.ExternalIncomeTax(
                    firstTown, "concurrent:tax", "JOBS", firstMayor, "Mayor",
                    1_000, 500, 50);
            firstEconomy.reserveTaxSubsidy(firstTown, tax.businessKey(), tax.taxMinor(),
                    1000, 1000, java.time.Instant.now(), java.time.ZoneId.of("Asia/Shanghai"));
            List<Attempt> taxAttempts = runConcurrently(
                    () -> firstEconomy.recordExternalIncomeTax(tax),
                    () -> secondEconomy.recordExternalIncomeTax(tax));
            assertEquals(2, taxAttempts.stream().filter(Attempt::succeeded).count());
            assertEquals(taxAttempts.get(0).result(), taxAttempts.get(1).result());
            assertEquals(1, scalar(firstGate, "SELECT COUNT(*) FROM "
                    + "external_income_tax_records WHERE business_key = 'concurrent:tax'"));
            assertEquals(2, scalar(firstGate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key LIKE 'concurrent:tax%'"));
            assertEquals(100, firstEconomy.findFinanceByTown(firstTown).orElseThrow()
                    .balanceMinor());
        }
    }

    private static UUID addMember(TownRepository repository, UUID townId, UUID playerId,
                                  UUID mayorId) {
        repository.addMember(townId, playerId, mayorId, "Admin", "并发约束测试");
        return playerId;
    }

    private static MemberRole changeRole(GovernanceRepository repository, UUID townId,
                                         UUID playerId, UUID mayorId) {
        return repository.changeRoleByMayor(townId, playerId, MemberRole.DEPUTY_MAYOR,
                mayorId, "Mayor");
    }

    private static Object createVote(GovernanceRepository repository, UUID townId,
                                     UUID targetId, UUID mayorId) {
        return repository.createVote(townId, VoteType.KICK_MEMBER, targetId, mayorId,
                Duration.ofDays(30), Duration.ZERO, Duration.ofHours(72), false);
    }

    private static void assertSingleWinner(List<Attempt> attempts,
                                           Class<? extends RuntimeException> failureType) {
        assertEquals(1, attempts.stream().filter(Attempt::succeeded).count(), attempts.toString());
        RuntimeException failure = attempts.stream().map(Attempt::failure)
                .filter(exception -> exception != null).findFirst().orElseThrow();
        assertInstanceOf(failureType, failure);
    }

    private static List<Attempt> runConcurrently(Supplier<?> first, Supplier<?> second)
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

    private static Attempt attempt(Supplier<?> action, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return new Attempt(action.get(), null);
        } catch (RuntimeException exception) {
            return new Attempt(null, exception);
        }
    }

    private static void insertTown(DatabaseGate gate, UUID townId, UUID mayorId, String name,
                                   String shortName, String residenceName) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, ?, ?, ?, ?, '约束测试', '规则', 'ACTIVE', ?)
                     """);
             var mayor = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role)
                     VALUES (?, ?, 'MAYOR')
                     """);
             var unit = connection.prepareStatement("""
                     INSERT INTO territory_units
                         (unit_id, town_id, world_uuid, world_name, grid_x, grid_z,
                          center_chunk_x, center_chunk_z, residence_name, residence_area_name,
                          projection_status)
                     VALUES (?, ?, ?, 'world', 0, 0, 0, 0, ?, 'main', 'ACTIVE')
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setString(2, name);
            town.setString(3, name);
            town.setString(4, shortName);
            town.setString(5, shortName);
            town.setBytes(6, uuid(mayorId));
            town.executeUpdate();
            mayor.setBytes(1, uuid(townId));
            mayor.setBytes(2, uuid(mayorId));
            mayor.executeUpdate();
            unit.setBytes(1, uuid(UUID.randomUUID()));
            unit.setBytes(2, uuid(townId));
            unit.setBytes(3, uuid(UUID.randomUUID()));
            unit.setString(4, residenceName);
            unit.executeUpdate();
        }
    }

    private static void execute(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long scalar(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String hex(UUID value) {
        return java.util.HexFormat.of().formatHex(uuid(value));
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private record Attempt(Object result, RuntimeException failure) {
        boolean succeeded() {
            return failure == null;
        }
    }
}

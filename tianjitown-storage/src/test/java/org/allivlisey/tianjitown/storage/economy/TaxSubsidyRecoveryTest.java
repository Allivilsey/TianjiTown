package org.allivlisey.tianjitown.storage.economy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TaxSubsidyRecoveryTest {
    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleConfirmationCannotApplyAfterTaxOrErrorChanges(boolean taxAlreadyRecorded) throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("stale.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            repo.initializeAccounts();
            repo.reserveTaxSubsidy(town, "jobs:stale", 100, 10_000, 10_000,
                    Instant.now(), ZoneId.of("Asia/Shanghai"));
            var tax = new EconomyRepository.ExternalIncomeTax(town, "jobs:stale", "JOBS", mayor,
                    "Mayor", 2000, 500, 100);
            if (taxAlreadyRecorded) repo.recordExternalIncomeTaxWithoutSubsidy(tax, "first failure detail");
            var stale = repo.pendingTaxSubsidies(10).getFirst();
            repo.recordExternalIncomeTaxWithoutSubsidy(tax, "updated failure detail");
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repo.resolveTaxSubsidy(stale, true, mayor, "Admin", "old confirmation"));
            assertEquals(100, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
            var current = repo.pendingTaxSubsidies(10).getFirst();
            repo.resolveTaxSubsidy(current, true, mayor, "Admin", "freshly verified payment");
            assertEquals(200, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repo.resolveTaxSubsidy(current, true, mayor, "Admin", "already consumed confirmation"));
        }
    }

    @Test
    void recoveredTaxCanBeRecordedBeforeAnySubsidyWasReserved() throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("no-reservation.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            repo.initializeAccounts();
            var tax = new EconomyRepository.ExternalIncomeTax(town, "jobs:before-subsidy", "JOBS", mayor,
                    "Mayor", 2000, 500, 100);
            repo.recordExternalIncomeTaxWithoutSubsidy(tax, "startup recovery");
            repo.recordExternalIncomeTaxWithoutSubsidy(tax, "startup recovery");
            assertEquals(100, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
            assertEquals(1, repo.ledger(town, 0, 45).size());
            assertTrue(repo.pendingTaxSubsidies(10).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void collectedTaxSurvivesUncertainSubsidyAndCanBeReconciledAfterReopen(boolean paid) throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("tax.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            repo.initializeAccounts();
            repo.reserveTaxSubsidy(town, "jobs:recovery", 100, 10_000, 10_000,
                    Instant.now(), ZoneId.of("Asia/Shanghai"));
            var tax = new EconomyRepository.ExternalIncomeTax(town, "jobs:recovery", "JOBS", mayor,
                    "Mayor", 2000, 500, 100);
            repo.recordExternalIncomeTaxWithoutSubsidy(tax, "lost Vault reply");
            repo.recordExternalIncomeTaxWithoutSubsidy(tax, "lost Vault reply");
            assertEquals(100, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
            var reopened = new EconomyRepository(gate.dataSource(), () -> false);
            var pending = reopened.pendingTaxSubsidies(10);
            assertEquals(1, pending.size());
            assertTrue(pending.getFirst().taxRecorded());
            assertEquals("lost Vault reply", pending.getFirst().lastError());
            var result = reopened.resolveTaxSubsidy("jobs:recovery", paid, mayor, "Admin", "核实经济流水");
            assertEquals(paid ? 200 : 100, result.balanceAfterMinor());
            assertEquals(result, reopened.resolveTaxSubsidy("jobs:recovery", paid, mayor, "Admin", "重复确认"));
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> reopened.resolveTaxSubsidy("jobs:recovery", !paid, mayor, "Admin", "旧页面错误确认"));
            assertTrue(reopened.pendingTaxSubsidies(10).isEmpty());
            assertEquals(paid ? 2 : 1, reopened.ledger(town, 0, 45).size());
        }
    }

    @Test
    void reservationStillInFlightCannotBeManuallyApplied() throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("flight.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            repo.initializeAccounts();
            repo.reserveTaxSubsidy(town, "jobs:flight", 100, 10_000, 10_000,
                    Instant.now(), ZoneId.of("Asia/Shanghai"));
            assertFalse(repo.pendingTaxSubsidies(10).getFirst().taxRecorded());
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repo.resolveTaxSubsidy("jobs:flight", true, mayor, "Admin", "still running"));
            assertEquals(0, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
        }
    }
}

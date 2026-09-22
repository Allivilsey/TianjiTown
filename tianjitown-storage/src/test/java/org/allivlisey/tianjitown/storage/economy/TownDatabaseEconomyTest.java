package org.allivlisey.tianjitown.storage.economy;

import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TownDatabaseEconomyTest {
    @TempDir Path directory;
    private final UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-21T00:00:00Z");
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");

    @Test void allSourcesShareQuotaAndRetryDoesNotGrantAgain() throws Exception {
        try (var gate = database()) {
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            var qs = quickshop();
            assertEquals(200, repo.recordQuickShopTax(qs, 150, 150, now, zone).balanceAfterMinor());
            var jobs = tax("JOBS");
            assertEquals(350, repo.recordExternalIncomeTax(jobs, 150, 150, now, zone).balanceAfterMinor());
            assertEquals(450, repo.recordExternalIncomeTax(tax("GLOBALMARKETPLUS"), 150, 150, now, zone).balanceAfterMinor());
            repo.recordQuickShopTax(qs, 10000, 10000, now.plusSeconds(604800), zone);
            repo.recordExternalIncomeTax(jobs, 10000, 10000, now.plusSeconds(604800), zone);
            assertEquals(450, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
            assertEquals(5, repo.ledger(town, 0, 45).size());
            assertTrue(repo.pendingTaxSubsidies(10).isEmpty());
            assertEquals(0, repo.taxSubsidyQuota(town, 150, 150, now, zone).weeklyRemainingMinor());
        }
    }

    @Test void failedSubsidyLedgerRollsBackTaxBalanceAndQuotaTogether() throws Exception {
        try (var gate = database()) {
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            try (var connection = gate.dataSource().getConnection(); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER fail_subsidy BEFORE INSERT ON ledger_entries WHEN NEW.entry_type = 'SERVER_TAX_SUBSIDY' BEGIN SELECT RAISE(ABORT, 'injected'); END");
            }
            assertThrows(RuntimeException.class, () -> repo.recordQuickShopTax(quickshop(), 150, 150, now, zone));
            assertEquals(0, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
            assertTrue(repo.ledger(town, 0, 45).isEmpty());
            assertTrue(repo.pendingTaxSubsidies(10).isEmpty());
            assertEquals(150, repo.taxSubsidyQuota(town, 150, 150, now, zone).weeklyRemainingMinor());
            try (var connection = gate.dataSource().getConnection(); var sql = connection.createStatement()) {
                sql.execute("DROP TRIGGER fail_subsidy");
            }
            assertEquals(200, repo.recordQuickShopTax(quickshop(), 150, 150, now, zone).balanceAfterMinor());
        }
    }

    @Test void adjustmentsAreAtomicAuditedAndCannotOverdraw() throws Exception {
        try (var gate = database()) {
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            var added = repo.adjustFunds(town, 500, mayor, "Admin", "adjust:1", "测试");
            assertEquals(added, repo.adjustFunds(town, 500, mayor, "Admin", "adjust:1", "测试"));
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repo.adjustFunds(town, -501, mayor, "Admin", "adjust:2", "测试"));
            assertEquals(400, repo.adjustFunds(town, -100, mayor, "Admin", "adjust:3", "测试").balanceAfterMinor());
            assertTrue(repo.pendingOperations().isEmpty());
            try (var connection = gate.dataSource().getConnection(); var sql = connection.createStatement();
                 var rows = sql.executeQuery("SELECT COUNT(*) FROM audit_logs WHERE action = 'ADMIN_ADJUSTMENT'")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }
    }

    private EconomyRepository.QuickShopTax quickshop() {
        return new EconomyRepository.QuickShopTax(town, "qs:1", 1, "SELLING", mayor,
                UUID.randomUUID(), 2000, 500, 100, "world");
    }
    private EconomyRepository.ExternalIncomeTax tax(String source) {
        return new EconomyRepository.ExternalIncomeTax(town, source + ":1", source, mayor, "Mayor", 2000, 500, 100);
    }
    private DatabaseGate database() throws Exception {
        var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + directory.resolve("town.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertTrue(gate.verifyAndMigrate().healthy());
        EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
        new EconomyRepository(gate.dataSource(), () -> false).initializeAccounts();
        return gate;
    }
}

package org.allivlisey.tianjitown.storage.economy;

import org.allivlisey.tianjitown.core.land.*;
import org.allivlisey.tianjitown.storage.database.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditStorageRegressionTest {
    @TempDir Path temporaryDirectory;

    @Test void batchChildCannotBeRecoveredOrSettledIndividually() throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("batch.db"), Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            repo.initializeAccounts();
            var funding = repo.prepareOperation(town, "DONATION", 10000, mayor, "Mayor", "audit:fund", "audit");
            repo.markOperationExternalApplied(funding.operationId());
            repo.completeOperation(funding.operationId());
            var origin = repo.territoryUnits(town).getFirst().unit();
            var batch = repo.prepareExpansionBatch(new EconomyRepository.ExpansionBatchRequest(town,
                    List.of(new EconomyRepository.ExpansionBatchItem(TerritoryRules.target(List.of(origin), 1, 0), "SKY", "unit_p1_p0", 200)),
                    200, mayor, "Mayor", "audit:batch", 1));
            var standaloneRecovery = repo.pendingExpansions();
            var batchRecovery = repo.pendingExpansionBatches();
            assertTrue(standaloneRecovery.isEmpty());
            assertEquals(1, batchRecovery.size());
            UUID child = batch.expansions().getFirst().expansionId();
            assertThrows(EconomyRepository.ConflictException.class, () -> repo.completeExpansion(child));
            assertThrows(EconomyRepository.ConflictException.class, () -> repo.refundExpansion(child, "failed"));
            repo.refundExpansionBatch(batch.batchId(), "projection failed");
            assertEquals(10000, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"leave", "remove", "mayor"})
    void invalidMembershipReleasesTransferSlot(String change) throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("transfer.db"), Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID(), candidate = UUID.randomUUID(), replacement = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var towns = new org.allivlisey.tianjitown.storage.town.TownRepository(gate.dataSource(), () -> false);
            var governance = new org.allivlisey.tianjitown.storage.governance.GovernanceRepository(gate.dataSource(), () -> false);
            towns.addMember(town, candidate, mayor, "Mayor", "audit candidate");
            towns.addMember(town, replacement, mayor, "Mayor", "audit replacement");
            governance.requestMayorTransfer(town, candidate, mayor, Duration.ofHours(24));
            if (change.equals("mayor")) {
                towns.transferMayor(town, replacement, mayor, "Admin", "transfer");
                assertEquals("PENDING", governance.requestMayorTransfer(town, candidate, replacement, Duration.ofHours(24)).status());
            } else {
                if (change.equals("leave")) towns.leaveTown(candidate);
                else towns.removeMember(town, candidate, mayor, "Admin", "remove");
                assertTrue(governance.dashboard(candidate).isEmpty());
                assertEquals("PENDING", governance.requestMayorTransfer(town, replacement, mayor, Duration.ofHours(24)).status());
            }
            try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT status FROM mayor_transfer_requests WHERE status = 'CANCELLED'")) {
                assertTrue(row.next());
                assertEquals("CANCELLED", row.getString(1));
            }
        }
    }

    @Test void failedOrUncertainSubsidyStillRecordsTaxWithoutInventingSubsidyIncome() throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("tax.db"), Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            repo.initializeAccounts();
            for (boolean ambiguous : List.of(false, true)) {
                String key = "tax:" + ambiguous;
                repo.reserveTaxSubsidy(town, key, 100, 10_000, 10_000, java.time.Instant.now(), java.time.ZoneId.of("Asia/Shanghai"));
                if (!ambiguous) repo.cancelTaxSubsidy(key, "definite failure");
                var tax = new EconomyRepository.QuickShopTax(town, key, 1, "SELLING", mayor,
                        "Mayor", UUID.randomUUID(), 2000, 500, 100, "world");
                repo.recordQuickShopTaxWithoutSubsidy(tax, "verify subsidy");
                repo.recordQuickShopTaxWithoutSubsidy(tax, "verify subsidy");
                try (var connection = gate.dataSource().getConnection();
                     var statement = connection.prepareStatement("SELECT status, last_error FROM quickshop_subsidy_reservations WHERE business_key = ?")) {
                    statement.setString(1, key);
                    try (var row = statement.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(ambiguous ? "RESERVED" : "CANCELLED", row.getString(1));
                        assertEquals("verify subsidy", row.getString(2));
                    }
                }
            }
            assertEquals(200, repo.findFinanceByTown(town).orElseThrow().balanceMinor());
            assertEquals(2, repo.ledger(town, 0, 45).size());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void pendingBridgeCannotSupportAnotherExpansion(boolean firstBatch, boolean secondBatch) throws Exception {
        try (var gate = new DatabaseGate(new DatabaseConfig("jdbc:sqlite:" + temporaryDirectory.resolve("audit.db"), Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID town = UUID.randomUUID(), mayor = UUID.randomUUID();
            EconomyRepositorySqliteTest.insertTown(gate, town, mayor, UUID.randomUUID());
            var repo = new EconomyRepository(gate.dataSource(), () -> false);
            repo.initializeAccounts();
            var funding = repo.prepareOperation(town, "DONATION", 10000, mayor, "Mayor", "audit:funding", "audit");
            repo.markOperationExternalApplied(funding.operationId());
            repo.completeOperation(funding.operationId());
            var origin = repo.territoryUnits(town).getFirst().unit();
            var east = TerritoryRules.target(List.of(origin), 1, 0);
            var first = firstBatch ? repo.prepareExpansionBatch(new EconomyRepository.ExpansionBatchRequest(town,
                    List.of(new EconomyRepository.ExpansionBatchItem(east, "SKY", "unit_p1_p0", 200)), 200, mayor, "Mayor", "audit:first", 1)) : null;
            var single = firstBatch ? null : repo.prepareExpansion(new EconomyRepository.ExpansionRequest(town,
                    east, "SKY", "unit_p1_p0", 200, mayor, "Mayor", "audit:first", 1));
            var outer = TerritoryRules.target(List.of(origin, east), 2, 0);
            if (secondBatch) {
                assertThrows(EconomyRepository.ConflictException.class, () -> repo.prepareExpansionBatch(new EconomyRepository.ExpansionBatchRequest(town,
                        List.of(new EconomyRepository.ExpansionBatchItem(outer, "SKY", "unit_p2_p0", 200)), 200, mayor, "Mayor", "audit:second", 2)));
            } else {
                assertThrows(EconomyRepository.ConflictException.class, () -> repo.prepareExpansion(new EconomyRepository.ExpansionRequest(town,
                        outer, "SKY", "unit_p2_p0", 200, mayor, "Mayor", "audit:second", 2)));
            }
            if (firstBatch) repo.refundExpansionBatch(first.batchId(), "projection failed");
            else repo.refundExpansion(single.expansionId(), "projection failed");
            var remaining = repo.territoryUnits(town);
            assertEquals(1, remaining.size());
            assertTrue(remaining.stream().allMatch(unit -> unit.projectionStatus().equals("ACTIVE")));
            assertDoesNotThrow(() -> TerritoryRules.requireConnected(remaining.stream().map(EconomyRepository.TerritoryUnitSnapshot::unit).toList()));
        }
    }
}

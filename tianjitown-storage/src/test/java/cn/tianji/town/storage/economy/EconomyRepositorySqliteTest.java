package cn.tianji.town.storage.economy;

import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.land.TerritoryRules;
import cn.tianji.town.core.land.TerritoryUnit;
import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsTaxLedgerSettlementAndExpansionIdempotent() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("phase3.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID mayorId = UUID.randomUUID();
            UUID deputyMayorId = UUID.randomUUID();
            UUID worldId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, worldId);
            insertMember(gate, townId, deputyMayorId, "DEPUTY_MAYOR");
            EconomyRepository repository = new EconomyRepository(gate.dataSource(), () -> false);
            repository.initializeAccounts();

            EconomyRepository.QuickShopTax tax = new EconomyRepository.QuickShopTax(
                    townId, "qs:test:1", 42, "SELLING", mayorId, UUID.randomUUID(),
                    10_000, 500, 500, "world");
            assertEquals(1_000, repository.recordQuickShopTax(tax).balanceAfterMinor());
            assertEquals(1_000, repository.recordQuickShopTax(tax).balanceAfterMinor());
            try (Connection connection = gate.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE ledger_entries SET created_at = 0 WHERE business_key = ?")) {
                statement.setString(1, tax.businessKey());
                statement.executeUpdate();
            }
            assertEquals(2, repository.ledger(townId, 0, 45).size());

            EconomyRepository.EconomyOperation donation = repository.prepareOperation(townId,
                    "DONATION", 1_000, mayorId, "Mayor", "donation:test:1", "测试捐款");
            repository.markOperationExternalApplied(donation.operationId());
            assertEquals(2_000, repository.completeOperation(donation.operationId())
                    .balanceAfterMinor());
            assertEquals(2_000, repository.completeOperation(donation.operationId())
                    .balanceAfterMinor());

            EconomyRepository.EconomyOperation cancelled = repository.prepareOperation(townId,
                    "DONATION", 100, mayorId, "Mayor", "donation:cancelled", "取消测试");
            repository.markOperationExternalApplied(cancelled.operationId());
            EconomyRepository.EconomyOperation cancelledResult = repository.cancelOperation(
                    cancelled.operationId(), "Vault 未扣款");
            assertEquals("CANCELLED", cancelledResult.status());
            assertEquals("Vault 未扣款", cancelledResult.lastError());

            EconomyRepository.EconomyOperation unavailable = repository.prepareOperation(townId,
                    "ADMIN_ADJUSTMENT", 100, mayorId, "Mayor", "adjustment:unavailable",
                    "Vault 临时不可用");
            EconomyRepository.EconomyOperation unavailableResult = repository.cancelOperation(
                    unavailable.operationId(), "Vault Economy provider 不可用");
            assertEquals("CANCELLED", unavailableResult.status());
            assertEquals("Vault Economy provider 不可用", unavailableResult.lastError());
            assertTrue(repository.pendingOperations().isEmpty());

            EconomyRepository.Reconciliation shortage = repository.reconcileSettlement(1_999);
            assertFalse(shortage.healthy());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().locked());

            TerritoryUnit origin = repository.territoryUnits(townId).getFirst().unit();
            TerritoryUnit east = TerritoryRules.next(List.of(origin), ExpansionDirection.EAST);
            TerritoryUnit displaced = new TerritoryUnit(1, 0, new InitialTerritory(
                    new ChunkPosition(worldId, "world", 100, 100)));
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.prepareExpansion(new EconomyRepository.ExpansionRequest(
                            townId, displaced, "SKY", "unit_p1_p0", 200, mayorId, "Mayor",
                            "expansion:invalid-grid")));
            EconomyRepository.ExpansionRequest expansion = new EconomyRepository.ExpansionRequest(
                    townId, east, "SKY", "unit_p1_p0", 200, mayorId, "Mayor",
                    "expansion:test:1");
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.prepareExpansion(expansion));

            assertTrue(repository.reconcileSettlement(2_000).healthy());
            assertFalse(repository.findFinanceByTown(townId).orElseThrow().locked());
            EconomyRepository.ExpansionOperation prepared = repository.prepareExpansion(expansion);
            assertEquals(prepared.expansionId(), repository.prepareExpansion(expansion).expansionId());
            assertEquals(1_800, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            repository.refundExpansion(prepared.expansionId(), "Residence 测试失败");
            assertEquals(2_000, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            assertEquals(1, repository.territoryUnits(townId).size());
            assertEquals(5, repository.ledger(townId, 0, 45).size());

            EconomyRepository.ExpansionOperation inner = repository.prepareExpansion(
                    new EconomyRepository.ExpansionRequest(townId, east, "SKY", "unit_p1_p0",
                            200, mayorId, "Mayor", "expansion:grid-inner"));
            TerritoryUnit outerEast = TerritoryRules.next(repository.territoryUnits(townId)
                    .stream().map(EconomyRepository.TerritoryUnitSnapshot::unit).toList(),
                    ExpansionDirection.EAST);
            assertEquals(2, outerEast.gridX());
            EconomyRepository.ExpansionOperation outer = repository.prepareExpansion(
                    new EconomyRepository.ExpansionRequest(townId, outerEast, "SKY", "unit_p2_p0",
                            200, mayorId, "Mayor", "expansion:grid-outer"));
            repository.refundExpansion(outer.expansionId(), "外圈测试回滚");
            repository.refundExpansion(inner.expansionId(), "内圈测试回滚");
            assertEquals(2_000, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());

            assertThrows(IllegalArgumentException.class,
                    () -> repository.changeTaxRate(townId, mayorId,
                            750, "Mayor", "非法税率测试"));
            EconomyRepository.TaxChange changed = repository.changeTaxRate(townId, mayorId,
                    600, "Mayor", "测试 1% 步进税率");
            assertEquals(600, changed.basisPoints());
            assertTrue(changed.revision() > 1);
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.changeTaxRate(townId, deputyMayorId,
                            700, "Deputy", "副镇长越权修改测试"));
            assertEquals(600, repository.findFinanceByTown(townId).orElseThrow().taxRateBps());

            EconomyRepository.EconomyOperation uncertain = repository.prepareOperation(townId,
                    "ADMIN_ADJUSTMENT", 100, mayorId, "Mayor", "adjustment:uncertain",
                    "外部结果未知");
            repository.markOperationExternalApplied(uncertain.operationId());
            EconomyRepository.EconomyOperation compensation = repository.requireCompensation(
                    uncertain.operationId(), "Vault 调用异常");
            assertEquals("COMPENSATION_REQUIRED", compensation.status());
            assertEquals("Vault 调用异常", compensation.lastError());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().locked());
            assertTrue(repository.reconcileSettlement(2_000).healthy());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().locked());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().lockReason()
                    .startsWith("ECONOMY_COMPENSATION:"));

            EconomyRepository.EconomyOperation resolved = repository.resolveCompensation(
                    uncertain.operationId(), "玩家余额已自动恢复");
            assertEquals("CANCELLED", resolved.status());
            assertEquals("玩家余额已自动恢复", resolved.lastError());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().locked());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().lockReason()
                    .startsWith("SETTLEMENT_RECONCILIATION:"));
            assertEquals(resolved, repository.resolveCompensation(uncertain.operationId(),
                    "重复收尾不应改写结果"));
            assertTrue(repository.reconcileSettlement(2_000).healthy());
            assertFalse(repository.findFinanceByTown(townId).orElseThrow().locked());

            EconomyRepository.ExternalIncomeTax jobsTax =
                    new EconomyRepository.ExternalIncomeTax(townId, "jobs:test:1", "JOBS",
                            mayorId, "Mayor", 2_000, 1_000, 200);
            assertEquals(2_400, repository.recordExternalIncomeTax(jobsTax).balanceAfterMinor());
            assertEquals(2_400, repository.recordExternalIncomeTax(jobsTax).balanceAfterMinor());
            List<String> recentEntryTypes = repository.ledger(townId, 0, 45).stream()
                    .map(EconomyRepository.LedgerEntry::entryType)
                    .toList();
            assertTrue(recentEntryTypes.contains("JOBS_TAX"));
            assertTrue(recentEntryTypes.contains("SERVER_TAX_SUBSIDY"));
            EconomyRepository.LedgerEntry displayedTax = repository.displayLedger(
                            townId, 0, 45).stream()
                    .filter(entry -> entry.businessKey().equals(jobsTax.businessKey()))
                    .findFirst().orElseThrow();
            assertEquals("JOBS_TAX", displayedTax.entryType());
            assertEquals(400, displayedTax.amountMinor());
            assertTrue(displayedTax.note().contains("服务器等额补贴"));
            assertFalse(repository.displayLedger(townId, 0, 45).stream()
                    .anyMatch(entry -> entry.entryType().equals("SERVER_TAX_SUBSIDY")));
        }
    }

    @Test
    void rollsBackDonationTaxExpansionAndRefundAtLateFailurePoints() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("economy-rollback.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID mayorId = UUID.randomUUID();
            UUID worldId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, worldId);
            EconomyRepository repository = new EconomyRepository(gate.dataSource(), () -> false);
            repository.initializeAccounts();
            EconomyRepository.EconomyOperation seed = repository.prepareOperation(townId,
                    "DONATION", 1_000, mayorId, "Mayor", "rollback:seed", "初始资金");
            repository.markOperationExternalApplied(seed.operationId());
            repository.completeOperation(seed.operationId());

            EconomyRepository.EconomyOperation donation = repository.prepareOperation(townId,
                    "DONATION", 300, mayorId, "Mayor", "rollback:donation", "捐款回滚");
            repository.markOperationExternalApplied(donation.operationId());
            execute(gate, """
                    CREATE TRIGGER fail_donation_complete
                    BEFORE UPDATE ON economy_operations
                    WHEN NEW.status = 'COMPLETED' AND NEW.business_key = 'rollback:donation'
                    BEGIN SELECT RAISE(ABORT, 'injected donation failure'); END
                    """);
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.completeOperation(donation.operationId()));
            assertEquals(1_000, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key = 'rollback:donation'"));
            assertEquals("EXTERNAL_APPLIED", scalarText(gate, "SELECT status FROM "
                    + "economy_operations WHERE business_key = 'rollback:donation'"));
            execute(gate, "DROP TRIGGER fail_donation_complete");

            execute(gate, """
                    CREATE TRIGGER fail_tax_subsidy
                    BEFORE INSERT ON ledger_entries
                    WHEN NEW.business_key = 'rollback:tax:subsidy'
                    BEGIN SELECT RAISE(ABORT, 'injected tax failure'); END
                    """);
            EconomyRepository.ExternalIncomeTax tax = new EconomyRepository.ExternalIncomeTax(
                    townId, "rollback:tax", "JOBS", mayorId, "Mayor", 10_000, 500, 500);
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.recordExternalIncomeTax(tax));
            assertEquals(1_000, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM external_income_tax_records "
                    + "WHERE business_key = 'rollback:tax'"));
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key LIKE 'rollback:tax%'"));
            execute(gate, "DROP TRIGGER fail_tax_subsidy");

            TerritoryUnit origin = repository.territoryUnits(townId).getFirst().unit();
            TerritoryUnit east = TerritoryRules.next(List.of(origin), ExpansionDirection.EAST);
            EconomyRepository.ExpansionRequest failedExpansion =
                    new EconomyRepository.ExpansionRequest(townId, east, "SKY", "unit_p1_p0",
                            200, mayorId, "Mayor", "rollback:expansion");
            execute(gate, """
                    CREATE TRIGGER fail_expansion_ledger
                    BEFORE INSERT ON ledger_entries
                    WHEN NEW.business_key = 'rollback:expansion'
                    BEGIN SELECT RAISE(ABORT, 'injected expansion failure'); END
                    """);
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.prepareExpansion(failedExpansion));
            assertEquals(1, repository.territoryUnits(townId).size());
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM territory_expansions "
                    + "WHERE business_key = 'rollback:expansion'"));
            assertEquals(1_000, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            execute(gate, "DROP TRIGGER fail_expansion_ledger");

            EconomyRepository.ExpansionOperation prepared = repository.prepareExpansion(
                    new EconomyRepository.ExpansionRequest(townId, east, "SKY", "unit_p1_p0",
                            200, mayorId, "Mayor", "rollback:refund"));
            execute(gate, """
                    CREATE TRIGGER fail_expansion_refund
                    BEFORE DELETE ON territory_units
                    BEGIN SELECT RAISE(ABORT, 'injected refund failure'); END
                    """);
            assertThrows(EconomyRepository.ConflictException.class,
                    () -> repository.refundExpansion(prepared.expansionId(), "注入失败"));
            assertEquals(800, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            assertEquals(2, repository.territoryUnits(townId).size());
            assertEquals(1, repository.pendingExpansions().size());
            assertEquals(0, scalar(gate, "SELECT COUNT(*) FROM ledger_entries "
                    + "WHERE business_key = 'rollback:refund:refund'"));
        }
    }

    @Test
    void preservesOriginalBusyFailureWhenRollbackHasNoActiveTransaction() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("busy-rollback.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(2), Duration.ofMillis(100)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            insertTown(gate, townId, UUID.randomUUID(), UUID.randomUUID());
            EconomyRepository repository = new EconomyRepository(gate.dataSource(), () -> false);
            repository.initializeAccounts();

            try (Connection blocker = DriverManager.getConnection(url);
                 Statement statement = blocker.createStatement()) {
                statement.execute("PRAGMA busy_timeout=100");
                statement.execute("BEGIN IMMEDIATE");

                EconomyRepository.StorageUnavailableException failure = assertThrows(
                        EconomyRepository.StorageUnavailableException.class,
                        () -> repository.reconcileSettlement(0));
                String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
                assertTrue(message.contains("busy") || message.contains("locked"),
                        failure.getMessage());
                assertFalse(message.contains("cannot rollback"), failure.getMessage());
                statement.execute("ROLLBACK");
            }

            EconomyRepository.EconomyOperation recovered = repository.prepareOperation(townId,
                    "DONATION", 100, UUID.randomUUID(), "Recovery",
                    "busy-recovery", "写锁恢复测试");
            assertEquals("PREPARED", recovered.status());
        }
    }

    @Test
    void findsTerritoryChunksOwnedByAnotherActiveTown() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("territory-map.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID worldId = UUID.randomUUID();
            UUID townId = UUID.randomUUID();
            UUID neighborId = UUID.randomUUID();
            insertTown(gate, townId, UUID.randomUUID(), worldId);
            insertTown(gate, neighborId, UUID.randomUUID(), worldId,
                    "邻镇", "邻", "NEAR", 16, 20);
            EconomyRepository repository = new EconomyRepository(
                    gate.dataSource(), () -> false);

            List<EconomyRepository.OccupiedTerritoryChunk> occupied =
                    repository.occupiedChunksOutsideTown(townId, worldId,
                            15, 17, 19, 21);

            assertEquals(9, occupied.size());
            assertTrue(occupied.stream().allMatch(chunk ->
                    chunk.townId().equals(neighborId) && chunk.townName().equals("邻镇")));
            assertTrue(repository.occupiedChunksOutsideTown(neighborId, worldId,
                    15, 17, 19, 21).isEmpty());
        }
    }

    private static void insertTown(DatabaseGate gate, UUID townId, UUID mayorId, UUID worldId)
            throws Exception {
        insertTown(gate, townId, mayorId, worldId,
                "测试镇", "测", "SKY", 10, 20);
    }

    private static void insertTown(DatabaseGate gate, UUID townId, UUID mayorId, UUID worldId,
                                   String name, String shortName, String residenceName,
                                   int centerX, int centerZ) throws Exception {
        UUID unitId = UUID.randomUUID();
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, ?, ?, ?, ?, '经济测试', '规则', 'ACTIVE', ?)
                     """);
             PreparedStatement member = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role)
                     VALUES (?, ?, 'MAYOR')
                     """);
             PreparedStatement unit = connection.prepareStatement("""
                     INSERT INTO territory_units
                         (unit_id, town_id, world_uuid, world_name, grid_x, grid_z,
                          center_chunk_x, center_chunk_z, residence_name, residence_area_name,
                          projection_status)
                     VALUES (?, ?, ?, 'world', 0, 0, ?, ?, ?, 'main', 'ACTIVE')
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setString(2, name);
            town.setString(3, name);
            town.setString(4, shortName);
            town.setString(5, shortName);
            town.setBytes(6, uuid(mayorId));
            town.executeUpdate();
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(mayorId));
            member.executeUpdate();
            unit.setBytes(1, uuid(unitId));
            unit.setBytes(2, uuid(townId));
            unit.setBytes(3, uuid(worldId));
            unit.setInt(4, centerX);
            unit.setInt(5, centerZ);
            unit.setString(6, residenceName);
            unit.executeUpdate();
            InitialTerritory territory = new InitialTerritory(
                    new ChunkPosition(worldId, "world", centerX, centerZ));
            try (PreparedStatement chunk = connection.prepareStatement("""
                    INSERT INTO territory_chunks (unit_id, world_uuid, chunk_x, chunk_z)
                    VALUES (?, ?, ?, ?)
                    """)) {
                for (ChunkPosition position : territory.chunks()) {
                    chunk.setBytes(1, uuid(unitId));
                    chunk.setBytes(2, uuid(worldId));
                    chunk.setInt(3, position.x());
                    chunk.setInt(4, position.z());
                    chunk.addBatch();
                }
                chunk.executeBatch();
            }
        }
    }

    private static void insertMember(DatabaseGate gate, UUID townId, UUID playerId, String role)
            throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            statement.setString(3, role);
            statement.executeUpdate();
        }
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static void execute(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalar(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String scalarText(DatabaseGate gate, String sql) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}

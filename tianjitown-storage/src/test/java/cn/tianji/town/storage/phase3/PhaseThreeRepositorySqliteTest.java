package cn.tianji.town.storage.phase3;

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
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseThreeRepositorySqliteTest {
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
            UUID worldId = UUID.randomUUID();
            insertTown(gate, townId, mayorId, worldId);
            PhaseThreeRepository repository = new PhaseThreeRepository(gate.dataSource(), () -> false);
            repository.initializeAccounts();

            PhaseThreeRepository.QuickShopTax tax = new PhaseThreeRepository.QuickShopTax(
                    townId, "qs:test:1", 42, "SELLING", mayorId, UUID.randomUUID(),
                    10_000, 500, 500, "world");
            assertEquals(500, repository.recordQuickShopTax(tax).balanceAfterMinor());
            assertEquals(500, repository.recordQuickShopTax(tax).balanceAfterMinor());
            assertEquals(1, repository.ledger(townId, 0, 45, Instant.now()).size());

            PhaseThreeRepository.EconomyOperation donation = repository.prepareOperation(townId,
                    "DONATION", 1_000, mayorId, "Mayor", "donation:test:1", "测试捐款");
            repository.markOperationExternalApplied(donation.operationId());
            assertEquals(1_500, repository.completeOperation(donation.operationId())
                    .balanceAfterMinor());
            assertEquals(1_500, repository.completeOperation(donation.operationId())
                    .balanceAfterMinor());

            PhaseThreeRepository.EconomyOperation cancelled = repository.prepareOperation(townId,
                    "DONATION", 100, mayorId, "Mayor", "donation:cancelled", "取消测试");
            repository.markOperationExternalApplied(cancelled.operationId());
            PhaseThreeRepository.EconomyOperation cancelledResult = repository.cancelOperation(
                    cancelled.operationId(), "Vault 未扣款");
            assertEquals("CANCELLED", cancelledResult.status());
            assertEquals("Vault 未扣款", cancelledResult.lastError());

            PhaseThreeRepository.EconomyOperation unavailable = repository.prepareOperation(townId,
                    "ADMIN_ADJUSTMENT", 100, mayorId, "Mayor", "adjustment:unavailable",
                    "Vault 临时不可用");
            PhaseThreeRepository.EconomyOperation unavailableResult = repository.cancelOperation(
                    unavailable.operationId(), "Vault Economy provider 不可用");
            assertEquals("CANCELLED", unavailableResult.status());
            assertEquals("Vault Economy provider 不可用", unavailableResult.lastError());
            assertTrue(repository.pendingOperations().isEmpty());

            PhaseThreeRepository.Reconciliation shortage = repository.reconcileSettlement(1_499);
            assertFalse(shortage.healthy());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().locked());

            TerritoryUnit origin = repository.territoryUnits(townId).getFirst().unit();
            TerritoryUnit east = TerritoryRules.next(List.of(origin), ExpansionDirection.EAST);
            TerritoryUnit displaced = new TerritoryUnit(1, 0, new InitialTerritory(
                    new ChunkPosition(worldId, "world", 100, 100)));
            assertThrows(PhaseThreeRepository.ConflictException.class,
                    () -> repository.prepareExpansion(new PhaseThreeRepository.ExpansionRequest(
                            townId, displaced, "SKY", "unit_p1_p0", 200, mayorId, "Mayor",
                            "expansion:invalid-grid")));
            PhaseThreeRepository.ExpansionRequest expansion = new PhaseThreeRepository.ExpansionRequest(
                    townId, east, "SKY", "unit_p1_p0", 200, mayorId, "Mayor",
                    "expansion:test:1");
            assertThrows(PhaseThreeRepository.ConflictException.class,
                    () -> repository.prepareExpansion(expansion));

            assertTrue(repository.reconcileSettlement(1_500).healthy());
            assertFalse(repository.findFinanceByTown(townId).orElseThrow().locked());
            PhaseThreeRepository.ExpansionOperation prepared = repository.prepareExpansion(expansion);
            assertEquals(prepared.expansionId(), repository.prepareExpansion(expansion).expansionId());
            assertEquals(1_300, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            repository.refundExpansion(prepared.expansionId(), "Residence 测试失败");
            assertEquals(1_500, repository.findFinanceByTown(townId).orElseThrow().balanceMinor());
            assertEquals(1, repository.territoryUnits(townId).size());
            assertEquals(4, repository.ledger(townId, 0, 45, Instant.now()).size());

            PhaseThreeRepository.TaxChange changed = repository.changeTaxRate(townId, mayorId,
                    750, "Mayor", "测试税率");
            assertEquals(750, changed.basisPoints());
            assertTrue(changed.revision() > 1);

            PhaseThreeRepository.EconomyOperation uncertain = repository.prepareOperation(townId,
                    "ADMIN_ADJUSTMENT", 100, mayorId, "Mayor", "adjustment:uncertain",
                    "外部结果未知");
            repository.markOperationExternalApplied(uncertain.operationId());
            PhaseThreeRepository.EconomyOperation compensation = repository.requireCompensation(
                    uncertain.operationId(), "Vault 调用异常");
            assertEquals("COMPENSATION_REQUIRED", compensation.status());
            assertEquals("Vault 调用异常", compensation.lastError());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().locked());
            assertTrue(repository.reconcileSettlement(1_500).healthy());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().locked());
            assertTrue(repository.findFinanceByTown(townId).orElseThrow().lockReason()
                    .startsWith("ECONOMY_COMPENSATION:"));
        }
    }

    private static void insertTown(DatabaseGate gate, UUID townId, UUID mayorId, UUID worldId)
            throws Exception {
        UUID unitId = UUID.randomUUID();
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, '测试镇', '测试镇', '测', '测', '阶段3', '规则', 'ACTIVE', ?)
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
                     VALUES (?, ?, ?, 'world', 0, 0, 10, 20, 'SKY', 'main', 'ACTIVE')
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setBytes(2, uuid(mayorId));
            town.executeUpdate();
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(mayorId));
            member.executeUpdate();
            unit.setBytes(1, uuid(unitId));
            unit.setBytes(2, uuid(townId));
            unit.setBytes(3, uuid(worldId));
            unit.executeUpdate();
            InitialTerritory territory = new InitialTerritory(
                    new ChunkPosition(worldId, "world", 10, 20));
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

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}

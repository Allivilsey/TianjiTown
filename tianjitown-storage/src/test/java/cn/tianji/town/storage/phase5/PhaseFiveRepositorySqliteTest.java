package cn.tianji.town.storage.phase5;

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
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseFiveRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reservesDailyRefundAtomicallyAndRevalidatesTerritory() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("phase5.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID playerId = UUID.randomUUID();
            UUID worldId = UUID.randomUUID();
            insertTownAndTerritory(gate, townId, playerId, worldId);
            PhaseFiveRepository repository = new PhaseFiveRepository(gate.dataSource(),
                    () -> false);

            PhaseFiveRepository.BonusIndex index = repository.loadBonusIndex();
            assertEquals(townId, index.memberships().get(playerId));
            assertEquals(townId, index.territories().get(
                    new PhaseFiveRepository.ChunkKey(worldId, 10, 20)));
            LocalDate day = LocalDate.of(2026, 8, 12);
            assertTrue(repository.reserveBuildingRefund(townId, playerId, worldId, 10, 20,
                    day, "minecraft:stone", 2).granted());
            assertEquals(2, repository.reserveBuildingRefund(townId, playerId, worldId, 10, 20,
                    day, "minecraft:stone", 2).used());
            assertFalse(repository.reserveBuildingRefund(townId, playerId, worldId, 10, 20,
                    day, "minecraft:stone", 2).granted());
            assertFalse(repository.reserveBuildingRefund(townId, playerId, worldId, 11, 20,
                    day, "minecraft:stone", 2).granted());

            PhaseFiveRepository.DiagnosticSnapshot diagnostic = repository.diagnose(
                    Instant.EPOCH);
            assertEquals("ok", diagnostic.quickCheck());
            assertEquals(0, diagnostic.foreignKeyViolations());
            assertEquals(1, diagnostic.counts().get("activeTowns"));
            assertEquals(1, repository.cleanupRefundCounters(day.plusDays(1)));
        }
    }

    private static void insertTownAndTerritory(DatabaseGate gate, UUID townId, UUID playerId,
                                               UUID worldId) throws Exception {
        UUID unitId = UUID.randomUUID();
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, '阶段五镇', '阶段五镇', '五', '五', '测试', '规则', 'ACTIVE', ?)
                     """);
             PreparedStatement member = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role)
                     VALUES (?, ?, 'MAYOR')
                     """);
             PreparedStatement unit = connection.prepareStatement("""
                     INSERT INTO territory_units
                         (unit_id, town_id, world_uuid, world_name, grid_x, grid_z,
                          center_chunk_x, center_chunk_z, residence_name,
                          residence_area_name, projection_status)
                     VALUES (?, ?, ?, 'world', 0, 0, 10, 20, 'p5t', 'main', 'ACTIVE')
                     """);
             PreparedStatement chunk = connection.prepareStatement("""
                     INSERT INTO territory_chunks (unit_id, world_uuid, chunk_x, chunk_z)
                     VALUES (?, ?, 10, 20)
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setBytes(2, uuid(playerId));
            town.executeUpdate();
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(playerId));
            member.executeUpdate();
            unit.setBytes(1, uuid(unitId));
            unit.setBytes(2, uuid(townId));
            unit.setBytes(3, uuid(worldId));
            unit.executeUpdate();
            chunk.setBytes(1, uuid(unitId));
            chunk.setBytes(2, uuid(worldId));
            chunk.executeUpdate();
        }
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}

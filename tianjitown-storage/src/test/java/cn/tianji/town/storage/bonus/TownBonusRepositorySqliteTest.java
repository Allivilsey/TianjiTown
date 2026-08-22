package cn.tianji.town.storage.bonus;

import cn.tianji.town.storage.database.DatabaseConfig;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.core.town.MemberRole;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownBonusRepositorySqliteTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reservesWeeklyRefundAtomicallyAndRevalidatesTerritory() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("phase5.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID playerId = UUID.randomUUID();
            UUID worldId = UUID.randomUUID();
            insertTownAndTerritory(gate, townId, playerId, worldId);
            insertArchivedFailedProjection(gate, UUID.randomUUID(), worldId);
            TownBonusRepository repository = new TownBonusRepository(gate.dataSource(),
                    () -> false);

            TownBonusRepository.BonusIndex index = repository.loadBonusIndex();
            assertEquals(townId, index.memberships().get(playerId));
            assertEquals(MemberRole.MAYOR, index.roles().get(playerId));
            assertEquals(townId, index.territories().get(
                    new TownBonusRepository.ChunkKey(worldId, 10, 20)));
            assertTrue(repository.recordBeaconEffect(townId, "minecraft:speed", 0));
            assertFalse(repository.recordBeaconEffect(townId, "minecraft:speed", 0));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.recordBeaconEffect(townId, "minecraft:speed", -1));
            assertTrue(repository.recordBeaconEffect(townId, "minecraft:speed", 1));
            assertEquals(1, repository.loadBonusIndex().beaconEffects().get(townId)
                    .get("minecraft:speed"));
            LocalDate day = LocalDate.of(2026, 8, 10);
            assertTrue(repository.reserveBuildingRefund(townId, playerId, worldId, 10, 20,
                    day, "minecraft:stone", 2).granted());
            assertEquals(2, repository.reserveBuildingRefund(townId, playerId, worldId, 10, 20,
                    day, "minecraft:stone", 2).used());
            assertFalse(repository.reserveBuildingRefund(townId, playerId, worldId, 10, 20,
                    day, "minecraft:stone", 2).granted());
            assertFalse(repository.reserveBuildingRefund(townId, playerId, worldId, 11, 20,
                    day, "minecraft:stone", 2).granted());

            TownBonusRepository.DiagnosticSnapshot diagnostic = repository.diagnose(
                    Instant.EPOCH);
            assertEquals("ok", diagnostic.quickCheck());
            assertEquals(0, diagnostic.foreignKeyViolations());
            assertEquals(1, diagnostic.counts().get("activeTowns"));
            assertEquals(0, diagnostic.counts().get("failedProjections"));
            failTownProjection(gate, townId);
            assertEquals(1, repository.diagnose(Instant.EPOCH).counts()
                    .get("failedProjections"));
            assertEquals(1, repository.cleanupRefundCounters(day.plusWeeks(1)));
        }
    }

    @Test
    void separatesWeeklyCountersAndCleansOnlyRecordsOlderThanRetentionBoundary()
            throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("refund-retention.db");
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(url,
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = UUID.randomUUID();
            UUID playerId = UUID.randomUUID();
            UUID worldId = UUID.randomUUID();
            insertTownAndTerritory(gate, townId, playerId, worldId);
            TownBonusRepository repository = new TownBonusRepository(gate.dataSource(),
                    () -> false);
            LocalDate firstMonday = LocalDate.of(2026, 5, 18);
            for (int week = 0; week < 14; week++) {
                LocalDate weekStart = firstMonday.plusWeeks(week);
                assertTrue(repository.reserveBuildingRefund(townId, playerId, worldId,
                        10, 20, weekStart, "minecraft:stone", 2).granted());
                assertEquals(2, repository.reserveBuildingRefund(townId, playerId, worldId,
                        10, 20, weekStart, "minecraft:stone", 2).used());
            }

            LocalDate currentWeek = firstMonday.plusWeeks(13);
            assertEquals(1, repository.cleanupRefundCounters(currentWeek.minusWeeks(12)));
            assertEquals(13, countRows(gate, "building_refund_weekly"));
            assertEquals(0, repository.cleanupRefundCounters(currentWeek.minusWeeks(12)));
        }
    }

    private static void failTownProjection(DatabaseGate gate, UUID townId) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE territory_units
                        SET projection_status = 'FAILED', projection_error = '当前投影失败'
                      WHERE town_id = ?
                     """)) {
            statement.setBytes(1, uuid(townId));
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertArchivedFailedProjection(DatabaseGate gate, UUID townId,
                                                       UUID worldId) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, short_name, normalized_short_name,
                          description, rules_text, status, mayor_uuid)
                     VALUES (?, '归档投影镇', '归档投影镇', '旧', '旧', '测试', '规则',
                             'ARCHIVED', ?)
                     """);
             PreparedStatement unit = connection.prepareStatement("""
                     INSERT INTO territory_units
                         (unit_id, town_id, world_uuid, world_name, grid_x, grid_z,
                          center_chunk_x, center_chunk_z, residence_name,
                          residence_area_name, projection_status, projection_error)
                     VALUES (?, ?, ?, 'world', 1, 0, 19, 20, 'archived', 'main',
                             'FAILED', '历史投影已归档')
                     """)) {
            town.setBytes(1, uuid(townId));
            town.setBytes(2, uuid(UUID.randomUUID()));
            town.executeUpdate();
            unit.setBytes(1, uuid(UUID.randomUUID()));
            unit.setBytes(2, uuid(townId));
            unit.setBytes(3, uuid(worldId));
            unit.executeUpdate();
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
                     VALUES (?, '加成测试镇', '加成测试镇', '加', '加', '测试', '规则', 'ACTIVE', ?)
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

    private static int countRows(DatabaseGate gate, String table) throws Exception {
        try (Connection connection = gate.dataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}

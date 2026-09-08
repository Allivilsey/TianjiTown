package org.allivlisey.tianjitown.paper.land;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.ExpansionDirection;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryServiceTest {
    private static final List<String> MESSAGE_KEYS = List.of(
            "validation.territory.batch-selection-required",
            "validation.territory.batch-capacity-exceeded",
            "validation.territory.batch-grid-out-of-bounds",
            "validation.territory.batch-duplicate-selection",
            "validation.territory.batch-occupied-selection",
            "validation.territory.batch-not-connected",
            "validation.territory.town-required",
            "validation.territory.mayor-required",
            "validation.territory.origin-missing",
            "validation.territory.capacity-reached",
            "dialog.territory.cell.not-adjacent-detail",
            "dialog.territory.cell.unavailable-detail");

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesTerritoryValidationAndCellDetailsFromConfiguredMessages() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        for (String key : MESSAGE_KEYS) {
            String rendered = messages.plainText(key);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }

        UUID townId = UUID.randomUUID();
        UUID mayorId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("territory-service.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            insertTown(gate, townId, mayorId, worldId);
            insertMember(gate, townId, memberId, "MEMBER");

            EconomyRepository finance = new EconomyRepository(gate.dataSource(), () -> false);
            finance.initializeAccounts();
            TerritoryService service = new TerritoryService(finance,
                    new SitePolicy(null, null, null), messages,
                    EconomySettings.load(new MemoryConfiguration()), 2);

            assertEquals(500_000, service.preview(mayorId, ExpansionDirection.EAST).priceMinor());
            TerritoryService.ExpansionBatchPreview batch = service.batchPreview(mayorId, Set.of(
                    new TerritoryService.GridSelection(1, 0),
                    new TerritoryService.GridSelection(2, 0),
                    new TerritoryService.GridSelection(2, 1)));
            assertEquals(List.of(500_000L, 525_000L, 551_250L), batch.candidates().stream()
                    .map(TerritoryService.ExpansionPreview::priceMinor).toList());
            assertEquals(1_576_250, batch.totalPriceMinor());
            assertEquals(500_000, service.map(mayorId).priceMinor());

            assertMessage("请至少选择一个领地单元",
                    () -> service.batchPreview(mayorId, Set.of()));
            Set<TerritoryService.GridSelection> oversized = IntStream.range(0, 25)
                    .mapToObj(index -> new TerritoryService.GridSelection(index, 0))
                    .collect(Collectors.toSet());
            assertMessage("批量激活后超过领地单元上限",
                    () -> service.batchPreview(mayorId, oversized));
            assertMessage("选中的领地单元超出 5×5 激活网格",
                    () -> service.batchPreview(mayorId,
                            Set.of(new TerritoryService.GridSelection(3, 0))));
            assertMessage("选中的领地单元已经激活",
                    () -> service.batchPreview(mayorId,
                            Set.of(new TerritoryService.GridSelection(0, 0))));
            assertMessage("批量选区必须与现有领地四方向连通",
                    () -> service.batchPreview(mayorId,
                            Set.of(new TerritoryService.GridSelection(2, 0))));
            assertMessage("你不属于任何小镇",
                    () -> service.batchPreview(UUID.randomUUID(), Set.of()));
            assertMessage("只有镇长可以使用公共资金激活",
                    () -> service.batchPreview(memberId,
                            Set.of(new TerritoryService.GridSelection(1, 0))));

            EconomySettings noExpansionSettings = new EconomySettings(true, true, "tax", 2,
                    2500, new BigDecimal("3000.00"), 1,
                    new BigDecimal("50000.00"), new BigDecimal("5000.00"));
            TerritoryService noExpansionService = new TerritoryService(finance,
                    new SitePolicy(null, null, null), messages, noExpansionSettings, 2);
            assertMessage("领地单元已达到配置上限",
                    () -> noExpansionService.preview(mayorId, ExpansionDirection.EAST));

            TerritoryService.TerritoryMap map = service.map(mayorId);
            assertEquals("只能激活与已激活区域边缘相连的区域", map.cells().stream()
                    .filter(cell -> cell.gridX() == 2 && cell.gridZ() == 2)
                    .findFirst().orElseThrow().detail());

            YamlConfiguration override = new YamlConfiguration();
            override.set("validation.territory.batch-selection-required", "自定义选择提示");
            override.set("dialog.territory.cell.not-adjacent-detail", "自定义相邻提示");
            override.save(temporaryDirectory.resolve("messages.yml").toFile());
            messages.reload();

            assertMessage("自定义选择提示",
                    () -> service.batchPreview(mayorId, Set.of()));
            TerritoryService.TerritoryMap reloadedMap = service.map(mayorId);
            assertEquals("自定义相邻提示", reloadedMap.cells().stream()
                    .filter(cell -> cell.gridX() == 2 && cell.gridZ() == 2)
                    .findFirst().orElseThrow().detail());

            try (Connection connection = gate.dataSource().getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM territory_units WHERE town_id = ?")) {
                statement.setBytes(1, uuid(townId));
                statement.executeUpdate();
            }
            assertMessage("初始领地单元缺失",
                    () -> service.batchPreview(mayorId, Set.of()));
        }
    }

    @Test
    void usesAStableTechnicalIdentifierForInvalidMapShape() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new TerritoryService.TerritoryMap(List.of(), 0, 25, 0));

        assertEquals("territory.map-size-invalid", exception.getMessage());
    }

    private static void assertMessage(String expected, Executable action) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, action);
        assertEquals(expected, exception.getMessage());
    }

    private static void insertTown(DatabaseGate gate, UUID townId, UUID mayorId, UUID worldId)
            throws Exception {
        UUID unitId = UUID.randomUUID();
        try (Connection connection = gate.dataSource().getConnection();
             PreparedStatement town = connection.prepareStatement("""
                     INSERT INTO towns
                         (town_id, name, normalized_name, description, rules_text, status, mayor_uuid)
                     VALUES (?, 'Test Town', 'test town', 'description', 'rules', 'ACTIVE', ?)
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
}

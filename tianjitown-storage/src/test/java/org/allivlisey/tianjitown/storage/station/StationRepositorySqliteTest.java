package org.allivlisey.tianjitown.storage.station;

import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StationRepositorySqliteTest {
    @TempDir Path directory;

    @Test
    void persistsRecordsAndRemovalAcrossDatabaseReopenAndEnforcesUniqueness() throws Exception {
        DatabaseConfig config = new DatabaseConfig("jdbc:sqlite:" + directory.resolve("stations.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        UUID world = UUID.randomUUID();
        UUID town = UUID.randomUUID();
        StationRecord publicStation = new StationRecord("public", world, "主世界", -10, 64, 12, null, null);
        StationRecord townStation = new StationRecord("town", world, "主世界", 20, 65, 12, town, "测试镇");
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            try (var connection = gate.dataSource().getConnection();
                 var statement = connection.prepareStatement("""
                         INSERT INTO towns (town_id, name, normalized_name, description, rules_text, status, mayor_uuid)
                         VALUES (?, '测试镇', 'test', '', '', 'ACTIVE', ?)
                         """)) {
                byte[] id = ByteBuffer.allocate(16).putLong(town.getMostSignificantBits())
                        .putLong(town.getLeastSignificantBits()).array();
                statement.setBytes(1, id);
                statement.setBytes(2, id);
                statement.executeUpdate();
            }
            StationRepository repository = new StationRepository(gate.dataSource(), () -> false);
            assertTrue(repository.insert(publicStation));
            assertTrue(repository.insert(townStation));
            assertFalse(repository.insert(new StationRecord("duplicate-location", world, "renamed",
                    -10, 64, 12, null, null)));
            assertFalse(repository.insert(new StationRecord("duplicate-town", world, "主世界",
                    30, 65, 12, town, "测试镇")));
            assertFalse(repository.insert(new StationRecord("public", world, "主世界",
                    30, 65, 12, null, null)));
            StationRecord otherWorld = new StationRecord("other", UUID.randomUUID(), "other",
                    -10, 64, 12, null, null);
            assertTrue(repository.insert(otherWorld));
            repository.deleteAt(otherWorld.worldId(), -10, 64, 12);
            assertEquals(List.of(publicStation, townStation), repository.load());
            StationRepository mainThread = new StationRepository(gate.dataSource(), () -> true);
            assertThrows(IllegalStateException.class, mainThread::load);
            assertThrows(IllegalStateException.class, () -> mainThread.insert(publicStation));
            assertThrows(IllegalStateException.class, () -> mainThread.deleteAt(world, -10, 64, 12));
        }
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            StationRepository repository = new StationRepository(gate.dataSource(), () -> false);
            assertEquals(List.of(publicStation, townStation), repository.load());
            repository.deleteAt(world, -10, 64, 12);
        }
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            assertEquals(List.of(townStation), new StationRepository(gate.dataSource(), () -> false).load());
        }
    }
}

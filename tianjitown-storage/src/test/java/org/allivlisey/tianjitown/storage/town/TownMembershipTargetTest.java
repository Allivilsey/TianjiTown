package org.allivlisey.tianjitown.storage.town;

import org.allivlisey.tianjitown.storage.database.DatabaseConfig;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TownMembershipTargetTest {
    @TempDir Path temporaryDirectory;

    @Test
    void aLeaveConfirmationForAnotherTownCannotRemoveCurrentMembership() throws Exception {
        try (DatabaseGate gate = new DatabaseGate(new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("membership-target.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5)))) {
            assertTrue(gate.verifyAndMigrate().healthy());
            TownRepository repository = new TownRepository(gate.dataSource(), () -> false);
            UUID oldTown = UUID.randomUUID(), currentTown = UUID.randomUUID(), player = UUID.randomUUID();
            try (var connection = gate.dataSource().getConnection();
                 var town = connection.prepareStatement("""
                         INSERT INTO towns (town_id, name, normalized_name, description, rules_text, status, mayor_uuid)
                         VALUES (?, '当前镇', '当前镇', '简介', '规则', 'ACTIVE', ?)
                         """);
                 var member = connection.prepareStatement("""
                         INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, 'MEMBER')
                         """)) {
                town.setBytes(1, TownSqlValues.uuid(currentTown));
                town.setBytes(2, TownSqlValues.uuid(UUID.randomUUID()));
                town.executeUpdate();
                member.setBytes(1, TownSqlValues.uuid(currentTown));
                member.setBytes(2, TownSqlValues.uuid(player));
                member.executeUpdate();
            }

            assertThrows(TownRepository.ConflictException.class, () -> repository.leaveTown(player, oldTown));
            assertEquals(1, count(gate, "town_members"));
            assertEquals(0, count(gate, "town_member_departures"));
            assertEquals(0, count(gate, "audit_logs"));

            repository.leaveTown(player, currentTown);
            assertEquals(0, count(gate, "town_members"));
            assertEquals(1, count(gate, "town_member_departures"));
        }
    }

    private long count(DatabaseGate gate, String table) throws Exception {
        try (var connection = gate.dataSource().getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}

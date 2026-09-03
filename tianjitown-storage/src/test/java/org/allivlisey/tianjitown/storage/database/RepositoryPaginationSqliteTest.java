package org.allivlisey.tianjitown.storage.database;

import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.AuditSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryPaginationSqliteTest {
    private static final long FIXED_MILLIS = 1_800_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsApplicationMemberLedgerAndAuditOrderingStableAtOneMillisecond()
            throws Exception {
        DatabaseConfig config = new DatabaseConfig(
                "jdbc:sqlite:" + temporaryDirectory.resolve("pagination.db"),
                Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (DatabaseGate gate = new DatabaseGate(config)) {
            assertTrue(gate.verifyAndMigrate().healthy());
            UUID townId = id(10_000);
            seed(gate, townId, 125, 76, 76, 250);

            TownRepository towns = new TownRepository(gate.dataSource(), () -> false);
            EconomyRepository economy = new EconomyRepository(gate.dataSource(), () -> false);

            List<ApplicationSnapshot> reviewQueue = towns.listReviewQueue(1_000);
            assertEquals(100, reviewQueue.size());
            assertEquals(ids(1, 100), reviewQueue.stream().map(ApplicationSnapshot::id).toList());
            assertEquals(reviewQueue, towns.listReviewQueue(1_000));

            List<UUID> memberIds = new ArrayList<>();
            List<TownSnapshot.Page> pages = new ArrayList<>();
            for (int page = 0; page < 4; page++) {
                TownSnapshot.Page result = towns.listMembers(townId, page, 20);
                pages.add(result);
                memberIds.addAll(result.members().stream().map(TownSnapshot.Member::playerId)
                        .toList());
            }
            assertEquals(ids(1_001, 76), memberIds);
            assertEquals(76, new HashSet<>(memberIds).size());
            assertTrue(pages.get(0).hasNext());
            assertTrue(pages.get(1).hasNext());
            assertTrue(pages.get(2).hasNext());
            assertFalse(pages.get(3).hasNext());
            assertEquals(76, towns.listMembers(townId, 0, 1_000).members().size());

            List<UUID> reverseTraversal = new ArrayList<>();
            for (int page = 3; page >= 0; page--) {
                reverseTraversal.addAll(towns.listMembers(townId, page, 20).members().stream()
                        .map(TownSnapshot.Member::playerId).toList());
            }
            assertEquals(new HashSet<>(memberIds), new HashSet<>(reverseTraversal));

            List<UUID> ledgerIds = new ArrayList<>();
            for (int page = 0; page < 4; page++) {
                ledgerIds.addAll(economy.ledger(townId, page, 20).stream()
                        .map(EconomyRepository.LedgerEntry::entryId).toList());
            }
            assertEquals(ids(2_001, 76), ledgerIds);
            assertEquals(76, new HashSet<>(ledgerIds).size());
            assertThrows(IllegalArgumentException.class, () -> economy.ledger(townId, 0, 46));

            List<AuditSnapshot> audits = towns.auditLog(1_000);
            assertEquals(200, audits.size());
            assertEquals(250L, audits.getFirst().id());
            assertEquals(51L, audits.getLast().id());
            assertEquals(audits, towns.auditLog(1_000));
        }
    }

    private static void seed(DatabaseGate gate, UUID townId, int applications, int members,
                             int ledgerEntries, int audits) throws Exception {
        try (Connection connection = gate.dataSource().getConnection()) {
            insertTown(connection, townId);
            insertApplications(connection, applications);
            insertMembers(connection, townId, members);
            insertLedger(connection, townId, ledgerEntries);
            insertAudits(connection, audits);
        }
    }

    private static void insertTown(Connection connection, UUID townId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO towns
                    (town_id, name, normalized_name, short_name, normalized_short_name,
                     description, rules_text, status, reuse_blocked, mayor_uuid,
                     created_at, updated_at)
                VALUES (?, '分页镇', '分页镇', '分页', '分页', '', '', 'ACTIVE', 1, ?, ?, ?)
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(id(9_999)));
            statement.setLong(3, FIXED_MILLIS);
            statement.setLong(4, FIXED_MILLIS);
            statement.executeUpdate();
        }
    }

    private static void insertApplications(Connection connection, int count) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO town_applications
                    (application_id, applicant_uuid, name, normalized_name, short_name,
                     normalized_short_name, residence_name, description, rules_text, status,
                     submitted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '', '', 'SUBMITTED', ?, ?, ?)
                """)) {
            for (int value = count; value >= 1; value--) {
                String suffix = Integer.toString(value);
                statement.setBytes(1, uuid(id(value)));
                statement.setBytes(2, uuid(id(100_000 + value)));
                statement.setString(3, "申请" + suffix);
                statement.setString(4, "申请" + suffix);
                statement.setString(5, "A" + suffix);
                statement.setString(6, "a" + suffix);
                statement.setString(7, "area" + suffix);
                statement.setLong(8, FIXED_MILLIS);
                statement.setLong(9, FIXED_MILLIS);
                statement.setLong(10, FIXED_MILLIS);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertMembers(Connection connection, UUID townId, int count)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO town_members (town_id, player_uuid, role, joined_at)
                VALUES (?, ?, 'MEMBER', ?)
                """)) {
            for (int value = count; value >= 1; value--) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(id(1_000 + value)));
                statement.setLong(3, FIXED_MILLIS);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertLedger(Connection connection, UUID townId, int count)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries
                    (entry_id, town_id, entry_type, amount_minor, balance_after_minor,
                     actor_name, business_key, note, created_at)
                VALUES (?, ?, 'ADMIN_ADJUSTMENT', 1, ?, 'Pagination', ?, '', ?)
                """)) {
            for (int value = count; value >= 1; value--) {
                statement.setBytes(1, uuid(id(2_000 + value)));
                statement.setBytes(2, uuid(townId));
                statement.setLong(3, value);
                statement.setString(4, "pagination:" + value);
                statement.setLong(5, FIXED_MILLIS);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertAudits(Connection connection, int count) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_logs
                    (actor_name, action, target_type, target_id, reason, detail, created_at)
                VALUES ('Pagination', 'TEST', 'TEST', ?, '分页', '', ?)
                """)) {
            for (int value = 1; value <= count; value++) {
                statement.setString(1, Integer.toString(value));
                statement.setLong(2, FIXED_MILLIS);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<UUID> ids(long first, int count) {
        List<UUID> result = new ArrayList<>(count);
        for (long value = first; value < first + count; value++) {
            result.add(id(value));
        }
        return List.copyOf(result);
    }

    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }
}

package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.allivlisey.tianjitown.core.town.TownStatus;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

import org.allivlisey.tianjitown.storage.town.TownRepository.*;

final class TownDeletionStore {
    private final TownDatabase database;

    TownDeletionStore(TownDatabase database) {
        this.database = database;
    }

    public void deleteTown(UUID townId, UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        database.transaction(connection -> {
            prepareTownDeletion(connection, townId, actorId, actorName, reason, false, -1);
            return null;
        });
    }

    public TownSnapshot disbandTown(UUID townId, UUID mayorId, long expectedVersion) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM town_members WHERE town_id = ?")) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new ConflictException("仅剩镇长一名成员时才能解散，请先完成成员管理");
                    }
                }
            }
            return prepareTownDeletion(connection, townId, mayorId,
                    mayorId.toString(), "镇长通过小镇界面解散", true, expectedVersion);
        });
    }

    public void completeTownDeletion(UUID townId, UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        database.transaction(connection -> {
            TownSnapshot current = TownPersistence.requireTown(connection, townId);
            if (current.status() != TownStatus.ARCHIVED || !townReuseBlocked(connection, townId)) {
                throw new ConflictException("小镇未处于等待资源释放的归档状态");
            }
            try (PreparedStatement town = connection.prepareStatement("""
                    UPDATE towns SET reuse_blocked = FALSE, version = version + 1
                     WHERE town_id = ? AND status = 'ARCHIVED' AND reuse_blocked = TRUE
                    """);
                 PreparedStatement units = connection.prepareStatement("""
                         UPDATE territory_units SET reuse_blocked = FALSE
                          WHERE town_id = ? AND reuse_blocked = TRUE
                         """);
                 PreparedStatement chunks = connection.prepareStatement("""
                         DELETE FROM territory_chunks
                          WHERE unit_id IN (SELECT unit_id FROM territory_units WHERE town_id = ?)
                         """)) {
                town.setBytes(1, uuid(townId));
                TownPersistence.requireUpdated(town, "小镇删除资源已被其他操作释放");
                units.setBytes(1, uuid(townId));
                units.executeUpdate();
                chunks.setBytes(1, uuid(townId));
                chunks.executeUpdate();
            }
            TownPersistence.audit(connection, null, actorId, actorName, "TOWN_DELETE_COMPLETE", "TOWN",
                    townId.toString(), reason, "Residence 已移除；名称、小镇代码和区块已允许复用");
            return null;
        });
    }

    private TownSnapshot prepareTownDeletion(Connection connection, UUID townId, UUID actorId,
                                              String actorName, String reason, boolean mayorOnly,
                                              long expectedVersion) throws SQLException {
        TownSnapshot current = TownPersistence.requireTown(connection, townId);
        if (mayorOnly) {
            TownPersistence.requireManager(connection, townId, actorId);
            if (current.version() != expectedVersion) {
                throw new ConflictException("小镇状态已经变化，请重新打开界面确认");
            }
        }
        boolean alreadyPrepared = current.status() == TownStatus.ARCHIVED
                && townReuseBlocked(connection, townId);
        if (current.status() == TownStatus.ARCHIVED && !alreadyPrepared) {
            throw new ConflictException("小镇已经删除并完成资源释放");
        }
        if (mayorOnly && current.status() != TownStatus.ACTIVE) {
            throw new ConflictException("只有正常运行的小镇可以由镇长解散");
        }
        try (PreparedStatement town = connection.prepareStatement("""
                UPDATE towns
                   SET status = 'ARCHIVED', reuse_blocked = TRUE, archived_at = ?,
                       archive_reason = ?, version = version + 1
                 WHERE town_id = ? AND status <> 'ARCHIVED'
                """);
             PreparedStatement unit = connection.prepareStatement(
                     "UPDATE territory_units SET reuse_blocked = TRUE WHERE town_id = ?");
             PreparedStatement members = connection.prepareStatement(
                     "DELETE FROM town_members WHERE town_id = ?");
             PreparedStatement visitors = connection.prepareStatement(
                     "DELETE FROM town_visitors WHERE town_id = ?");
             PreparedStatement invitations = connection.prepareStatement("""
                     UPDATE town_invitations
                        SET revoked_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                      WHERE town_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                     """);
             PreparedStatement joinApplications = connection.prepareStatement("""
                     UPDATE town_join_applications
                        SET status = 'CANCELLED', decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                      WHERE town_id = ? AND status = 'PENDING'
                     """)) {
            if (!alreadyPrepared) {
                snapshotMembers(connection, townId);
                town.setLong(1, Instant.now().toEpochMilli());
                town.setString(2, reason);
                town.setBytes(3, uuid(townId));
                TownPersistence.requireUpdated(town, "小镇不存在或已经归档");
            }
            unit.setBytes(1, uuid(townId));
            unit.executeUpdate();
            members.setBytes(1, uuid(townId));
            members.executeUpdate();
            visitors.setBytes(1, uuid(townId));
            visitors.executeUpdate();
            invitations.setBytes(1, uuid(townId));
            invitations.executeUpdate();
            joinApplications.setBytes(1, uuid(townId));
            joinApplications.executeUpdate();
        }
        String action = mayorOnly ? "TOWN_DISBAND_PREPARE"
                : alreadyPrepared ? "TOWN_DELETE_RETRY" : "TOWN_DELETE_PREPARE";
        try (PreparedStatement buffs = connection.prepareStatement("""
                UPDATE active_buffs SET status = 'CANCELLED', last_error = '小镇已归档'
                 WHERE town_id = ? AND status = 'ACTIVE'
                """)) {
            buffs.setBytes(1, uuid(townId));
            buffs.executeUpdate();
        }
        TownPersistence.audit(connection, null, actorId, actorName, action, "TOWN", townId.toString(), reason,
                "已安全归档；名称、小镇代码和区块保持锁定，等待移除投影");
        return current;
    }

    private static void snapshotMembers(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO town_archived_members
                    (town_id, player_uuid, role, joined_at, last_active_at, rules_revision)
                SELECT town_id, player_uuid, role, joined_at, last_active_at, rules_revision
                  FROM town_members WHERE town_id = ?
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.executeUpdate();
        }
        try (PreparedStatement votes = connection.prepareStatement("""
                UPDATE governance_votes SET status = 'CANCELLED', settled_at = ?,
                       cancelled_reason = '小镇已归档'
                 WHERE town_id = ? AND status = 'OPEN'
                """);
             PreparedStatement transfers = connection.prepareStatement("""
                     UPDATE mayor_transfer_requests SET status = 'CANCELLED', decided_at = ?
                      WHERE town_id = ? AND status = 'PENDING'
                     """)) {
            long now = Instant.now().toEpochMilli();
            votes.setLong(1, now);
            votes.setBytes(2, uuid(townId));
            votes.executeUpdate();
            transfers.setLong(1, now);
            transfers.setBytes(2, uuid(townId));
            transfers.executeUpdate();
        }
    }

    private static boolean townReuseBlocked(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT reuse_blocked FROM towns WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("小镇不存在");
                }
                return result.getBoolean("reuse_blocked");
            }
        }
    }

}

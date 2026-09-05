package org.allivlisey.tianjitown.storage.town;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownRepository.ConflictException;

import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.timestamp;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Player invitation creation and response transactions. */
final class TownInvitationStore {
    private final TownDatabase database;

    TownInvitationStore(TownDatabase database) {
        this.database = database;
    }

    InvitationSnapshot invite(UUID townId, UUID mayorId, UUID playerId, Duration lifetime) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            TownPersistence.requireManager(connection, townId, mayorId);
            if (TownPersistence.memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("目标玩家已经属于一个小镇");
            }
            UUID invitationId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(lifetime);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_invitations
                        (invitation_id, town_id, player_uuid, invited_by, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (town_id, player_uuid) DO UPDATE SET
                        invitation_id = excluded.invitation_id,
                        invited_by = excluded.invited_by, expires_at = excluded.expires_at,
                        accepted_at = NULL, revoked_at = NULL
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(playerId));
                statement.setBytes(4, uuid(mayorId));
                statement.setTimestamp(5, timestamp(expiresAt));
                statement.executeUpdate();
            }
            TownPersistence.audit(connection, null, mayorId, mayorId.toString(), "MEMBER_INVITE", "TOWN",
                    townId.toString(), "管理组邀请成员", playerId.toString());
            return new InvitationSnapshot(invitationId, townId,
                    TownPersistence.requireTown(connection, townId).profile().name(), mayorId, expiresAt);
        });
    }

    InvitationSnapshot adminInvite(UUID townId, UUID playerId, UUID actorId,
                                           String actorName, Duration lifetime, String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        return database.transaction(connection -> {
            TownSnapshot town = TownPersistence.requireTown(connection, townId);
            if (town.status() != TownStatus.ACTIVE) {
                throw new ConflictException("只有正常运行的小镇可以邀请成员");
            }
            if (TownPersistence.memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("目标玩家已经属于一个小镇");
            }
            UUID invitationId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(lifetime);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_invitations
                        (invitation_id, town_id, player_uuid, invited_by, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (town_id, player_uuid) DO UPDATE SET
                        invitation_id = excluded.invitation_id,
                        invited_by = excluded.invited_by, expires_at = excluded.expires_at,
                        accepted_at = NULL, revoked_at = NULL
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(playerId));
                statement.setBytes(4, uuid(actorId));
                statement.setTimestamp(5, timestamp(expiresAt));
                statement.executeUpdate();
            }
            TownPersistence.audit(connection, null, actorId, actorName, "MEMBER_ADMIN_INVITE", "TOWN",
                    townId.toString(), reason, playerId.toString());
            return new InvitationSnapshot(invitationId, townId, town.profile().name(), actorId,
                    expiresAt);
        });
    }

    List<InvitationSnapshot> listInvitations(UUID playerId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<InvitationSnapshot> invitations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT i.invitation_id, i.town_id, t.name, i.invited_by, i.expires_at
                      FROM town_invitations i JOIN towns t ON t.town_id = i.town_id
                     WHERE i.player_uuid = ? AND i.accepted_at IS NULL AND i.revoked_at IS NULL
                       AND i.expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                       AND t.status = 'ACTIVE'
                     ORDER BY i.created_at DESC, i.invitation_id
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        invitations.add(new InvitationSnapshot(readUuid(result, "invitation_id"),
                                readUuid(result, "town_id"), result.getString("name"),
                                readUuid(result, "invited_by"), result.getTimestamp("expires_at").toInstant()));
                    }
                }
            }
            return List.copyOf(invitations);
        });
    }

    UUID acceptInvitation(UUID invitationId, UUID playerId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            if (TownPersistence.memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("你已经属于一个小镇");
            }
            UUID townId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT i.town_id FROM town_invitations i JOIN towns t ON t.town_id = i.town_id
                     WHERE i.invitation_id = ? AND i.player_uuid = ? AND i.accepted_at IS NULL
                       AND i.revoked_at IS NULL
                       AND i.expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                       AND t.status = 'ACTIVE'
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                statement.setBytes(2, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new ConflictException("邀请不存在、已过期或已处理");
                    }
                    townId = readUuid(result, "town_id");
                }
            }
            try (PreparedStatement member = connection.prepareStatement("""
                    INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, 'MEMBER')
                    """);
                 PreparedStatement invitation = connection.prepareStatement("""
                         UPDATE town_invitations
                            SET accepted_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE invitation_id = ?
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                member.executeUpdate();
                invitation.setBytes(1, uuid(invitationId));
                invitation.executeUpdate();
            }
            TownPersistence.audit(connection, null, playerId, playerId.toString(), "MEMBER_JOIN", "TOWN",
                    townId.toString(), "玩家接受邀请", "");
            return townId;
        });
    }

    UUID declineInvitation(UUID invitationId, UUID playerId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            UUID townId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id FROM town_invitations
                     WHERE invitation_id = ? AND player_uuid = ? AND accepted_at IS NULL
                       AND revoked_at IS NULL
                       AND expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                statement.setBytes(2, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new ConflictException("邀请不存在、已过期或已处理");
                    }
                    townId = readUuid(result, "town_id");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_invitations
                       SET revoked_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                     WHERE invitation_id = ?
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                TownPersistence.requireUpdated(statement, "邀请不存在、已过期或已处理");
            }
            TownPersistence.audit(connection, null, playerId, playerId.toString(), "MEMBER_INVITE_DECLINE", "TOWN",
                    townId.toString(), "玩家拒绝邀请", invitationId.toString());
            return townId;
        });
    }
}

package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TownResidenceName;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownRepository.ConflictException;
import org.allivlisey.tianjitown.storage.town.TownRepository.NotFoundException;

import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Connection-scoped queries and checks shared by town workflows. */
final class TownPersistence {
    static final String RULE_SEPARATOR = "\u001e";

    private TownPersistence() {}

    static List<JoinApplicationSnapshot> listJoinApplicationsForPlayer(Connection connection,
                                                                        UUID applicantId)
            throws SQLException {
        List<JoinApplicationSnapshot> applications = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT j.*, t.name AS town_name
                  FROM town_join_applications j JOIN towns t ON t.town_id = j.town_id
                 WHERE j.applicant_uuid = ? AND j.status = 'PENDING'
                   AND j.expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                   AND t.status = 'ACTIVE'
                 ORDER BY j.created_at DESC, j.join_application_id
                """)) {
            statement.setBytes(1, uuid(applicantId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    applications.add(readJoinApplication(result));
                }
            }
        }
        return List.copyOf(applications);
    }

    static List<JoinApplicationSnapshot> listJoinApplicationsForTown(Connection connection,
                                                                      UUID townId)
            throws SQLException {
        List<JoinApplicationSnapshot> applications = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT j.*, t.name AS town_name
                  FROM town_join_applications j JOIN towns t ON t.town_id = j.town_id
                 WHERE j.town_id = ? AND j.status = 'PENDING'
                   AND j.expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                   AND t.status = 'ACTIVE'
                 ORDER BY j.created_at, j.join_application_id
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    applications.add(readJoinApplication(result));
                }
            }
        }
        return List.copyOf(applications);
    }

    static JoinApplicationSnapshot readJoinApplication(ResultSet result) throws SQLException {
        byte[] decidedBy = result.getBytes("decided_by");
        Timestamp decidedAt = result.getTimestamp("decided_at");
        return new JoinApplicationSnapshot(readUuid(result, "join_application_id"),
                readUuid(result, "town_id"), result.getString("town_name"),
                readUuid(result, "applicant_uuid"),
                JoinApplicationSnapshot.Status.valueOf(result.getString("status")),
                result.getTimestamp("expires_at").toInstant(),
                decidedBy == null ? null : uuid(decidedBy),
                decidedAt == null ? null : decidedAt.toInstant(),
                result.getTimestamp("created_at").toInstant());
    }

    static int pendingJoinApplicationCount(Connection connection, UUID applicantId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS amount FROM town_join_applications
                 WHERE applicant_uuid = ? AND status = 'PENDING'
                   AND expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                """)) {
            statement.setBytes(1, uuid(applicantId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("amount") : 0;
            }
        }
    }

    static void requireDatabaseSiteAvailable(Connection connection, UUID applicationId,
                                              InitialTerritory territory, int bufferChunks)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT application_id FROM site_reservations
                 WHERE world_uuid = ? AND application_id <> ? AND released_at IS NULL
                   AND expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                   AND min_chunk_x <= ? AND max_chunk_x >= ?
                   AND min_chunk_z <= ? AND max_chunk_z >= ?
                 LIMIT 1
                """)) {
            statement.setBytes(1, uuid(territory.center().worldId()));
            statement.setBytes(2, uuid(applicationId));
            statement.setInt(3, territory.maximumChunkX() + bufferChunks);
            statement.setInt(4, territory.minimumChunkX() - bufferChunks);
            statement.setInt(5, territory.maximumChunkZ() + bufferChunks);
            statement.setInt(6, territory.minimumChunkZ() - bufferChunks);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("选址与其他申请的有效预留重叠或距离过近");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.unit_id FROM territory_units u JOIN towns t ON t.town_id = u.town_id
                 WHERE u.world_uuid = ? AND u.reuse_blocked = TRUE
                   AND u.center_chunk_x BETWEEN ? AND ? AND u.center_chunk_z BETWEEN ? AND ?
                 LIMIT 1
                """)) {
            statement.setBytes(1, uuid(territory.center().worldId()));
            statement.setInt(2, territory.minimumChunkX()
                    - InitialTerritory.RADIUS - bufferChunks);
            statement.setInt(3, territory.maximumChunkX()
                    + InitialTerritory.RADIUS + bufferChunks);
            statement.setInt(4, territory.minimumChunkZ()
                    - InitialTerritory.RADIUS - bufferChunks);
            statement.setInt(5, territory.maximumChunkZ()
                    + InitialTerritory.RADIUS + bufferChunks);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("选址与已有小镇领地重叠或距离过近");
                }
            }
        }
    }

    static void ensureNameAvailable(Connection connection, ApplicationText text,
                                     UUID ignoredApplicationId) throws SQLException {
        ensureTownNameAvailable(connection, text, null);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT application_id FROM town_applications
                 WHERE (active_name = ? OR active_residence_name = ?)
                   AND (? IS NULL OR application_id <> ?) LIMIT 1
                """)) {
            statement.setString(1, text.normalizedName());
            statement.setString(2, text.normalizedResidenceName());
            if (ignoredApplicationId == null) {
                statement.setNull(3, java.sql.Types.BINARY);
                statement.setNull(4, java.sql.Types.BINARY);
            } else {
                statement.setBytes(3, uuid(ignoredApplicationId));
                statement.setBytes(4, uuid(ignoredApplicationId));
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("小镇名称或小镇代码已被其他申请占用");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT unit_id FROM territory_units WHERE residence_name = ? AND reuse_blocked = TRUE LIMIT 1")) {
            statement.setString(1, TownResidenceName.initial(text.residenceName()));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("小镇代码已被现有小镇使用");
                }
            }
        }
    }

    static void ensureTownNameAvailable(Connection connection, ApplicationText text,
                                         UUID ignoredTownId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT town_id FROM towns
                 WHERE reuse_blocked = TRUE
                   AND (normalized_name = ?)
                  AND (? IS NULL OR town_id <> ?) LIMIT 1
                """)) {
            statement.setString(1, text.normalizedName());
            if (ignoredTownId == null) {
                statement.setNull(2, java.sql.Types.BINARY);
                statement.setNull(3, java.sql.Types.BINARY);
            } else {
                statement.setBytes(2, uuid(ignoredTownId));
                statement.setBytes(3, uuid(ignoredTownId));
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("小镇名称或代码已被现有小镇使用");
                }
            }
        }
    }

    static Optional<ApplicationSnapshot> findApplication(Connection connection, UUID applicationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.*, r.world_uuid, r.world_name, r.center_chunk_x, r.center_chunk_z,
                       r.expires_at AS reservation_expires_at
                  FROM town_applications a
                  LEFT JOIN site_reservations r ON r.application_id = a.application_id
                                               AND r.released_at IS NULL
                 WHERE a.application_id = ?
                """)) {
            statement.setBytes(1, uuid(applicationId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(withInitialMembers(connection, readApplication(result)))
                        : Optional.empty();
            }
        }
    }

    static ApplicationSnapshot withInitialMembers(Connection connection,
                                                   ApplicationSnapshot application)
            throws SQLException {
        List<InitialMemberConfirmation> members = new ArrayList<>(2);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, confirmation_status, responded_at
                  FROM application_initial_members
                 WHERE application_id = ? ORDER BY created_at, player_uuid
                """)) {
            statement.setBytes(1, uuid(application.id()));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Timestamp responded = result.getTimestamp("responded_at");
                    members.add(new InitialMemberConfirmation(
                            readUuid(result, "player_uuid"),
                            InitialMemberConfirmation.Status.valueOf(
                                    result.getString("confirmation_status")),
                            responded == null ? null : responded.toInstant()));
                }
            }
        }
        return new ApplicationSnapshot(application.id(), application.applicantId(),
                application.text(), application.status(), application.territory(),
                application.reservationExpiresAt(), application.townId(),
                application.reviewMessage(), application.lastError(), members,
                application.applicationFeeMinor(), application.applicationFeeStatus(),
                application.version(),
                application.submittedAt(),
                application.createdAt(), application.updatedAt());
    }

    static void requireConfirmedInitialMembers(Connection connection,
                                                ApplicationSnapshot application)
            throws SQLException {
        ApplicationSnapshot current = application.initialMembers().isEmpty()
                ? withInitialMembers(connection, application) : application;
        if (!current.initialMembersConfirmed()) {
            throw new ConflictException("两名初始成员均确认后才能提交或批准申请");
        }
        for (InitialMemberConfirmation member : current.initialMembers()) {
            if (memberTownId(connection, member.playerId()).isPresent()) {
                throw new ConflictException("初始成员已经加入其他小镇，请修改申请名单");
            }
        }
    }

    static ApplicationSnapshot requireApplication(Connection connection, UUID applicationId)
            throws SQLException {
        return findApplication(connection, applicationId)
                .orElseThrow(() -> new NotFoundException("找不到申请 " + applicationId));
    }

    static ApplicationSnapshot readApplication(ResultSet result) throws SQLException {
        byte[] worldBytes = result.getBytes("world_uuid");
        InitialTerritory territory = worldBytes == null ? null : new InitialTerritory(new ChunkPosition(
                uuid(worldBytes), result.getString("world_name"), result.getInt("center_chunk_x"),
                result.getInt("center_chunk_z")));
        Timestamp reservationExpiry = result.getTimestamp("reservation_expires_at");
        byte[] townBytes = result.getBytes("town_id");
        return new ApplicationSnapshot(readUuid(result, "application_id"),
                readUuid(result, "applicant_uuid"), readApplicationText(result),
                ApplicationStatus.valueOf(result.getString("status")), territory,
                reservationExpiry == null ? null : reservationExpiry.toInstant(),
                townBytes == null ? null : uuid(townBytes), result.getString("review_message"),
                result.getString("last_error"), List.of(),
                result.getLong("application_fee_minor"),
                ApplicationSnapshot.FeeStatus.valueOf(
                        result.getString("application_fee_status")), result.getLong("version"),
                result.getTimestamp("submitted_at") == null ? null
                        : result.getTimestamp("submitted_at").toInstant(),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    static Optional<TownSnapshot> findTown(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.*, u.world_uuid, u.world_name, u.center_chunk_x, u.center_chunk_z,
                       u.residence_name, u.projection_status, u.projection_error
                  FROM towns t LEFT JOIN territory_units u ON u.town_id = t.town_id
                                                   AND u.grid_x = 0 AND u.grid_z = 0
                 WHERE t.town_id = ?
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                byte[] worldBytes = result.getBytes("world_uuid");
                InitialTerritory territory = worldBytes == null ? null
                        : new InitialTerritory(new ChunkPosition(uuid(worldBytes),
                        result.getString("world_name"), result.getInt("center_chunk_x"),
                        result.getInt("center_chunk_z")));
                return Optional.of(new TownSnapshot(readUuid(result, "town_id"), readTownText(result),
                        TownStatus.valueOf(result.getString("status")),
                        readUuid(result, "mayor_uuid"), result.getLong("rules_revision"),
                        result.getLong("version"),
                        result.getTimestamp("created_at").toInstant(), territory,
                        result.getString("projection_status"), result.getString("projection_error")));
            }
        }
    }

    static TownSnapshot requireTown(Connection connection, UUID townId) throws SQLException {
        return findTown(connection, townId)
                .orElseThrow(() -> new NotFoundException("找不到小镇 " + townId));
    }

    static Optional<UUID> memberTownId(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT town_id FROM town_members WHERE player_uuid = ? LIMIT 1")) {
            statement.setBytes(1, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readUuid(result, "town_id")) : Optional.empty();
            }
        }
    }

    static void requireManager(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        if (!canReviewJoinApplications(connection, townId, playerId)) {
            throw new ConflictException("只有正常运行小镇的镇长或副镇长可以执行该操作");
        }
    }

    static boolean canReviewJoinApplications(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members m JOIN towns t ON t.town_id = m.town_id
                 WHERE m.town_id = ? AND m.player_uuid = ?
                   AND m.role IN ('MAYOR', 'DEPUTY_MAYOR') AND t.status = 'ACTIVE' LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static void updateStatus(Connection connection, UUID applicationId, long version,
                              ApplicationStatus status, String reviewMessage, String lastError)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE town_applications SET status = ?, review_message = COALESCE(?, review_message),
                    last_error = ?, version = version + 1
                 WHERE application_id = ? AND version = ?
                """)) {
            statement.setString(1, status.name());
            statement.setString(2, reviewMessage);
            statement.setString(3, lastError);
            statement.setBytes(4, uuid(applicationId));
            statement.setLong(5, version);
            requireUpdated(statement, "申请已被其他操作修改，请重新读取后再试");
        }
    }

    static void releaseReservation(Connection connection, UUID applicationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE site_reservations
                   SET released_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                 WHERE application_id = ? AND released_at IS NULL
                """)) {
            statement.setBytes(1, uuid(applicationId));
            statement.executeUpdate();
        }
    }

    static void insertReview(Connection connection, UUID applicationId, UUID reviewerId,
                              String action, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_reviews (application_id, reviewer_uuid, action, reason)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setBytes(1, uuid(applicationId));
            statement.setBytes(2, uuid(reviewerId));
            statement.setString(3, action);
            statement.setString(4, reason);
            statement.executeUpdate();
        }
    }

    static void audit(Connection connection, String idempotencyKey, UUID actorId, String actorName,
                       String action, String targetType, String targetId, String reason, String detail)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_logs
                    (idempotency_key, actor_uuid, actor_name, action, target_type, target_id, reason, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, idempotencyKey);
            statement.setBytes(2, actorId == null ? null : uuid(actorId));
            statement.setString(3, actorName);
            statement.setString(4, action);
            statement.setString(5, targetType);
            statement.setString(6, targetId);
            statement.setString(7, reason == null ? "" : reason);
            statement.setString(8, detail == null ? "" : detail);
            statement.executeUpdate();
        }
    }

    static ApplicationText readApplicationText(ResultSet result) throws SQLException {
        return readText(result, result.getString("residence_name"));
    }

    static ApplicationText readTownText(ResultSet result) throws SQLException {
        return readText(result, TownResidenceName.key(result.getString("residence_name")));
    }

    static ApplicationText readText(ResultSet result, String residenceName) throws SQLException {
        String rules = result.getString("rules_text");
        return new ApplicationText(result.getString("name"),
                residenceName, result.getString("description"),
                rules == null || rules.isEmpty() ? List.of() : List.of(rules.split(RULE_SEPARATOR, -1)));
    }

    static void requireUpdated(PreparedStatement statement, String message) throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new ConflictException(message);
        }
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("必须填写原因");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("原因不能超过 500 字符");
        }
    }
}

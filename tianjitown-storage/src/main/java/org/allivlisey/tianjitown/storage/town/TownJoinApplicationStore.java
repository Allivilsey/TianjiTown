package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownRepository.ConflictException;
import org.allivlisey.tianjitown.storage.town.TownRepository.NotFoundException;

import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.timestamp;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Join application submission, review and expiry. */
final class TownJoinApplicationStore {
    private final TownDatabase database;

    TownJoinApplicationStore(TownDatabase database) {
        this.database = database;
    }

    JoinApplicationSnapshot applyToTown(UUID townId, UUID applicantId, Duration lifetime,
                                               Duration rejectionCooldown, Duration leaveCooldown,
                                               int maximumPending) {
        database.requireWorkerThread();
        Objects.requireNonNull(lifetime, "lifetime");
        Objects.requireNonNull(rejectionCooldown, "rejectionCooldown");
        Objects.requireNonNull(leaveCooldown, "leaveCooldown");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("入镇申请有效期必须大于 0");
        }
        if (maximumPending < 1) {
            throw new IllegalArgumentException("入镇申请数量上限必须大于 0");
        }
        return database.transaction(connection -> {
            expireJoinApplications(connection);
            TownSnapshot town = TownPersistence.requireTown(connection, townId);
            if (town.status() != TownStatus.ACTIVE) {
                throw new ConflictException("只能申请加入正常运行的小镇");
            }
            if (TownPersistence.memberTownId(connection, applicantId).isPresent()) {
                throw new ConflictException("你已经属于一个小镇");
            }
            if (findOpenApplicationByApplicant(connection, applicantId).isPresent()) {
                throw new ConflictException("你正在申请建立小镇，不能同时申请加入其他小镇");
            }
            if (recentVoluntaryDeparture(connection, applicantId, leaveCooldown)) {
                throw new ConflictException("退出小镇后的 24 小时冷却尚未结束");
            }
            if (recentJoinRejection(connection, townId, applicantId, rejectionCooldown)) {
                throw new ConflictException("被该小镇拒绝后的 24 小时冷却尚未结束");
            }
            if (pendingJoinApplicationExists(connection, townId, applicantId)) {
                throw new ConflictException("你已经提交过该小镇的入镇申请");
            }
            if (TownPersistence.pendingJoinApplicationCount(connection, applicantId) >= maximumPending) {
                throw new ConflictException("你同时最多只能申请 " + maximumPending + " 个小镇");
            }
            UUID applicationId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(lifetime);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_join_applications
                        (join_application_id, town_id, applicant_uuid, status, expires_at,
                         rules_revision)
                    VALUES (?, ?, ?, 'PENDING', ?, ?)
                    """)) {
                statement.setBytes(1, uuid(applicationId));
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(applicantId));
                statement.setTimestamp(4, timestamp(expiresAt));
                statement.setLong(5, town.rulesRevision());
                statement.executeUpdate();
            }
            TownPersistence.audit(connection, null, applicantId, applicantId.toString(),
                    "MEMBER_APPLICATION_CREATE", "TOWN", townId.toString(),
                    "玩家申请加入小镇", applicationId.toString());
            return requireJoinApplication(connection, applicationId);
        });
    }

    List<JoinApplicationSnapshot> listJoinApplications(UUID applicantId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            expireJoinApplications(connection);
            return TownPersistence.listJoinApplicationsForPlayer(connection, applicantId);
        });
    }

    List<JoinApplicationSnapshot> listTownJoinApplications(UUID townId, UUID mayorId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            expireJoinApplications(connection);
            TownPersistence.requireManager(connection, townId, mayorId);
            return TownPersistence.listJoinApplicationsForTown(connection, townId);
        });
    }

    JoinApplicationSnapshot approveJoinApplication(UUID applicationId, UUID mayorId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            expireJoinApplications(connection);
            JoinApplicationSnapshot application = requireJoinApplication(connection, applicationId);
            TownPersistence.requireManager(connection, application.townId(), mayorId);
            requirePendingJoinApplication(application);
            if (TownPersistence.memberTownId(connection, application.applicantId()).isPresent()) {
                throw new ConflictException("申请人已经属于一个小镇");
            }
            if (findOpenApplicationByApplicant(connection, application.applicantId()).isPresent()) {
                throw new ConflictException("申请人正在申请建立小镇，暂时不能批准入镇");
            }
            try (PreparedStatement member = connection.prepareStatement("""
                    INSERT INTO town_members (town_id, player_uuid, role, rules_revision)
                    SELECT town_id, applicant_uuid, 'MEMBER', rules_revision
                      FROM town_join_applications WHERE join_application_id = ?
                    """);
                 PreparedStatement approved = connection.prepareStatement("""
                         UPDATE town_join_applications
                            SET status = 'APPROVED', decided_by = ?,
                                decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE join_application_id = ? AND status = 'PENDING'
                            AND expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                         """);
                 PreparedStatement cancelOthers = connection.prepareStatement("""
                         UPDATE town_join_applications
                            SET status = 'CANCELLED', decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE applicant_uuid = ? AND join_application_id <> ? AND status = 'PENDING'
                         """)) {
                member.setBytes(1, uuid(applicationId));
                member.executeUpdate();
                approved.setBytes(1, uuid(mayorId));
                approved.setBytes(2, uuid(applicationId));
                TownPersistence.requireUpdated(approved, "入镇申请已过期或已被处理");
                cancelOthers.setBytes(1, uuid(application.applicantId()));
                cancelOthers.setBytes(2, uuid(applicationId));
                cancelOthers.executeUpdate();
            }
            TownPersistence.audit(connection, null, mayorId, mayorId.toString(),
                    "MEMBER_APPLICATION_APPROVE", "TOWN", application.townId().toString(),
                    "管理组批准入镇申请", application.applicantId().toString());
            return requireJoinApplication(connection, applicationId);
        });
    }

    JoinApplicationSnapshot rejectJoinApplication(UUID applicationId, UUID mayorId) {
        database.requireWorkerThread();
        return decideJoinApplication(applicationId, mayorId, "REJECTED",
                "MEMBER_APPLICATION_REJECT", "管理组拒绝入镇申请");
    }

    JoinApplicationSnapshot cancelJoinApplication(UUID applicationId, UUID applicantId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            expireJoinApplications(connection);
            JoinApplicationSnapshot application = requireJoinApplication(connection, applicationId);
            if (!application.applicantId().equals(applicantId)) {
                throw new ConflictException("只能撤回自己的入镇申请");
            }
            requirePendingJoinApplication(application);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_join_applications
                       SET status = 'CANCELLED', decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                     WHERE join_application_id = ? AND status = 'PENDING'
                    """)) {
                statement.setBytes(1, uuid(applicationId));
                TownPersistence.requireUpdated(statement, "入镇申请已过期或已被处理");
            }
            TownPersistence.audit(connection, null, applicantId, applicantId.toString(),
                    "MEMBER_APPLICATION_CANCEL", "TOWN", application.townId().toString(),
                    "玩家撤回入镇申请", applicationId.toString());
            return requireJoinApplication(connection, applicationId);
        });
    }

    private JoinApplicationSnapshot decideJoinApplication(UUID applicationId, UUID mayorId,
                                                           String status, String action,
                                                           String reason) {
        return database.transaction(connection -> {
            expireJoinApplications(connection);
            JoinApplicationSnapshot application = requireJoinApplication(connection, applicationId);
            TownPersistence.requireManager(connection, application.townId(), mayorId);
            requirePendingJoinApplication(application);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_join_applications
                       SET status = ?, decided_by = ?,
                           decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                     WHERE join_application_id = ? AND status = 'PENDING'
                       AND expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER)
                    """)) {
                statement.setString(1, status);
                statement.setBytes(2, uuid(mayorId));
                statement.setBytes(3, uuid(applicationId));
                TownPersistence.requireUpdated(statement, "入镇申请已过期或已被处理");
            }
            TownPersistence.audit(connection, null, mayorId, mayorId.toString(), action, "TOWN",
                    application.townId().toString(), reason, application.applicantId().toString());
            return requireJoinApplication(connection, applicationId);
        });
    }

    private static void expireJoinApplications(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE town_join_applications
                   SET status = 'EXPIRED', decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                 WHERE status = 'PENDING'
                   AND expires_at <= CAST(unixepoch('subsec') * 1000 AS INTEGER)
                """)) {
            statement.executeUpdate();
        }
    }

    private Optional<JoinApplicationSnapshot> findJoinApplication(Connection connection,
                                                                  UUID applicationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT j.*, t.name AS town_name
                  FROM town_join_applications j JOIN towns t ON t.town_id = j.town_id
                 WHERE j.join_application_id = ?
                """)) {
            statement.setBytes(1, uuid(applicationId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(TownPersistence.readJoinApplication(result)) : Optional.empty();
            }
        }
    }

    private JoinApplicationSnapshot requireJoinApplication(Connection connection, UUID applicationId)
            throws SQLException {
        return findJoinApplication(connection, applicationId)
                .orElseThrow(() -> new NotFoundException("找不到入镇申请 " + applicationId));
    }

    private static void requirePendingJoinApplication(JoinApplicationSnapshot application) {
        if (application.status() != JoinApplicationSnapshot.Status.PENDING
                || !application.expiresAt().isAfter(Instant.now())) {
            throw new ConflictException("入镇申请已过期或已被处理");
        }
    }

    private static boolean pendingJoinApplicationExists(Connection connection, UUID townId,
                                                        UUID applicantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_join_applications
                 WHERE town_id = ? AND applicant_uuid = ? AND status = 'PENDING'
                   AND expires_at > CAST(unixepoch('subsec') * 1000 AS INTEGER) LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(applicantId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean recentJoinRejection(Connection connection, UUID townId, UUID applicantId,
                                               Duration cooldown) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_join_applications
                 WHERE town_id = ? AND applicant_uuid = ? AND status = 'REJECTED'
                   AND decided_at > ? LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(applicantId));
            statement.setTimestamp(3, timestamp(Instant.now().minus(cooldown)));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean recentVoluntaryDeparture(Connection connection, UUID applicantId,
                                                     Duration cooldown) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_member_departures
                 WHERE player_uuid = ? AND departure_type = 'VOLUNTARY'
                   AND departed_at > ? LIMIT 1
                """)) {
            statement.setBytes(1, uuid(applicantId));
            statement.setTimestamp(2, timestamp(Instant.now().minus(cooldown)));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Optional<UUID> findOpenApplicationByApplicant(Connection connection, UUID applicantId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT application_id FROM town_applications WHERE active_applicant = ? LIMIT 1
                """)) {
            statement.setBytes(1, uuid(applicantId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readUuid(result, "application_id")) : Optional.empty();
            }
        }
    }
}

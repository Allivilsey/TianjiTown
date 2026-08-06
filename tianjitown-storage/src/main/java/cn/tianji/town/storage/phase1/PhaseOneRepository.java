package cn.tianji.town.storage.phase1;

import cn.tianji.town.core.application.ApplicationActor;
import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.application.ApplicationWorkflow;
import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.land.TownResidenceName;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.core.town.TownStatus;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class PhaseOneRepository {
    private static final String RULE_SEPARATOR = "\u001e";
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public PhaseOneRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    public ApplicationSnapshot createDraft(UUID applicantId, ApplicationText text,
                                           Duration cooldown) {
        requireWorkerThread();
        text.requireValid();
        Objects.requireNonNull(cooldown, "cooldown");
        return transaction(connection -> {
            if (memberTownId(connection, applicantId).isPresent()) {
                throw new ConflictException("你已经属于一个小镇");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM town_applications
                     WHERE applicant_uuid = ? AND status IN ('REJECTED', 'CANCELLED')
                       AND updated_at > ? LIMIT 1 FOR UPDATE
                    """)) {
                statement.setBytes(1, uuid(applicantId));
                statement.setTimestamp(2, timestamp(Instant.now().minus(cooldown)));
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new ConflictException("申请冷却尚未结束");
                    }
                }
            }
            ensureNameAvailable(connection, text, null);
            UUID applicationId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_applications
                        (application_id, applicant_uuid, name, normalized_name, short_name,
                         normalized_short_name, residence_name, description, rules_text, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                    """)) {
                setApplicationText(statement, 3, text);
                statement.setBytes(1, uuid(applicationId));
                statement.setBytes(2, uuid(applicantId));
                statement.executeUpdate();
            }
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_CREATE",
                    "APPLICATION", applicationId.toString(), "玩家创建草稿", text.name());
            return requireApplication(connection, applicationId, false);
        });
    }

    public ApplicationSnapshot updateApplicationText(UUID applicationId, UUID applicantId,
                                                     ApplicationText text, long expectedVersion) {
        requireWorkerThread();
        text.requireValid();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId, true);
            requireApplicant(current, applicantId);
            if (current.status() != ApplicationStatus.DRAFT
                    && current.status() != ApplicationStatus.SITE_SELECTED
                    && current.status() != ApplicationStatus.NEED_CHANGES) {
                throw new ConflictException("当前申请状态不允许修改资料");
            }
            ensureNameAvailable(connection, text, applicationId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET name = ?, normalized_name = ?, short_name = ?, normalized_short_name = ?,
                           residence_name = ?, description = ?, rules_text = ?, version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                setApplicationText(statement, 1, text);
                statement.setBytes(8, uuid(applicationId));
                statement.setLong(9, expectedVersion);
                requireUpdated(statement, "申请资料已被其他操作修改，请重新打开");
            }
            return requireApplication(connection, applicationId, false);
        });
    }

    public ApplicationSnapshot selectSite(UUID applicationId, UUID applicantId,
                                          InitialTerritory territory, Instant expiresAt,
                                          int bufferChunks) {
        requireWorkerThread();
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("选址预留到期时间必须晚于当前时间");
        }
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId, true);
            requireApplicant(current, applicantId);
            if (current.status() != ApplicationStatus.DRAFT
                    && current.status() != ApplicationStatus.SITE_SELECTED
                    && current.status() != ApplicationStatus.NEED_CHANGES) {
                throw new ConflictException("当前申请状态不允许选址");
            }
            requireDatabaseSiteAvailable(connection, applicationId, territory, bufferChunks);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO site_reservations
                        (reservation_id, application_id, world_uuid, world_name, center_chunk_x,
                         center_chunk_z, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE world_uuid = VALUES(world_uuid), world_name = VALUES(world_name),
                        center_chunk_x = VALUES(center_chunk_x), center_chunk_z = VALUES(center_chunk_z),
                        min_chunk_x = VALUES(min_chunk_x), max_chunk_x = VALUES(max_chunk_x),
                        min_chunk_z = VALUES(min_chunk_z), max_chunk_z = VALUES(max_chunk_z),
                        expires_at = VALUES(expires_at), released_at = NULL
                    """)) {
                statement.setBytes(1, uuid(UUID.randomUUID()));
                statement.setBytes(2, uuid(applicationId));
                setTerritory(statement, 3, territory);
                statement.setTimestamp(11, timestamp(expiresAt));
                statement.executeUpdate();
            }
            ApplicationStatus target = ApplicationStatus.SITE_SELECTED;
            if (current.status() != target) {
                ApplicationWorkflow.requireAllowed(current.status(), target, ApplicationActor.APPLICANT);
            }
            updateStatus(connection, applicationId, current.version(), target, null, null);
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_SITE_SELECT",
                    "APPLICATION", applicationId.toString(), "玩家选择初始领地",
                    territory.center().worldName() + ":" + territory.center().x() + ","
                            + territory.center().z());
            return requireApplication(connection, applicationId, false);
        });
    }

    public ApplicationSnapshot submit(UUID applicationId, UUID applicantId) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId, true);
            requireApplicant(current, applicantId);
            ApplicationWorkflow.requireAllowed(current.status(), ApplicationStatus.SUBMITTED,
                    ApplicationActor.APPLICANT);
            if (current.territory() == null || current.reservationExpiresAt() == null
                    || !current.reservationExpiresAt().isAfter(Instant.now())) {
                throw new ConflictException("选址预留不存在或已经过期，请重新选址");
            }
            current.text().requireValid();
            ensureNameAvailable(connection, current.text(), applicationId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP(6),
                           review_message = NULL, version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                statement.setBytes(1, uuid(applicationId));
                statement.setLong(2, current.version());
                requireUpdated(statement, "申请已被其他操作修改，请重新打开");
            }
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_SUBMIT",
                    "APPLICATION", applicationId.toString(), "玩家确认提交", current.text().name());
            return requireApplication(connection, applicationId, false);
        });
    }

    public ApplicationSnapshot cancel(UUID applicationId, UUID applicantId, String reason) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId, true);
            requireApplicant(current, applicantId);
            ApplicationWorkflow.requireAllowed(current.status(), ApplicationStatus.CANCELLED,
                    ApplicationActor.APPLICANT);
            updateStatus(connection, applicationId, current.version(), ApplicationStatus.CANCELLED,
                    reason, null);
            releaseReservation(connection, applicationId);
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_CANCEL",
                    "APPLICATION", applicationId.toString(), reason, "");
            return requireApplication(connection, applicationId, false);
        });
    }

    public ApplicationSnapshot requestChanges(UUID applicationId, UUID reviewerId,
                                              String reviewerName, String reason) {
        requireReason(reason);
        return reviewTransition(applicationId, reviewerId, reviewerName,
                ApplicationStatus.NEED_CHANGES, reason, "REQUEST_CHANGES");
    }

    public ApplicationSnapshot reject(UUID applicationId, UUID reviewerId,
                                      String reviewerName, String reason) {
        requireReason(reason);
        return reviewTransition(applicationId, reviewerId, reviewerName,
                ApplicationStatus.REJECTED, reason, "REJECT");
    }

    public Provisioning beginProvision(UUID applicationId, UUID reviewerId, String reviewerName,
                                       String reason, String idempotencyKey) {
        requireWorkerThread();
        requireReason(reason);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("批准操作必须提供幂等键");
        }
        return transaction(connection -> {
            ApplicationSnapshot application = requireApplication(connection, applicationId, true);
            if (application.status() == ApplicationStatus.ACTIVE) {
                return requireProvisioningTownStatus(connection, application, TownStatus.ACTIVE);
            }
            if (application.status() == ApplicationStatus.APPROVED_PROVISIONING) {
                return requireProvisioningTownStatus(connection, application, TownStatus.PROVISIONING);
            }
            if (application.status() == ApplicationStatus.PROVISION_FAILED) {
                requireProvisioningTownStatus(connection, application, TownStatus.PROVISIONING);
                ApplicationWorkflow.requireAllowed(application.status(),
                        ApplicationStatus.APPROVED_PROVISIONING, ApplicationActor.ADMINISTRATOR);
                updateStatus(connection, applicationId, application.version(),
                        ApplicationStatus.APPROVED_PROVISIONING, null, null);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE territory_units SET projection_status = 'PENDING', projection_error = NULL
                         WHERE town_id = ?
                        """)) {
                    statement.setBytes(1, uuid(application.townId()));
                    statement.executeUpdate();
                }
                audit(connection, idempotencyKey, reviewerId, reviewerName, "PROVISION_RETRY",
                        "APPLICATION", applicationId.toString(), reason, "");
                return provisioning(connection, requireApplication(connection, applicationId, false));
            }
            ApplicationWorkflow.requireAllowed(application.status(),
                    ApplicationStatus.APPROVED_PROVISIONING, ApplicationActor.ADMINISTRATOR);
            if (memberTownId(connection, application.applicantId()).isPresent()) {
                throw new ConflictException("申请人已经加入其他小镇");
            }
            if (application.territory() == null || application.reservationExpiresAt() == null
                    || !application.reservationExpiresAt().isAfter(Instant.now())) {
                throw new ConflictException("选址预留已经过期，不能批准");
            }
            ensureNameAvailable(connection, application.text(), applicationId);
            requireDatabaseSiteAvailable(connection, applicationId, application.territory(), 0);

            UUID townId = UUID.randomUUID();
            UUID unitId = UUID.randomUUID();
            String residenceName = TownResidenceName.initial(application.text().residenceName());
            insertTown(connection, townId, application, residenceName, unitId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET status = 'APPROVED_PROVISIONING', town_id = ?, review_message = ?,
                           version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setString(2, reason);
                statement.setBytes(3, uuid(applicationId));
                statement.setLong(4, application.version());
                requireUpdated(statement, "申请已被其他管理员处理");
            }
            releaseReservation(connection, applicationId);
            insertReview(connection, applicationId, reviewerId, "APPROVE", reason);
            audit(connection, idempotencyKey, reviewerId, reviewerName, "APPLICATION_APPROVE",
                    "APPLICATION", applicationId.toString(), reason, townId.toString());
            return provisioning(connection, requireApplication(connection, applicationId, false));
        });
    }

    public ApplicationSnapshot finishProvision(UUID applicationId, boolean success, String detail) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot application = requireApplication(connection, applicationId, true);
            if (success && application.status() == ApplicationStatus.ACTIVE) {
                return application;
            }
            if (!success && application.status() == ApplicationStatus.PROVISION_FAILED) {
                return application;
            }
            ApplicationStatus target = success ? ApplicationStatus.ACTIVE
                    : ApplicationStatus.PROVISION_FAILED;
            ApplicationWorkflow.requireAllowed(application.status(), target, ApplicationActor.SYSTEM);
            updateStatus(connection, applicationId, application.version(), target, null,
                    success ? null : safeDetail(detail));
            try (PreparedStatement town = connection.prepareStatement(
                    "UPDATE towns SET status = ?, version = version + 1 WHERE town_id = ?");
                 PreparedStatement unit = connection.prepareStatement("""
                         UPDATE territory_units SET projection_status = ?, projection_error = ?
                          WHERE town_id = ?
                         """)) {
                town.setString(1, success ? TownStatus.ACTIVE.name() : TownStatus.PROVISIONING.name());
                town.setBytes(2, uuid(application.townId()));
                town.executeUpdate();
                unit.setString(1, success ? "ACTIVE" : "FAILED");
                unit.setString(2, success ? null : safeDetail(detail));
                unit.setBytes(3, uuid(application.townId()));
                unit.executeUpdate();
            }
            audit(connection, null, null, "SYSTEM", success ? "PROVISION_COMPLETE" : "PROVISION_FAIL",
                    "TOWN", application.townId().toString(), success ? "自动创建完成" : "自动创建失败",
                    safeDetail(detail));
            return requireApplication(connection, applicationId, false);
        });
    }

    public Optional<ApplicationSnapshot> findApplication(UUID applicationId) {
        requireWorkerThread();
        return query(connection -> findApplication(connection, applicationId, false));
    }

    public Optional<ApplicationSnapshot> findReviewApplicationByName(String townName) {
        requireWorkerThread();
        String normalizedName = ApplicationText.normalizeNameKey(townName);
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE normalized_name = ?
                       AND status IN ('SUBMITTED', 'UNDER_REVIEW', 'PROVISION_FAILED')
                     ORDER BY updated_at DESC LIMIT 1
                    """)) {
                statement.setString(1, normalizedName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? findApplication(connection, readUuid(result, "application_id"), false)
                            : Optional.empty();
                }
            }
        });
    }

    public Optional<ApplicationSnapshot> findOpenApplication(UUID applicantId) {
        requireWorkerThread();
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE active_applicant = ? LIMIT 1
                    """)) {
                statement.setBytes(1, uuid(applicantId));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? findApplication(connection, readUuid(result, "application_id"), false)
                            : Optional.empty();
                }
            }
        });
    }

    public List<ApplicationSnapshot> listReviewQueue(int limit) {
        requireWorkerThread();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return query(connection -> {
            List<ApplicationSnapshot> applications = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE status IN ('SUBMITTED', 'UNDER_REVIEW', 'PROVISION_FAILED')
                     ORDER BY submitted_at, created_at LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        applications.add(requireApplication(connection,
                                readUuid(result, "application_id"), false));
                    }
                }
            }
            return List.copyOf(applications);
        });
    }

    public List<ApplicationSnapshot> listApplicationsForCompletion(int limit) {
        requireWorkerThread();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return query(connection -> {
            List<ApplicationSnapshot> applications = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE status IN ('SUBMITTED', 'UNDER_REVIEW', 'PROVISION_FAILED')
                     ORDER BY updated_at DESC LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        applications.add(requireApplication(connection,
                                readUuid(result, "application_id"), false));
                    }
                }
            }
            return List.copyOf(applications);
        });
    }

    public Optional<TownSnapshot> findTown(UUID townId) {
        requireWorkerThread();
        return query(connection -> findTown(connection, townId));
    }

    public Optional<TownSnapshot> findTownByName(String townName) {
        requireWorkerThread();
        String normalizedName = ApplicationText.normalizeNameKey(townName);
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT town_id FROM towns WHERE normalized_name = ? LIMIT 1")) {
                statement.setString(1, normalizedName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? findTown(connection, readUuid(result, "town_id")) : Optional.empty();
                }
            }
        });
    }

    public Optional<TownSnapshot> findTownByMember(UUID playerId) {
        requireWorkerThread();
        return query(connection -> {
            Optional<UUID> townId = memberTownId(connection, playerId);
            return townId.isPresent() ? findTown(connection, townId.get()) : Optional.empty();
        });
    }

    public PlayerDashboard dashboard(UUID playerId) {
        requireWorkerThread();
        return query(connection -> {
            Optional<UUID> townId = memberTownId(connection, playerId);
            Optional<TownSnapshot> town = townId.isPresent()
                    ? findTown(connection, townId.get()) : Optional.empty();
            if (town.isPresent() && town.get().status() != TownStatus.ACTIVE) {
                town = Optional.empty();
            }
            Optional<ApplicationSnapshot> application;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications WHERE active_applicant = ? LIMIT 1
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    application = result.next()
                            ? findApplication(connection, readUuid(result, "application_id"), false)
                            : Optional.empty();
                }
            }
            List<InvitationSnapshot> invitations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT i.invitation_id, i.town_id, t.name, i.invited_by, i.expires_at
                      FROM town_invitations i JOIN towns t ON t.town_id = i.town_id
                     WHERE i.player_uuid = ? AND i.accepted_at IS NULL AND i.revoked_at IS NULL
                       AND i.expires_at > CURRENT_TIMESTAMP(6) AND t.status = 'ACTIVE'
                     ORDER BY i.created_at DESC
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
            return new PlayerDashboard(town.orElse(null), application.orElse(null), invitations);
        });
    }

    public List<TownSnapshot> listTowns(boolean includeArchived) {
        requireWorkerThread();
        return query(connection -> {
            List<TownSnapshot> towns = new ArrayList<>();
            String sql = includeArchived ? "SELECT town_id FROM towns ORDER BY created_at"
                    : "SELECT town_id FROM towns WHERE status <> 'ARCHIVED' ORDER BY created_at";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    findTown(connection, readUuid(result, "town_id")).ifPresent(towns::add);
                }
            }
            return List.copyOf(towns);
        });
    }

    public TownSnapshot.Page listMembers(UUID townId, int page, int pageSize) {
        requireWorkerThread();
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int safePage = Math.max(page, 0);
        return query(connection -> {
            List<TownSnapshot.Member> members = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, role, joined_at FROM town_members
                     WHERE town_id = ? ORDER BY joined_at, player_uuid LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setInt(2, safeSize + 1);
                statement.setInt(3, Math.multiplyExact(safePage, safeSize));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        members.add(new TownSnapshot.Member(readUuid(result, "player_uuid"),
                                MemberRole.valueOf(result.getString("role")),
                                result.getTimestamp("joined_at").toInstant()));
                    }
                }
            }
            boolean hasNext = members.size() > safeSize;
            if (hasNext) {
                members.removeLast();
            }
            return new TownSnapshot.Page(members, hasNext);
        });
    }

    public List<UUID> listMemberIds(UUID townId) {
        requireWorkerThread();
        return query(connection -> {
            List<UUID> members = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid FROM town_members WHERE town_id = ? ORDER BY joined_at")) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        members.add(readUuid(result, "player_uuid"));
                    }
                }
            }
            return List.copyOf(members);
        });
    }

    public Map<UUID, List<UUID>> listMemberIdsByTown() {
        requireWorkerThread();
        return query(connection -> {
            Map<UUID, List<UUID>> mutable = new java.util.LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id, player_uuid FROM town_members ORDER BY town_id, joined_at
                    """);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID townId = readUuid(result, "town_id");
                    mutable.computeIfAbsent(townId, ignored -> new ArrayList<>())
                            .add(readUuid(result, "player_uuid"));
                }
            }
            Map<UUID, List<UUID>> immutable = new java.util.LinkedHashMap<>();
            mutable.forEach((townId, members) -> immutable.put(townId, List.copyOf(members)));
            return Map.copyOf(immutable);
        });
    }

    public InvitationSnapshot invite(UUID townId, UUID mayorId, UUID playerId, Duration lifetime) {
        requireWorkerThread();
        return transaction(connection -> {
            requireMayor(connection, townId, mayorId);
            if (memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("目标玩家已经属于一个小镇");
            }
            UUID invitationId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(lifetime);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_invitations
                        (invitation_id, town_id, player_uuid, invited_by, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE invitation_id = VALUES(invitation_id),
                        invited_by = VALUES(invited_by), expires_at = VALUES(expires_at),
                        accepted_at = NULL, revoked_at = NULL
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(playerId));
                statement.setBytes(4, uuid(mayorId));
                statement.setTimestamp(5, timestamp(expiresAt));
                statement.executeUpdate();
            }
            audit(connection, null, mayorId, mayorId.toString(), "MEMBER_INVITE", "TOWN",
                    townId.toString(), "镇长邀请成员", playerId.toString());
            return new InvitationSnapshot(invitationId, townId,
                    requireTown(connection, townId).profile().name(), mayorId, expiresAt);
        });
    }

    public InvitationSnapshot adminInvite(UUID townId, UUID playerId, UUID actorId,
                                           String actorName, Duration lifetime, String reason) {
        requireWorkerThread();
        requireReason(reason);
        return transaction(connection -> {
            TownSnapshot town = requireTown(connection, townId);
            if (town.status() != TownStatus.ACTIVE) {
                throw new ConflictException("只有正常运行的小镇可以邀请成员");
            }
            if (memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("目标玩家已经属于一个小镇");
            }
            UUID invitationId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(lifetime);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_invitations
                        (invitation_id, town_id, player_uuid, invited_by, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE invitation_id = VALUES(invitation_id),
                        invited_by = VALUES(invited_by), expires_at = VALUES(expires_at),
                        accepted_at = NULL, revoked_at = NULL
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(playerId));
                statement.setBytes(4, uuid(actorId));
                statement.setTimestamp(5, timestamp(expiresAt));
                statement.executeUpdate();
            }
            audit(connection, null, actorId, actorName, "MEMBER_ADMIN_INVITE", "TOWN",
                    townId.toString(), reason, playerId.toString());
            return new InvitationSnapshot(invitationId, townId, town.profile().name(), actorId,
                    expiresAt);
        });
    }

    public List<InvitationSnapshot> listInvitations(UUID playerId) {
        requireWorkerThread();
        return query(connection -> {
            List<InvitationSnapshot> invitations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT i.invitation_id, i.town_id, t.name, i.invited_by, i.expires_at
                      FROM town_invitations i JOIN towns t ON t.town_id = i.town_id
                     WHERE i.player_uuid = ? AND i.accepted_at IS NULL AND i.revoked_at IS NULL
                       AND i.expires_at > CURRENT_TIMESTAMP(6) AND t.status = 'ACTIVE'
                     ORDER BY i.created_at DESC
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

    public UUID acceptInvitation(UUID invitationId, UUID playerId) {
        requireWorkerThread();
        return transaction(connection -> {
            if (memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("你已经属于一个小镇");
            }
            UUID townId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT i.town_id FROM town_invitations i JOIN towns t ON t.town_id = i.town_id
                     WHERE i.invitation_id = ? AND i.player_uuid = ? AND i.accepted_at IS NULL
                       AND i.revoked_at IS NULL AND i.expires_at > CURRENT_TIMESTAMP(6)
                       AND t.status = 'ACTIVE' FOR UPDATE
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
                         UPDATE town_invitations SET accepted_at = CURRENT_TIMESTAMP(6)
                          WHERE invitation_id = ?
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                member.executeUpdate();
                invitation.setBytes(1, uuid(invitationId));
                invitation.executeUpdate();
            }
            audit(connection, null, playerId, playerId.toString(), "MEMBER_JOIN", "TOWN",
                    townId.toString(), "玩家接受邀请", "");
            return townId;
        });
    }

    public UUID declineInvitation(UUID invitationId, UUID playerId) {
        requireWorkerThread();
        return transaction(connection -> {
            UUID townId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id FROM town_invitations
                     WHERE invitation_id = ? AND player_uuid = ? AND accepted_at IS NULL
                       AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP(6)
                     FOR UPDATE
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
                    UPDATE town_invitations SET revoked_at = CURRENT_TIMESTAMP(6)
                     WHERE invitation_id = ?
                    """)) {
                statement.setBytes(1, uuid(invitationId));
                requireUpdated(statement, "邀请不存在、已过期或已处理");
            }
            audit(connection, null, playerId, playerId.toString(), "MEMBER_INVITE_DECLINE", "TOWN",
                    townId.toString(), "玩家拒绝邀请", invitationId.toString());
            return townId;
        });
    }

    public void leaveTown(UUID playerId) {
        requireWorkerThread();
        transaction(connection -> {
            UUID townId = memberTownId(connection, playerId)
                    .orElseThrow(() -> new ConflictException("你不属于任何小镇"));
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM town_members WHERE town_id = ? AND player_uuid = ? AND role = 'MEMBER'
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(playerId));
                requireUpdated(statement, "镇长不能主动退出，请先由管理员转移镇长");
            }
            audit(connection, null, playerId, playerId.toString(), "MEMBER_LEAVE", "TOWN",
                    townId.toString(), "成员主动退出", "");
            return null;
        });
    }

    public void removeMember(UUID townId, UUID playerId, UUID actorId, String actorName,
                             String reason) {
        requireWorkerThread();
        requireReason(reason);
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM town_members WHERE town_id = ? AND player_uuid = ? AND role = 'MEMBER'
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(playerId));
                requireUpdated(statement, "成员不存在或目标是镇长");
            }
            audit(connection, null, actorId, actorName, "MEMBER_REMOVE", "TOWN", townId.toString(),
                    reason, playerId.toString());
            return null;
        });
    }

    public void addMember(UUID townId, UUID playerId, UUID actorId, String actorName,
                          String reason) {
        requireWorkerThread();
        requireReason(reason);
        transaction(connection -> {
            TownSnapshot town = requireTown(connection, townId);
            if (town.status() != TownStatus.ACTIVE) {
                throw new ConflictException("只有正常运行的小镇可以添加成员");
            }
            if (memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("目标玩家已经属于一个小镇");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, 'MEMBER')
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(playerId));
                statement.executeUpdate();
            }
            audit(connection, null, actorId, actorName, "MEMBER_ADMIN_ADD", "TOWN",
                    townId.toString(), reason, playerId.toString());
            return null;
        });
    }

    public void transferMayor(UUID townId, UUID newMayorId, UUID actorId, String actorName,
                              String reason) {
        requireWorkerThread();
        requireReason(reason);
        transaction(connection -> {
            TownSnapshot town = requireTown(connection, townId);
            if (town.status() == TownStatus.ARCHIVED) {
                throw new ConflictException("已归档小镇不能转移镇长");
            }
            if (!memberExists(connection, townId, newMayorId)) {
                throw new ConflictException("新镇长必须已是该镇成员");
            }
            try (PreparedStatement oldMayor = connection.prepareStatement("""
                    UPDATE town_members SET role = 'MEMBER'
                     WHERE town_id = ? AND player_uuid = ? AND role = 'MAYOR'
                    """);
                 PreparedStatement newMayor = connection.prepareStatement("""
                         UPDATE town_members SET role = 'MAYOR'
                          WHERE town_id = ? AND player_uuid = ?
                         """);
                 PreparedStatement townUpdate = connection.prepareStatement("""
                         UPDATE towns SET mayor_uuid = ?, version = version + 1
                          WHERE town_id = ? AND version = ?
                         """)) {
                oldMayor.setBytes(1, uuid(townId));
                oldMayor.setBytes(2, uuid(town.mayorId()));
                oldMayor.executeUpdate();
                newMayor.setBytes(1, uuid(townId));
                newMayor.setBytes(2, uuid(newMayorId));
                requireUpdated(newMayor, "新镇长不是该镇成员");
                townUpdate.setBytes(1, uuid(newMayorId));
                townUpdate.setBytes(2, uuid(townId));
                townUpdate.setLong(3, town.version());
                requireUpdated(townUpdate, "小镇资料已被其他操作修改");
            }
            audit(connection, null, actorId, actorName, "MAYOR_TRANSFER", "TOWN",
                    townId.toString(), reason, town.mayorId() + " -> " + newMayorId);
            return null;
        });
    }

    public void deleteTown(UUID townId, UUID actorId, String actorName, String reason) {
        requireWorkerThread();
        requireReason(reason);
        transaction(connection -> {
            try (PreparedStatement town = connection.prepareStatement("""
                    UPDATE towns SET status = 'ARCHIVED', version = version + 1
                     WHERE town_id = ? AND status <> 'ARCHIVED'
                    """);
                 PreparedStatement members = connection.prepareStatement(
                         "DELETE FROM town_members WHERE town_id = ?");
                 PreparedStatement chunks = connection.prepareStatement("""
                         DELETE c FROM territory_chunks c
                         JOIN territory_units u ON u.unit_id = c.unit_id WHERE u.town_id = ?
                         """)) {
                town.setBytes(1, uuid(townId));
                requireUpdated(town, "小镇不存在或已经归档");
                members.setBytes(1, uuid(townId));
                members.executeUpdate();
                chunks.setBytes(1, uuid(townId));
                chunks.executeUpdate();
            }
            audit(connection, null, actorId, actorName, "TOWN_DELETE", "TOWN", townId.toString(),
                    reason, "小镇已逻辑删除，成员关系已解除，领地投影等待移除");
            return null;
        });
    }

    public TownSnapshot updateTownProfile(UUID townId, ApplicationText profile, long expectedVersion,
                                          UUID actorId, String actorName, String reason) {
        requireWorkerThread();
        profile.requireValid();
        return transaction(connection -> {
            ensureTownNameAvailable(connection, profile, townId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns SET name = ?, normalized_name = ?, short_name = ?,
                        normalized_short_name = ?, description = ?, rules_text = ?, version = version + 1
                     WHERE town_id = ? AND version = ? AND status <> 'ARCHIVED'
                    """)) {
                setTownText(statement, 1, profile);
                statement.setBytes(7, uuid(townId));
                statement.setLong(8, expectedVersion);
                requireUpdated(statement, "小镇资料已被其他操作修改，请重新读取后再试");
            }
            audit(connection, null, actorId, actorName, "PROFILE_UPDATE", "TOWN", townId.toString(),
                    reason, profile.name());
            return requireTown(connection, townId);
        });
    }

    public List<AuditSnapshot> auditLog(int limit) {
        requireWorkerThread();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return query(connection -> {
            List<AuditSnapshot> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT audit_id, actor_name, action, target_type, target_id, reason, detail, created_at
                      FROM audit_logs ORDER BY audit_id DESC LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(new AuditSnapshot(result.getLong("audit_id"),
                                result.getString("actor_name"), result.getString("action"),
                                result.getString("target_type"), result.getString("target_id"),
                                result.getString("reason"), result.getString("detail"),
                                result.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            return List.copyOf(records);
        });
    }

    public void recordAudit(UUID actorId, String actorName, String action, String targetType,
                            String targetId, String reason, String detail) {
        requireWorkerThread();
        transaction(connection -> {
            audit(connection, null, actorId, actorName, action, targetType, targetId, reason, detail);
            return null;
        });
    }

    private ApplicationSnapshot reviewTransition(UUID applicationId, UUID reviewerId,
                                                 String reviewerName, ApplicationStatus target,
                                                 String reason, String action) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId, true);
            ApplicationWorkflow.requireAllowed(current.status(), target, ApplicationActor.ADMINISTRATOR);
            updateStatus(connection, applicationId, current.version(), target, reason, null);
            if (target == ApplicationStatus.REJECTED) {
                releaseReservation(connection, applicationId);
            }
            insertReview(connection, applicationId, reviewerId, action, reason);
            audit(connection, null, reviewerId, reviewerName, "APPLICATION_" + action,
                    "APPLICATION", applicationId.toString(), reason, "");
            return requireApplication(connection, applicationId, false);
        });
    }

    private void insertTown(Connection connection, UUID townId, ApplicationSnapshot application,
                            String residenceName, UUID unitId) throws SQLException {
        ApplicationText text = application.text();
        try (PreparedStatement town = connection.prepareStatement("""
                INSERT INTO towns (town_id, name, normalized_name, short_name, normalized_short_name,
                                   description, rules_text, status, mayor_uuid)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PROVISIONING', ?)
                """);
             PreparedStatement member = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, 'MAYOR')
                     """);
             PreparedStatement unit = connection.prepareStatement("""
                     INSERT INTO territory_units
                         (unit_id, town_id, world_uuid, world_name, grid_x, grid_z, center_chunk_x,
                          center_chunk_z, residence_name)
                     VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?)
                     """);
             PreparedStatement chunk = connection.prepareStatement("""
                     INSERT INTO territory_chunks (unit_id, world_uuid, chunk_x, chunk_z)
                     VALUES (?, ?, ?, ?)
                     """)) {
            town.setBytes(1, uuid(townId));
            setTownText(town, 2, text);
            town.setBytes(8, uuid(application.applicantId()));
            town.executeUpdate();
            member.setBytes(1, uuid(townId));
            member.setBytes(2, uuid(application.applicantId()));
            member.executeUpdate();
            InitialTerritory territory = application.territory();
            unit.setBytes(1, uuid(unitId));
            unit.setBytes(2, uuid(townId));
            unit.setBytes(3, uuid(territory.center().worldId()));
            unit.setString(4, territory.center().worldName());
            unit.setInt(5, territory.center().x());
            unit.setInt(6, territory.center().z());
            unit.setString(7, residenceName);
            unit.executeUpdate();
            for (ChunkPosition position : territory.chunks()) {
                chunk.setBytes(1, uuid(unitId));
                chunk.setBytes(2, uuid(position.worldId()));
                chunk.setInt(3, position.x());
                chunk.setInt(4, position.z());
                chunk.addBatch();
            }
            chunk.executeBatch();
        }
    }

    private Provisioning provisioning(Connection connection, ApplicationSnapshot application)
            throws SQLException {
        TownSnapshot town = requireTown(connection, application.townId());
        List<UUID> members = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM town_members WHERE town_id = ?")) {
            statement.setBytes(1, uuid(town.id()));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    members.add(readUuid(result, "player_uuid"));
                }
            }
        }
        return new Provisioning(application.id(), town, members);
    }

    private Provisioning requireProvisioningTownStatus(Connection connection,
                                                        ApplicationSnapshot application,
                                                        TownStatus expected) throws SQLException {
        Provisioning existing = provisioning(connection, application);
        if (existing.town().status() == expected) {
            return existing;
        }
        if (existing.town().status() == TownStatus.ARCHIVED) {
            throw new ConflictException("小镇已归档，不能继续或重复批准");
        }
        throw new ConflictException("申请与小镇建镇状态不一致，请先执行数据对账");
    }

    private void requireDatabaseSiteAvailable(Connection connection, UUID applicationId,
                                              InitialTerritory territory, int bufferChunks)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT application_id FROM site_reservations
                 WHERE world_uuid = ? AND application_id <> ? AND released_at IS NULL
                   AND expires_at > CURRENT_TIMESTAMP(6)
                   AND min_chunk_x <= ? AND max_chunk_x >= ?
                   AND min_chunk_z <= ? AND max_chunk_z >= ?
                 LIMIT 1 FOR UPDATE
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
                 WHERE u.world_uuid = ? AND t.status <> 'ARCHIVED'
                   AND u.center_chunk_x BETWEEN ? AND ? AND u.center_chunk_z BETWEEN ? AND ?
                 LIMIT 1 FOR UPDATE
                """)) {
            statement.setBytes(1, uuid(territory.center().worldId()));
            statement.setInt(2, territory.minimumChunkX() - 1 - bufferChunks);
            statement.setInt(3, territory.maximumChunkX() + 1 + bufferChunks);
            statement.setInt(4, territory.minimumChunkZ() - 1 - bufferChunks);
            statement.setInt(5, territory.maximumChunkZ() + 1 + bufferChunks);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("选址与已有小镇领地重叠或距离过近");
                }
            }
        }
    }

    private void ensureNameAvailable(Connection connection, ApplicationText text,
                                     UUID ignoredApplicationId) throws SQLException {
        ensureTownNameAvailable(connection, text, null);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT application_id FROM town_applications
                 WHERE (active_name = ? OR active_short_name = ? OR active_residence_name = ?)
                   AND (? IS NULL OR application_id <> ?) LIMIT 1 FOR UPDATE
                """)) {
            statement.setString(1, text.normalizedName());
            statement.setString(2, text.normalizedShortName());
            statement.setString(3, text.normalizedResidenceName());
            if (ignoredApplicationId == null) {
                statement.setNull(4, java.sql.Types.BINARY);
                statement.setNull(5, java.sql.Types.BINARY);
            } else {
                statement.setBytes(4, uuid(ignoredApplicationId));
                statement.setBytes(5, uuid(ignoredApplicationId));
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("小镇名称、简称或领地名称已被其他申请占用");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT unit_id FROM territory_units WHERE residence_name = ? LIMIT 1 FOR UPDATE")) {
            statement.setString(1, TownResidenceName.initial(text.residenceName()));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("领地名称已被现有小镇使用");
                }
            }
        }
    }

    private void ensureTownNameAvailable(Connection connection, ApplicationText text,
                                         UUID ignoredTownId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT town_id FROM towns WHERE (normalized_name = ? OR normalized_short_name = ?)
                  AND (? IS NULL OR town_id <> ?) LIMIT 1 FOR UPDATE
                """)) {
            statement.setString(1, text.normalizedName());
            statement.setString(2, text.normalizedShortName());
            if (ignoredTownId == null) {
                statement.setNull(3, java.sql.Types.BINARY);
                statement.setNull(4, java.sql.Types.BINARY);
            } else {
                statement.setBytes(3, uuid(ignoredTownId));
                statement.setBytes(4, uuid(ignoredTownId));
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new ConflictException("名称或简称已被现有小镇使用");
                }
            }
        }
    }

    private Optional<ApplicationSnapshot> findApplication(Connection connection, UUID applicationId,
                                                          boolean forUpdate) throws SQLException {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.*, r.world_uuid, r.world_name, r.center_chunk_x, r.center_chunk_z,
                       r.expires_at AS reservation_expires_at
                  FROM town_applications a
                  LEFT JOIN site_reservations r ON r.application_id = a.application_id
                                               AND r.released_at IS NULL
                 WHERE a.application_id = ?
                """ + suffix)) {
            statement.setBytes(1, uuid(applicationId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readApplication(result)) : Optional.empty();
            }
        }
    }

    private ApplicationSnapshot requireApplication(Connection connection, UUID applicationId,
                                                   boolean forUpdate) throws SQLException {
        return findApplication(connection, applicationId, forUpdate)
                .orElseThrow(() -> new NotFoundException("找不到申请 " + applicationId));
    }

    private ApplicationSnapshot readApplication(ResultSet result) throws SQLException {
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
                result.getString("last_error"), result.getLong("version"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private Optional<TownSnapshot> findTown(Connection connection, UUID townId) throws SQLException {
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
                        readUuid(result, "mayor_uuid"), result.getLong("version"),
                        result.getTimestamp("created_at").toInstant(), territory,
                        result.getString("projection_status"), result.getString("projection_error")));
            }
        }
    }

    private TownSnapshot requireTown(Connection connection, UUID townId) throws SQLException {
        return findTown(connection, townId)
                .orElseThrow(() -> new NotFoundException("找不到小镇 " + townId));
    }

    private Optional<UUID> memberTownId(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT town_id FROM town_members WHERE player_uuid = ? LIMIT 1")) {
            statement.setBytes(1, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readUuid(result, "town_id")) : Optional.empty();
            }
        }
    }

    private boolean memberExists(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members WHERE town_id = ? AND player_uuid = ? LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void requireMayor(Connection connection, UUID townId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members m JOIN towns t ON t.town_id = m.town_id
                 WHERE m.town_id = ? AND m.player_uuid = ? AND m.role = 'MAYOR'
                   AND t.status = 'ACTIVE' LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("只有正常小镇的镇长可以邀请成员");
                }
            }
        }
    }

    private void updateStatus(Connection connection, UUID applicationId, long version,
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

    private void releaseReservation(Connection connection, UUID applicationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE site_reservations SET released_at = CURRENT_TIMESTAMP(6)
                 WHERE application_id = ? AND released_at IS NULL
                """)) {
            statement.setBytes(1, uuid(applicationId));
            statement.executeUpdate();
        }
    }

    private void insertReview(Connection connection, UUID applicationId, UUID reviewerId,
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

    private void audit(Connection connection, String idempotencyKey, UUID actorId, String actorName,
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

    private static void setApplicationText(PreparedStatement statement, int start,
                                            ApplicationText text) throws SQLException {
        statement.setString(start, text.name());
        statement.setString(start + 1, text.normalizedName());
        statement.setString(start + 2, text.shortName());
        statement.setString(start + 3, text.normalizedShortName());
        statement.setString(start + 4, text.residenceName());
        statement.setString(start + 5, text.description());
        statement.setString(start + 6, String.join(RULE_SEPARATOR, text.rules()));
    }

    private static void setTownText(PreparedStatement statement, int start,
                                    ApplicationText text) throws SQLException {
        statement.setString(start, text.name());
        statement.setString(start + 1, text.normalizedName());
        statement.setString(start + 2, text.shortName());
        statement.setString(start + 3, text.normalizedShortName());
        statement.setString(start + 4, text.description());
        statement.setString(start + 5, String.join(RULE_SEPARATOR, text.rules()));
    }

    private static ApplicationText readApplicationText(ResultSet result) throws SQLException {
        return readText(result, result.getString("residence_name"));
    }

    private static ApplicationText readTownText(ResultSet result) throws SQLException {
        return readText(result, TownResidenceName.key(result.getString("residence_name")));
    }

    private static ApplicationText readText(ResultSet result, String residenceName) throws SQLException {
        String rules = result.getString("rules_text");
        return new ApplicationText(result.getString("name"), result.getString("short_name"),
                residenceName, result.getString("description"),
                rules == null || rules.isEmpty() ? List.of() : List.of(rules.split(RULE_SEPARATOR, -1)));
    }

    private static void setTerritory(PreparedStatement statement, int start,
                                     InitialTerritory territory) throws SQLException {
        statement.setBytes(start, uuid(territory.center().worldId()));
        statement.setString(start + 1, territory.center().worldName());
        statement.setInt(start + 2, territory.center().x());
        statement.setInt(start + 3, territory.center().z());
        statement.setInt(start + 4, territory.minimumChunkX());
        statement.setInt(start + 5, territory.maximumChunkX());
        statement.setInt(start + 6, territory.minimumChunkZ());
        statement.setInt(start + 7, territory.maximumChunkZ());
    }

    private static void requireApplicant(ApplicationSnapshot application, UUID applicantId) {
        if (!application.applicantId().equals(applicantId)) {
            throw new ConflictException("只能操作自己的申请");
        }
    }

    private static void requireUpdated(PreparedStatement statement, String message) throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new ConflictException(message);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("必须填写原因");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("原因不能超过 500 字符");
        }
    }

    private void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("禁止在 Paper 主线程执行数据库 I/O");
        }
    }

    private <T> T query(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private static RuntimeException translate(SQLException exception) {
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState())) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("MySQL 操作失败: " + exception.getMessage(), exception);
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID readUuid(ResultSet result, String column) throws SQLException {
        return uuid(result.getBytes(column));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static String safeDetail(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.length() <= 2_000 ? detail : detail.substring(0, 2_000);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    public record Provisioning(UUID applicationId, TownSnapshot town, List<UUID> members) {
        public Provisioning {
            members = List.copyOf(members);
        }
    }

    public record PlayerDashboard(TownSnapshot town, ApplicationSnapshot application,
                                  List<InvitationSnapshot> invitations) {
        public PlayerDashboard {
            invitations = List.copyOf(invitations);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }

        public ConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class NotFoundException extends ConflictException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

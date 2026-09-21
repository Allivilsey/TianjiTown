package org.allivlisey.tianjitown.storage.town;

import org.allivlisey.tianjitown.core.land.TownReservation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationActor;
import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.application.ApplicationWorkflow;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownRepository.ConflictException;
import org.allivlisey.tianjitown.storage.town.TownRepository.MemberConflict;
import org.allivlisey.tianjitown.storage.town.TownRepository.MemberConflictException;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.timestamp;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Application editing and review workflows. */
final class TownApplicationStore {
    private final TownDatabase database;

    TownApplicationStore(TownDatabase database) {
        this.database = database;
    }

    ApplicationSnapshot createDraft(UUID applicantId, ApplicationText text,
                                           List<UUID> initialMemberIds, Duration cooldown) {
        database.requireWorkerThread();
        text.requireValid();
        Objects.requireNonNull(cooldown, "cooldown");
        return database.transaction(connection -> {
            if (TownPersistence.memberTownId(connection, applicantId).isPresent()) {
                throw new ConflictException("你已经属于一个小镇");
            }
            if (TownPersistence.pendingJoinApplicationCount(connection, applicantId) > 0) {
                throw new ConflictException("你有待处理的入镇申请，请先撤回后再申请建立小镇");
            }
            // Administrative recovery records its action atomically with cancellation.
            // Use that durable record so cancellations made before this exemption also qualify.
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM town_applications a
                     WHERE a.applicant_uuid = ? AND a.status IN ('REJECTED', 'CANCELLED')
                       AND a.updated_at > ?
                       AND NOT EXISTS (
                           SELECT 1 FROM audit_logs audit
                            WHERE (audit.action = 'APPLICATION_CLEAR_COOLDOWN'
                                   OR (a.status = 'CANCELLED' AND audit.action IN (
                                       'APPLICATION_CANCEL_ARCHIVED', 'APPLICATION_FORCE_DELETE',
                                       'PROVISION_FORCE_CLEANUP', 'PROVISION_CANCEL_AND_REFUND')))
                              AND audit.target_type = 'APPLICATION'
                              AND lower(replace(audit.target_id, '-', '')) = lower(hex(a.application_id))
                       ) LIMIT 1
                    """)) {
                statement.setBytes(1, uuid(applicantId));
                statement.setTimestamp(2, timestamp(Instant.now().minus(cooldown)));
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new ConflictException("申请冷却尚未结束");
                    }
                }
            }
            TownPersistence.ensureNameAvailable(connection, text, null);
            UUID applicationId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_applications
                        (application_id, applicant_uuid, name, normalized_name, residence_name, description, rules_text, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                    """)) {
                setApplicationText(statement, 3, text);
                statement.setBytes(1, uuid(applicationId));
                statement.setBytes(2, uuid(applicantId));
                statement.executeUpdate();
            }
            replaceInitialMembers(connection, applicationId, applicantId, initialMemberIds);
            TownPersistence.audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_CREATE",
                    "APPLICATION", applicationId.toString(), "玩家创建草稿", text.name());
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    ApplicationSnapshot updateApplicationText(UUID applicationId, UUID applicantId,
                                                     ApplicationText text,
                                                     List<UUID> initialMemberIds,
                                                     long expectedVersion) {
        database.requireWorkerThread();
        text.requireValid();
        return database.transaction(connection -> {
            ApplicationSnapshot current = TownPersistence.requireApplication(connection, applicationId);
            requireApplicant(current, applicantId);
            if (current.status() != ApplicationStatus.DRAFT
                    && current.status() != ApplicationStatus.SITE_SELECTED
                    && current.status() != ApplicationStatus.NEED_CHANGES) {
                throw new ConflictException("当前申请状态不允许修改资料");
            }
            TownPersistence.ensureNameAvailable(connection, text, applicationId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET name = ?, normalized_name = ?, residence_name = ?, description = ?, rules_text = ?, version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                setApplicationText(statement, 1, text);
                statement.setBytes(6, uuid(applicationId));
                statement.setLong(7, expectedVersion);
                TownPersistence.requireUpdated(statement, "申请资料已被其他操作修改，请重新打开");
            }
            replaceInitialMembers(connection, applicationId, applicantId, initialMemberIds);
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    ApplicationSnapshot respondInitialMember(UUID applicationId, UUID playerId,
                                                     UUID invitationToken, boolean confirm) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            ApplicationSnapshot current = TownPersistence.requireApplication(connection, applicationId);
            if (invitationToken == null || current.needsInitialMemberReselection()) {
                throw new ConflictException("本轮邀请已失效，请等待申请人重新选择初始成员");
            }
            if (current.status() != ApplicationStatus.DRAFT
                    && current.status() != ApplicationStatus.SITE_SELECTED
                    && current.status() != ApplicationStatus.NEED_CHANGES) {
                throw new ConflictException("该建镇申请已不能确认初始成员");
            }
            if (confirm && TownPersistence.memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("你已经属于其他小镇，不能确认成为初始成员");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE application_initial_members
                       SET confirmation_status = ?,
                           responded_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                     WHERE application_id = ? AND player_uuid = ?
                       AND invitation_token = ? AND confirmation_status = 'PENDING'
                    """)) {
                statement.setString(1, confirm ? "CONFIRMED" : "REJECTED");
                statement.setBytes(2, uuid(applicationId));
                statement.setBytes(3, uuid(playerId));
                statement.setBytes(4, uuid(invitationToken));
                TownPersistence.requireUpdated(statement, "你不在该申请的初始成员名单中");
            }
            TownPersistence.audit(connection, null, playerId, playerId.toString(),
                    confirm ? "INITIAL_MEMBER_CONFIRM" : "INITIAL_MEMBER_REJECT",
                    "APPLICATION", applicationId.toString(),
                    confirm ? "确认成为建镇初始成员" : "拒绝成为建镇初始成员", "");
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    ApplicationSnapshot selectSite(UUID applicationId, UUID applicantId,
                                          InitialTerritory territory, Instant expiresAt,
                                          int bufferChunks) {
        database.requireWorkerThread();
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("选址预留到期时间必须晚于当前时间");
        }
        return database.transaction(connection -> {
            ApplicationSnapshot current = TownPersistence.requireApplication(connection, applicationId);
            requireApplicant(current, applicantId);
            if (current.status() != ApplicationStatus.DRAFT
                    && current.status() != ApplicationStatus.SITE_SELECTED
                    && current.status() != ApplicationStatus.NEED_CHANGES) {
                throw new ConflictException("当前申请状态不允许选址");
            }
            TownPersistence.requireDatabaseSiteAvailable(connection, applicationId, territory, bufferChunks);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO site_reservations
                        (reservation_id, application_id, world_uuid, world_name, center_chunk_x,
                         center_chunk_z, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (application_id) DO UPDATE SET
                        world_uuid = excluded.world_uuid, world_name = excluded.world_name,
                        center_chunk_x = excluded.center_chunk_x,
                        center_chunk_z = excluded.center_chunk_z,
                        min_chunk_x = excluded.min_chunk_x, max_chunk_x = excluded.max_chunk_x,
                        min_chunk_z = excluded.min_chunk_z, max_chunk_z = excluded.max_chunk_z,
                        expires_at = excluded.expires_at, released_at = NULL
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
            TownPersistence.updateStatus(connection, applicationId, current.version(), target, null, null);
            TownPersistence.audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_SITE_SELECT",
                    "APPLICATION", applicationId.toString(), "玩家选择初始领地",
                    territory.center().worldName() + ":" + territory.center().x() + ","
                            + territory.center().z());
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    ApplicationSnapshot submit(UUID applicationId, UUID applicantId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            ApplicationSnapshot current = TownPersistence.requireApplication(connection, applicationId);
            requireApplicant(current, applicantId);
            ApplicationWorkflow.requireAllowed(current.status(), ApplicationStatus.SUBMITTED,
                    ApplicationActor.APPLICANT);
            if (current.territory() == null || current.reservationExpiresAt() == null
                    || !current.reservationExpiresAt().isAfter(Instant.now())) {
                throw new ConflictException("选址预留不存在或已经过期，请重新选址");
            }
            current.text().requireValid();
            TownPersistence.requireConfirmedInitialMembers(connection, current);
            TownPersistence.ensureNameAvailable(connection, current.text(), applicationId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET status = 'SUBMITTED',
                           submitted_at = CAST(unixepoch('subsec') * 1000 AS INTEGER),
                           review_message = NULL, version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                statement.setBytes(1, uuid(applicationId));
                statement.setLong(2, current.version());
                TownPersistence.requireUpdated(statement, "申请已被其他操作修改，请重新打开");
            }
            TownPersistence.audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_SUBMIT",
                    "APPLICATION", applicationId.toString(), "玩家确认提交", current.text().name());
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    ApplicationSnapshot cancel(UUID applicationId, UUID applicantId, String reason) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            ApplicationSnapshot current = TownPersistence.requireApplication(connection, applicationId);
            requireApplicant(current, applicantId);
            ApplicationWorkflow.requireAllowed(current.status(), ApplicationStatus.CANCELLED,
                    ApplicationActor.APPLICANT);
            TownPersistence.updateStatus(connection, applicationId, current.version(), ApplicationStatus.CANCELLED,
                    reason, null);
            TownPersistence.releaseReservation(connection, applicationId);
            TownPersistence.audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_CANCEL",
                    "APPLICATION", applicationId.toString(), reason, "");
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    /** Force cancellation retains the application, linked town and all financial history. */
    ApplicationSnapshot forceDeleteApplication(String target, UUID reviewerId,
                                               String reviewerName, String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        return database.transaction(connection -> {
            UUID applicationId;
            try {
                applicationId = UUID.fromString(target.strip());
            } catch (IllegalArgumentException ignored) {
                List<UUID> matches = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT application_id FROM town_applications
                         WHERE lower(residence_name) = ? ORDER BY updated_at DESC, application_id
                        """)) {
                    statement.setString(1, target.strip().toLowerCase(java.util.Locale.ROOT));
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) matches.add(readUuid(result, "application_id"));
                    }
                }
                if (matches.isEmpty()) throw new ConflictException("找不到该代码对应的建镇申请；未执行删除");
                if (matches.size() > 1) throw new ConflictException(
                        "该代码对应多份申请，请使用申请 UUID 指定目标：" + matches);
                applicationId = matches.getFirst();
            }
            ApplicationSnapshot current = TownPersistence.requireApplication(connection, applicationId);
            TownStatus linkedTownStatus = current.townId() == null ? null
                    : TownPersistence.requireTown(connection, current.townId()).status();
            if (linkedTownStatus == TownStatus.PROVISIONING
                    || (linkedTownStatus == null
                    && (current.status() == ApplicationStatus.APPROVED_PROVISIONING
                    || current.status() == ApplicationStatus.PROVISION_FAILED))) {
                throw new ConflictException("申请仍处于建镇创建或失败恢复流程，请先恢复失败建镇，再取消申请");
            }
            TownPersistence.updateStatus(connection, applicationId, current.version(),
                    ApplicationStatus.CANCELLED, reason, null);
            TownPersistence.releaseReservation(connection, applicationId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM application_form_drafts WHERE application_id = ?")) {
                statement.setBytes(1, uuid(applicationId));
                statement.executeUpdate();
            }
            TownPersistence.insertReview(connection, applicationId, reviewerId, "CANCEL", reason);
            TownPersistence.audit(connection, null, reviewerId, reviewerName,
                    "APPLICATION_FORCE_DELETE", "APPLICATION", applicationId.toString(), reason,
                    "原状态=" + current.status() + "；仅取消申请，不执行退款；关联小镇=" + current.townId()
                            + "；申请费=" + current.applicationFeeMinor()
                            + "；费用状态=" + current.applicationFeeStatus());
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    /** Exempt only existing terminal applications; later applications retain normal cooldowns. */
    int clearApplicationCooldown(UUID applicantId, UUID reviewerId, String reviewerName) {
        database.requireWorkerThread();
        Objects.requireNonNull(applicantId, "applicantId");
        return database.transaction(connection -> {
            List<UUID> applications = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.application_id FROM town_applications a
                     WHERE a.applicant_uuid = ? AND a.status IN ('CANCELLED', 'REJECTED')
                       AND NOT EXISTS (
                           SELECT 1 FROM audit_logs audit
                            WHERE audit.action = 'APPLICATION_CLEAR_COOLDOWN'
                              AND audit.target_type = 'APPLICATION'
                              AND lower(replace(audit.target_id, '-', '')) = lower(hex(a.application_id)))
                    """)) {
                statement.setBytes(1, uuid(applicantId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) applications.add(readUuid(result, "application_id"));
                }
            }
            for (UUID applicationId : applications) {
                TownPersistence.audit(connection, null, reviewerId, reviewerName,
                        "APPLICATION_CLEAR_COOLDOWN", "APPLICATION", applicationId.toString(),
                        "管理员清除建镇申请冷却", "申请人=" + applicantId);
            }
            TownPersistence.audit(connection, null, reviewerId, reviewerName,
                    "APPLICATION_CLEAR_COOLDOWN", "PLAYER", applicantId.toString(),
                    "管理员清除建镇申请冷却", "本次豁免记录数=" + applications.size());
            return applications.size();
        });
    }

    ApplicationSnapshot requestChanges(UUID applicationId, UUID reviewerId,
                                              String reviewerName, String reason) {
        TownPersistence.requireReason(reason);
        return reviewTransition(applicationId, reviewerId, reviewerName,
                ApplicationStatus.NEED_CHANGES, reason, "REQUEST_CHANGES");
    }

    ApplicationSnapshot reject(UUID applicationId, UUID reviewerId,
                                      String reviewerName, String reason) {
        TownPersistence.requireReason(reason);
        return reviewTransition(applicationId, reviewerId, reviewerName,
                ApplicationStatus.REJECTED, reason, "REJECT");
    }

    Optional<ApplicationSnapshot> findApplication(UUID applicationId) {
        database.requireWorkerThread();
        return database.query(connection -> TownPersistence.findApplication(connection, applicationId));
    }

    List<ApplicationSnapshot> listPendingInitialMemberApplications(UUID playerId) {
        database.requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        return database.query(connection -> {
            List<ApplicationSnapshot> applications = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.application_id
                      FROM town_applications a
                      JOIN application_initial_members m
                        ON m.application_id = a.application_id
                     WHERE m.player_uuid = ?
                       AND m.confirmation_status = 'PENDING'
                       AND a.status IN ('DRAFT', 'SITE_SELECTED', 'NEED_CHANGES')
                       AND NOT EXISTS (
                           SELECT 1 FROM application_initial_members rejected
                            WHERE rejected.application_id = a.application_id
                              AND rejected.confirmation_status = 'REJECTED'
                       )
                     ORDER BY a.updated_at DESC, a.application_id
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        TownPersistence.findApplication(connection, readUuid(result, "application_id"))
                                .ifPresent(applications::add);
                    }
                }
            }
            return List.copyOf(applications);
        });
    }

    Optional<ApplicationSnapshot> findReviewApplicationByName(String townName) {
        database.requireWorkerThread();
        String normalizedName = ApplicationText.normalizeNameKey(townName);
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE normalized_name = ?
                       AND status IN ('SUBMITTED', 'UNDER_REVIEW', 'PROVISION_FAILED')
                     ORDER BY updated_at DESC LIMIT 1
                    """)) {
                statement.setString(1, normalizedName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? TownPersistence.findApplication(connection, readUuid(result, "application_id"))
                            : Optional.empty();
                }
            }
        });
    }

    Optional<ApplicationSnapshot> findOpenApplication(UUID applicantId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE active_applicant = ? LIMIT 1
                    """)) {
                statement.setBytes(1, uuid(applicantId));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? TownPersistence.findApplication(connection, readUuid(result, "application_id"))
                            : Optional.empty();
                }
            }
        });
    }

    Optional<ApplicationFormDraft> findFormDraft(UUID applicantId) {
        database.requireWorkerThread();
        Objects.requireNonNull(applicantId, "applicantId");
        return database.query(connection -> ApplicationFormDraftStore.find(connection, applicantId));
    }

    ApplicationFormDraft saveFormDraft(ApplicationFormDraft draft) {
        database.requireWorkerThread();
        Objects.requireNonNull(draft, "draft");
        return database.transaction(connection -> {
            if (draft.applicationId() != null) {
                ApplicationSnapshot application = TownPersistence.requireApplication(connection,
                        draft.applicationId());
                requireApplicant(application, draft.applicantId());
                if (application.version() != draft.applicationVersion()) {
                    throw new ConflictException("正式申请已被其他操作修改，请重新打开后继续编辑");
                }
            }
            ApplicationFormDraftStore.save(connection, draft);
            TownPersistence.audit(connection, null, draft.applicantId(), draft.applicantId().toString(),
                    "APPLICATION_DRAFT_SAVE", "APPLICATION",
                    draft.applicationId() == null ? draft.applicantId().toString()
                            : draft.applicationId().toString(),
                    "保存第 " + draft.currentStep() + " 步申请草稿", "");
            return ApplicationFormDraftStore.requireFormDraft(connection, draft.applicantId());
        });
    }

    void deleteFormDraft(UUID applicantId) {
        database.requireWorkerThread();
        Objects.requireNonNull(applicantId, "applicantId");
        database.transaction(connection -> {
            ApplicationFormDraftStore.delete(connection, applicantId);
            return null;
        });
    }

    List<ApplicationSnapshot> listReviewQueue(int limit) {
        database.requireWorkerThread();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return database.query(connection -> {
            List<ApplicationSnapshot> applications = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE status IN ('SUBMITTED', 'UNDER_REVIEW', 'PROVISION_FAILED')
                     ORDER BY submitted_at, created_at, application_id LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        applications.add(TownPersistence.requireApplication(connection,
                                readUuid(result, "application_id")));
                    }
                }
            }
            return List.copyOf(applications);
        });
    }

    List<ApplicationSnapshot> listApplicationsForCompletion(int limit) {
        database.requireWorkerThread();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return database.query(connection -> {
            List<ApplicationSnapshot> applications = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     ORDER BY updated_at DESC, application_id LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        applications.add(TownPersistence.requireApplication(connection,
                                readUuid(result, "application_id")));
                    }
                }
            }
            return List.copyOf(applications);
        });
    }

    private ApplicationSnapshot reviewTransition(UUID applicationId, UUID reviewerId,
                                                 String reviewerName, ApplicationStatus target,
                                                 String reason, String action) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            ApplicationSnapshot current = TownPersistence.requireApplication(connection, applicationId);
            ApplicationWorkflow.requireAllowed(current.status(), target, ApplicationActor.ADMINISTRATOR);
            TownPersistence.updateStatus(connection, applicationId, current.version(), target, reason, null);
            if (target == ApplicationStatus.REJECTED) {
                TownPersistence.releaseReservation(connection, applicationId);
            }
            TownPersistence.insertReview(connection, applicationId, reviewerId, action, reason);
            TownPersistence.audit(connection, null, reviewerId, reviewerName, "APPLICATION_" + action,
                    "APPLICATION", applicationId.toString(), reason, "");
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    private void replaceInitialMembers(Connection connection, UUID applicationId,
                                       UUID applicantId, List<UUID> memberIds)
            throws SQLException {
        if (memberIds == null || memberIds.size() != 2
                || memberIds.stream().distinct().count() != 2) {
            throw new ConflictException("建镇申请必须填写两名不同的初始成员");
        }
        for (UUID memberId : memberIds) {
            if (memberId == null || memberId.equals(applicantId)) {
                throw new ConflictException("初始成员不能包含申请人");
            }
            Optional<UUID> existingTownId = TownPersistence.memberTownId(connection, memberId);
            if (existingTownId.isPresent()) {
                TownSnapshot existingTown = TownPersistence.requireTown(connection, existingTownId.get());
                throw new MemberConflictException(new MemberConflict(memberId,
                        "ALREADY_MEMBER", existingTown.id(), existingTown.profile().name()));
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM application_initial_members
                 WHERE application_id = ?
                """);
             PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO application_initial_members (application_id, player_uuid, invitation_token)
                VALUES (?, ?, ?)
                """)) {
            delete.setBytes(1, uuid(applicationId));
            delete.executeUpdate();
            UUID invitationToken = UUID.randomUUID();
            for (UUID memberId : memberIds) {
                insert.setBytes(1, uuid(applicationId));
                insert.setBytes(2, uuid(memberId));
                insert.setBytes(3, uuid(invitationToken));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void setApplicationText(PreparedStatement statement, int start,
                                            ApplicationText text) throws SQLException {
        statement.setString(start, text.name());
        statement.setString(start + 1, text.normalizedName());
        statement.setString(start + 2, text.normalizedResidenceName());
        statement.setString(start + 3, text.description());
        statement.setString(start + 4, String.join(RULE_SEPARATOR, text.rules()));
    }

    private static void setTerritory(PreparedStatement statement, int start,
                                     InitialTerritory territory) throws SQLException {
        statement.setBytes(start, uuid(territory.center().worldId()));
        statement.setString(start + 1, territory.center().worldName());
        statement.setInt(start + 2, territory.center().x());
        statement.setInt(start + 3, territory.center().z());
        TownReservation reserved = new TownReservation(territory);
        statement.setInt(start + 4, reserved.minimumChunkX());
        statement.setInt(start + 5, reserved.maximumChunkX());
        statement.setInt(start + 6, reserved.minimumChunkZ());
        statement.setInt(start + 7, reserved.maximumChunkZ());
    }

    private static void requireApplicant(ApplicationSnapshot application, UUID applicantId) {
        if (!application.applicantId().equals(applicantId)) {
            throw new ConflictException("只能操作自己的申请");
        }
    }
}

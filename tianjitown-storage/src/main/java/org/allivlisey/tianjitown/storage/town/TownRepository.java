package cn.tianji.town.storage.town;

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

public final class TownRepository {
    private static final String RULE_SEPARATOR = "\u001e";
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public TownRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    public ApplicationSnapshot createDraft(UUID applicantId, ApplicationText text,
                                           List<UUID> initialMemberIds, Duration cooldown) {
        requireWorkerThread();
        text.requireValid();
        Objects.requireNonNull(cooldown, "cooldown");
        return transaction(connection -> {
            if (memberTownId(connection, applicantId).isPresent()) {
                throw new ConflictException("你已经属于一个小镇");
            }
            if (pendingJoinApplicationCount(connection, applicantId) > 0) {
                throw new ConflictException("你有待处理的入镇申请，请先撤回后再申请建立小镇");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM town_applications
                     WHERE applicant_uuid = ? AND status IN ('REJECTED', 'CANCELLED')
                       AND updated_at > ? LIMIT 1
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
            replaceInitialMembers(connection, applicationId, applicantId, initialMemberIds);
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_CREATE",
                    "APPLICATION", applicationId.toString(), "玩家创建草稿", text.name());
            return requireApplication(connection, applicationId);
        });
    }

    public ApplicationSnapshot updateApplicationText(UUID applicationId, UUID applicantId,
                                                     ApplicationText text,
                                                     List<UUID> initialMemberIds,
                                                     long expectedVersion) {
        requireWorkerThread();
        text.requireValid();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId);
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
            replaceInitialMembers(connection, applicationId, applicantId, initialMemberIds);
            return requireApplication(connection, applicationId);
        });
    }

    public ApplicationSnapshot respondInitialMember(UUID applicationId, UUID playerId,
                                                     boolean confirm) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId);
            if (current.status() != ApplicationStatus.DRAFT
                    && current.status() != ApplicationStatus.SITE_SELECTED
                    && current.status() != ApplicationStatus.NEED_CHANGES) {
                throw new ConflictException("该建镇申请已不能确认初始成员");
            }
            if (confirm && memberTownId(connection, playerId).isPresent()) {
                throw new ConflictException("你已经属于其他小镇，不能确认成为初始成员");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE application_initial_members
                       SET confirmation_status = ?,
                           responded_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                     WHERE application_id = ? AND player_uuid = ?
                    """)) {
                statement.setString(1, confirm ? "CONFIRMED" : "REJECTED");
                statement.setBytes(2, uuid(applicationId));
                statement.setBytes(3, uuid(playerId));
                requireUpdated(statement, "你不在该申请的初始成员名单中");
            }
            audit(connection, null, playerId, playerId.toString(),
                    confirm ? "INITIAL_MEMBER_CONFIRM" : "INITIAL_MEMBER_REJECT",
                    "APPLICATION", applicationId.toString(),
                    confirm ? "确认成为建镇初始成员" : "拒绝成为建镇初始成员", "");
            return requireApplication(connection, applicationId);
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
            ApplicationSnapshot current = requireApplication(connection, applicationId);
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
            updateStatus(connection, applicationId, current.version(), target, null, null);
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_SITE_SELECT",
                    "APPLICATION", applicationId.toString(), "玩家选择初始领地",
                    territory.center().worldName() + ":" + territory.center().x() + ","
                            + territory.center().z());
            return requireApplication(connection, applicationId);
        });
    }

    public ApplicationSnapshot submit(UUID applicationId, UUID applicantId) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId);
            requireApplicant(current, applicantId);
            ApplicationWorkflow.requireAllowed(current.status(), ApplicationStatus.SUBMITTED,
                    ApplicationActor.APPLICANT);
            if (current.territory() == null || current.reservationExpiresAt() == null
                    || !current.reservationExpiresAt().isAfter(Instant.now())) {
                throw new ConflictException("选址预留不存在或已经过期，请重新选址");
            }
            current.text().requireValid();
            requireConfirmedInitialMembers(connection, current);
            ensureNameAvailable(connection, current.text(), applicationId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET status = 'SUBMITTED',
                           submitted_at = CAST(unixepoch('subsec') * 1000 AS INTEGER),
                           review_message = NULL, version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                statement.setBytes(1, uuid(applicationId));
                statement.setLong(2, current.version());
                requireUpdated(statement, "申请已被其他操作修改，请重新打开");
            }
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_SUBMIT",
                    "APPLICATION", applicationId.toString(), "玩家确认提交", current.text().name());
            return requireApplication(connection, applicationId);
        });
    }

    public ApplicationSnapshot cancel(UUID applicationId, UUID applicantId, String reason) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId);
            requireApplicant(current, applicantId);
            ApplicationWorkflow.requireAllowed(current.status(), ApplicationStatus.CANCELLED,
                    ApplicationActor.APPLICANT);
            updateStatus(connection, applicationId, current.version(), ApplicationStatus.CANCELLED,
                    reason, null);
            releaseReservation(connection, applicationId);
            audit(connection, null, applicantId, applicantId.toString(), "APPLICATION_CANCEL",
                    "APPLICATION", applicationId.toString(), reason, "");
            return requireApplication(connection, applicationId);
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
                                       String reason, String idempotencyKey,
                                       long applicationFeeMinor) {
        return beginProvision(applicationId, reviewerId, reviewerName, reason, idempotencyKey,
                applicationFeeMinor, null);
    }

    public Provisioning beginProvision(UUID applicationId, UUID reviewerId, String reviewerName,
                                       String reason, String idempotencyKey,
                                       long applicationFeeMinor, String applicantName) {
        requireWorkerThread();
        requireReason(reason);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("批准操作必须提供幂等键");
        }
        return transaction(connection -> {
            ApplicationSnapshot application = requireApplication(connection, applicationId);
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
                return provisioning(connection, requireApplication(connection, applicationId));
            }
            ApplicationWorkflow.requireAllowed(application.status(),
                    ApplicationStatus.APPROVED_PROVISIONING, ApplicationActor.ADMINISTRATOR);
            if (memberTownId(connection, application.applicantId()).isPresent()) {
                throw new ConflictException("申请人已经加入其他小镇");
            }
            if (applicationFeeMinor <= 0) {
                throw new IllegalArgumentException("建镇申请费用必须大于 0");
            }
            requireConfirmedInitialMembers(connection, application);
            if (application.territory() == null || application.reservationExpiresAt() == null
                    || !application.reservationExpiresAt().isAfter(Instant.now())) {
                throw new ConflictException("选址预留已经过期，不能批准");
            }
            ensureNameAvailable(connection, application.text(), applicationId);
            requireDatabaseSiteAvailable(connection, applicationId, application.territory(), 0);

            UUID townId = UUID.randomUUID();
            UUID unitId = UUID.randomUUID();
            String residenceName = TownResidenceName.initial(application.text().residenceName());
            insertTown(connection, townId, application, residenceName, unitId,
                    applicationFeeMinor, applicantName);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET status = 'APPROVED_PROVISIONING', town_id = ?, review_message = ?,
                           application_fee_minor = ?,
                           application_fee_status = 'ESCROWED',
                           version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setString(2, reason);
                statement.setLong(3, applicationFeeMinor);
                statement.setBytes(4, uuid(applicationId));
                statement.setLong(5, application.version());
                requireUpdated(statement, "申请已被其他管理员处理");
            }
            releaseReservation(connection, applicationId);
            insertReview(connection, applicationId, reviewerId, "APPROVE", reason);
            audit(connection, idempotencyKey, reviewerId, reviewerName, "APPLICATION_APPROVE",
                    "APPLICATION", applicationId.toString(), reason, townId.toString());
            return provisioning(connection, requireApplication(connection, applicationId));
        });
    }

    public ApplicationSnapshot finishProvision(UUID applicationId, boolean success, String detail) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot application = requireApplication(connection, applicationId);
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
            if (success) {
                try (PreparedStatement fee = connection.prepareStatement("""
                        UPDATE town_applications SET application_fee_status = 'CONSUMED'
                         WHERE application_id = ? AND application_fee_status = 'ESCROWED'
                        """)) {
                    fee.setBytes(1, uuid(applicationId));
                    fee.executeUpdate();
                }
            }
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
            return requireApplication(connection, applicationId);
        });
    }

    public Optional<ApplicationSnapshot> findApplication(UUID applicationId) {
        requireWorkerThread();
        return query(connection -> findApplication(connection, applicationId));
    }

    public List<ApplicationSnapshot> listPendingInitialMemberApplications(UUID playerId) {
        requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        return query(connection -> {
            List<ApplicationSnapshot> applications = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT a.application_id
                      FROM town_applications a
                      JOIN application_initial_members m
                        ON m.application_id = a.application_id
                     WHERE m.player_uuid = ?
                       AND m.confirmation_status = 'PENDING'
                       AND a.status IN ('DRAFT', 'SITE_SELECTED', 'NEED_CHANGES')
                     ORDER BY a.updated_at DESC, a.application_id
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        findApplication(connection, readUuid(result, "application_id"))
                                .ifPresent(applications::add);
                    }
                }
            }
            return List.copyOf(applications);
        });
    }

    public int recoverInterruptedProvisions(String reason) {
        requireWorkerThread();
        requireReason(reason);
        return transaction(connection -> {
            List<UUID> applicationIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications
                     WHERE status = 'APPROVED_PROVISIONING'
                    """);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    applicationIds.add(readUuid(result, "application_id"));
                }
            }
            for (UUID applicationId : applicationIds) {
                ApplicationSnapshot application = requireApplication(connection, applicationId);
                try (PreparedStatement applicationUpdate = connection.prepareStatement("""
                        UPDATE town_applications
                           SET status = 'PROVISION_FAILED', last_error = ?, version = version + 1
                         WHERE application_id = ? AND status = 'APPROVED_PROVISIONING'
                        """);
                     PreparedStatement unitUpdate = connection.prepareStatement("""
                             UPDATE territory_units
                                SET projection_status = 'FAILED', projection_error = ?
                              WHERE town_id = ?
                             """)) {
                    applicationUpdate.setString(1, reason);
                    applicationUpdate.setBytes(2, uuid(applicationId));
                    requireUpdated(applicationUpdate, "中断的建镇申请已被其他恢复任务处理");
                    if (application.townId() != null) {
                        unitUpdate.setString(1, reason);
                        unitUpdate.setBytes(2, uuid(application.townId()));
                        unitUpdate.executeUpdate();
                    }
                }
                audit(connection, null, null, "SYSTEM", "PROVISION_RECOVER", "APPLICATION",
                        applicationId.toString(), reason, "已转为 PROVISION_FAILED，可由管理员幂等重试");
            }
            return applicationIds.size();
        });
    }

    public Provisioning failedProvision(UUID applicationId) {
        requireWorkerThread();
        return query(connection -> {
            ApplicationSnapshot application = requireApplication(connection, applicationId);
            if (application.status() != ApplicationStatus.PROVISION_FAILED
                    || application.townId() == null) {
                throw new ConflictException("申请当前不是可恢复的创建失败状态");
            }
            return requireProvisioningTownStatus(connection, application,
                    TownStatus.PROVISIONING);
        });
    }

    public ApplicationSnapshot recoverFailedProvision(UUID applicationId, UUID reviewerId,
                                                       String reviewerName, String reason,
                                                       RecoveryMode mode) {
        requireWorkerThread();
        requireReason(reason);
        Objects.requireNonNull(mode, "mode");
        return transaction(connection -> {
            ApplicationSnapshot application = requireApplication(connection, applicationId);
            if (application.status() != ApplicationStatus.PROVISION_FAILED
                    || application.townId() == null) {
                throw new ConflictException("申请当前不是可恢复的创建失败状态");
            }
            TownSnapshot town = requireTown(connection, application.townId());
            if (town.status() != TownStatus.PROVISIONING) {
                throw new ConflictException("临时小镇已不处于 PROVISIONING，拒绝解除锁定");
            }
            ApplicationStatus target = mode == RecoveryMode.UNLOCK_FOR_CHANGES
                    ? ApplicationStatus.NEED_CHANGES : ApplicationStatus.CANCELLED;
            ApplicationWorkflow.requireAllowed(application.status(), target,
                    ApplicationActor.ADMINISTRATOR);
            String feeStatus = target == ApplicationStatus.NEED_CHANGES
                    ? "ESCROWED" : "REFUND_PENDING";
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET status = ?, town_id = NULL, review_message = ?, last_error = NULL,
                           application_fee_status = ?, version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                statement.setString(1, target.name());
                statement.setString(2, reason);
                statement.setString(3, feeStatus);
                statement.setBytes(4, uuid(applicationId));
                statement.setLong(5, application.version());
                requireUpdated(statement, "失败申请已被其他管理员处理");
            }
            deleteProvisioningTown(connection, town.id());
            insertReview(connection, applicationId, reviewerId,
                    mode == RecoveryMode.UNLOCK_FOR_CHANGES ? "UNLOCK_FOR_CHANGES"
                            : mode.name(), reason);
            audit(connection, null, reviewerId, reviewerName,
                    mode == RecoveryMode.UNLOCK_FOR_CHANGES
                            ? "PROVISION_UNLOCK_FOR_CHANGES"
                            : mode == RecoveryMode.CANCEL_AND_REFUND
                            ? "PROVISION_CANCEL_AND_REFUND" : "PROVISION_FORCE_CLEANUP",
                    "APPLICATION", applicationId.toString(), reason,
                    "已事务回滚临时小镇、成员、领地和初始资金账本；申请费="
                            + application.applicationFeeMinor() + "，状态=" + feeStatus);
            return requireApplication(connection, applicationId);
        });
    }

    public ApplicationSnapshot completeApplicationFeeRefund(UUID applicationId, UUID reviewerId,
                                                              String reviewerName,
                                                              String detail) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot application = requireApplication(connection, applicationId);
            if (application.status() != ApplicationStatus.CANCELLED
                    || application.applicationFeeStatus()
                    != ApplicationSnapshot.FeeStatus.REFUND_PENDING) {
                if (application.applicationFeeStatus()
                        == ApplicationSnapshot.FeeStatus.REFUNDED) {
                    return application;
                }
                throw new ConflictException("申请费当前不在待退款状态");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_applications
                       SET application_fee_status = 'REFUNDED', version = version + 1
                     WHERE application_id = ? AND version = ?
                    """)) {
                statement.setBytes(1, uuid(applicationId));
                statement.setLong(2, application.version());
                requireUpdated(statement, "退款状态已被其他操作修改");
            }
            audit(connection, "application-fee-refund:" + applicationId, reviewerId,
                    reviewerName, "APPLICATION_FEE_REFUND", "APPLICATION",
                    applicationId.toString(), "管理员取消失败申请并退款", safeDetail(detail));
            return requireApplication(connection, applicationId);
        });
    }

    private void deleteProvisioningTown(Connection connection, UUID townId) throws SQLException {
        String[] statements = {
                "DELETE FROM governance_vote_ballots WHERE vote_id IN "
                        + "(SELECT vote_id FROM governance_votes WHERE town_id = ?)",
                "DELETE FROM governance_vote_voters WHERE vote_id IN "
                        + "(SELECT vote_id FROM governance_votes WHERE town_id = ?)",
                "DELETE FROM governance_votes WHERE town_id = ?",
                "DELETE FROM territory_chunks WHERE unit_id IN "
                        + "(SELECT unit_id FROM territory_units WHERE town_id = ?)",
                "DELETE FROM territory_expansions WHERE town_id = ?",
                "DELETE FROM territory_expansion_batches WHERE town_id = ?",
                "DELETE FROM territory_units WHERE town_id = ?",
                "DELETE FROM active_buffs WHERE town_id = ?",
                "DELETE FROM building_refund_weekly WHERE town_id = ?",
                "DELETE FROM economy_operations WHERE town_id = ?",
                "DELETE FROM external_income_tax_records WHERE town_id = ?",
                "DELETE FROM mayor_transfer_requests WHERE town_id = ?",
                "DELETE FROM quickshop_tax_records WHERE town_id = ?",
                "DELETE FROM quickshop_subsidy_reservations WHERE town_id = ?",
                "DELETE FROM resource_orders WHERE town_id = ?",
                "DELETE FROM town_archived_members WHERE town_id = ?",
                "DELETE FROM town_beacon_effects WHERE town_id = ?",
                "DELETE FROM town_member_departures WHERE town_id = ?",
                "DELETE FROM town_profile_sync WHERE town_id = ?",
                "DELETE FROM town_visitors WHERE town_id = ?",
                "DELETE FROM town_invitations WHERE town_id = ?",
                "DELETE FROM town_join_applications WHERE town_id = ?",
                "DELETE FROM town_members WHERE town_id = ?",
                "DELETE FROM ledger_entries WHERE town_id = ?",
                "DELETE FROM town_accounts WHERE town_id = ?",
                "DELETE FROM towns WHERE town_id = ?"
        };
        for (String sql : statements) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, uuid(townId));
                statement.executeUpdate();
            }
        }
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
                            ? findApplication(connection, readUuid(result, "application_id"))
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
                            ? findApplication(connection, readUuid(result, "application_id"))
                            : Optional.empty();
                }
            }
        });
    }

    public Optional<ApplicationFormDraft> findFormDraft(UUID applicantId) {
        requireWorkerThread();
        Objects.requireNonNull(applicantId, "applicantId");
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM application_form_drafts WHERE applicant_uuid = ?
                    """)) {
                statement.setBytes(1, uuid(applicantId));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readFormDraft(result)) : Optional.empty();
                }
            }
        });
    }

    public ApplicationFormDraft saveFormDraft(ApplicationFormDraft draft) {
        requireWorkerThread();
        Objects.requireNonNull(draft, "draft");
        return transaction(connection -> {
            if (draft.applicationId() != null) {
                ApplicationSnapshot application = requireApplication(connection,
                        draft.applicationId());
                requireApplicant(application, draft.applicantId());
                if (application.version() != draft.applicationVersion()) {
                    throw new ConflictException("正式申请已被其他操作修改，请重新打开后继续编辑");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO application_form_drafts
                        (applicant_uuid, application_id, application_version, current_step,
                         name, short_name, residence_name, description, rules_text,
                         member_one_uuid, member_one_name, member_two_uuid, member_two_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (applicant_uuid) DO UPDATE SET
                        application_id = excluded.application_id,
                        application_version = excluded.application_version,
                        current_step = excluded.current_step,
                        name = excluded.name,
                        short_name = excluded.short_name,
                        residence_name = excluded.residence_name,
                        description = excluded.description,
                        rules_text = excluded.rules_text,
                        member_one_uuid = excluded.member_one_uuid,
                        member_one_name = excluded.member_one_name,
                        member_two_uuid = excluded.member_two_uuid,
                        member_two_name = excluded.member_two_name
                    """)) {
                statement.setBytes(1, uuid(draft.applicantId()));
                if (draft.applicationId() == null) {
                    statement.setNull(2, java.sql.Types.BLOB);
                } else {
                    statement.setBytes(2, uuid(draft.applicationId()));
                }
                statement.setLong(3, draft.applicationVersion());
                statement.setInt(4, draft.currentStep());
                statement.setString(5, draft.name());
                statement.setString(6, draft.shortName());
                statement.setString(7, draft.residenceName());
                statement.setString(8, draft.description());
                statement.setString(9, String.join(RULE_SEPARATOR, draft.rules()));
                setNullableUuid(statement, 10, draft.memberOneId());
                statement.setString(11, draft.memberOneName());
                setNullableUuid(statement, 12, draft.memberTwoId());
                statement.setString(13, draft.memberTwoName());
                statement.executeUpdate();
            }
            audit(connection, null, draft.applicantId(), draft.applicantId().toString(),
                    "APPLICATION_DRAFT_SAVE", "APPLICATION",
                    draft.applicationId() == null ? draft.applicantId().toString()
                            : draft.applicationId().toString(),
                    "保存第 " + draft.currentStep() + " 步申请草稿", "");
            return requireFormDraft(connection, draft.applicantId());
        });
    }

    public void deleteFormDraft(UUID applicantId) {
        requireWorkerThread();
        Objects.requireNonNull(applicantId, "applicantId");
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM application_form_drafts WHERE applicant_uuid = ?")) {
                statement.setBytes(1, uuid(applicantId));
                statement.executeUpdate();
            }
            return null;
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
                     ORDER BY submitted_at, created_at, application_id LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        applications.add(requireApplication(connection,
                                readUuid(result, "application_id")));
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
                     ORDER BY updated_at DESC, application_id LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        applications.add(requireApplication(connection,
                                readUuid(result, "application_id")));
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
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id FROM towns WHERE normalized_name = ?
                     ORDER BY (status = 'ARCHIVED'), reuse_blocked DESC, created_at DESC LIMIT 1
                    """)) {
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
                            ? findApplication(connection, readUuid(result, "application_id"))
                            : Optional.empty();
                }
            }
            List<JoinApplicationSnapshot> joinApplications = listJoinApplicationsForPlayer(
                    connection, playerId);
            List<JoinApplicationSnapshot> incomingApplications = town.isPresent()
                    && canReviewJoinApplications(connection, town.get().id(), playerId)
                    ? listJoinApplicationsForTown(connection, town.get().id()) : List.of();
            return new PlayerDashboard(town.orElse(null), application.orElse(null),
                    joinApplications, incomingApplications);
        });
    }

    public List<TownSnapshot> listTowns(boolean includeArchived) {
        requireWorkerThread();
        return query(connection -> {
            List<TownSnapshot> towns = new ArrayList<>();
            String sql = includeArchived ? """
                    SELECT town_id FROM towns
                     ORDER BY (status = 'ARCHIVED'), reuse_blocked DESC, created_at DESC, town_id
                    """
                    : "SELECT town_id FROM towns WHERE status <> 'ARCHIVED' "
                    + "ORDER BY created_at, town_id";
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

    /**
     * Returns the complete membership set for presentation-layer ordering.  Player names are not
     * persisted, so the Paper layer applies its name-aware order before it paginates.
     */
    public List<TownSnapshot.Member> listAllMembers(UUID townId) {
        requireWorkerThread();
        return query(connection -> {
            List<TownSnapshot.Member> members = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, role, joined_at FROM town_members
                     WHERE town_id = ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        members.add(new TownSnapshot.Member(readUuid(result, "player_uuid"),
                                MemberRole.valueOf(result.getString("role")),
                                result.getTimestamp("joined_at").toInstant()));
                    }
                }
            }
            return List.copyOf(members);
        });
    }

    public List<UUID> listMemberIds(UUID townId) {
        requireWorkerThread();
        return query(connection -> {
            List<UUID> members = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid FROM town_members WHERE town_id = ? "
                            + "ORDER BY joined_at, player_uuid")) {
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

    public List<MemberConflict> initialMemberConflicts(List<UUID> playerIds) {
        requireWorkerThread();
        if (playerIds == null) {
            return List.of();
        }
        return query(connection -> {
            List<MemberConflict> conflicts = new ArrayList<>();
            for (UUID playerId : playerIds.stream().filter(Objects::nonNull).distinct().toList()) {
                Optional<UUID> townId = memberTownId(connection, playerId);
                if (townId.isEmpty()) {
                    continue;
                }
                TownSnapshot town = requireTown(connection, townId.get());
                conflicts.add(new MemberConflict(playerId, "ALREADY_MEMBER", town.id(),
                        town.profile().name()));
            }
            return List.copyOf(conflicts);
        });
    }

    public TownSnapshot.VisitorPage listVisitors(UUID townId, int page, int pageSize) {
        requireWorkerThread();
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        int safePage = Math.max(page, 0);
        return query(connection -> {
            List<TownSnapshot.Visitor> visitors = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, invited_by, added_at FROM town_visitors
                     WHERE town_id = ? ORDER BY added_at, player_uuid LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setInt(2, safeSize + 1);
                statement.setInt(3, Math.multiplyExact(safePage, safeSize));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        visitors.add(new TownSnapshot.Visitor(
                                readUuid(result, "player_uuid"),
                                readUuid(result, "invited_by"),
                                result.getTimestamp("added_at").toInstant()));
                    }
                }
            }
            boolean hasNext = visitors.size() > safeSize;
            if (hasNext) {
                visitors.removeLast();
            }
            return new TownSnapshot.VisitorPage(visitors, hasNext);
        });
    }

    public List<UUID> listVisitorIds(UUID townId) {
        requireWorkerThread();
        return query(connection -> {
            List<UUID> visitors = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid FROM town_visitors
                     WHERE town_id = ? ORDER BY added_at, player_uuid
                    """)) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        visitors.add(readUuid(result, "player_uuid"));
                    }
                }
            }
            return List.copyOf(visitors);
        });
    }

    public List<UUID> listLandAccessIds(UUID townId) {
        requireWorkerThread();
        return query(connection -> {
            List<UUID> players = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, subject_type, granted_at FROM (
                        SELECT player_uuid, 0 AS subject_type, joined_at AS granted_at
                          FROM town_members WHERE town_id = ?
                        UNION ALL
                        SELECT player_uuid, 1 AS subject_type, added_at AS granted_at
                          FROM town_visitors WHERE town_id = ?
                    ) ORDER BY subject_type, granted_at, player_uuid
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        players.add(readUuid(result, "player_uuid"));
                    }
                }
            }
            return List.copyOf(players);
        });
    }

    public TownSnapshot.Visitor addVisitor(UUID townId, UUID playerId, UUID actorId,
                                           String actorName) {
        requireWorkerThread();
        return transaction(connection -> {
            requireManager(connection, townId, actorId);
            if (memberExists(connection, townId, playerId)) {
                throw new ConflictException("本镇成员不能加入访客名单");
            }
            if (visitorExists(connection, townId, playerId)) {
                throw new ConflictException("目标玩家已经在访客名单中");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_visitors (town_id, player_uuid, invited_by)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(playerId));
                statement.setBytes(3, uuid(actorId));
                statement.executeUpdate();
            }
            audit(connection, null, actorId, actorName, "VISITOR_ADD", "TOWN",
                    townId.toString(), "镇长或副镇长邀请访客", playerId.toString());
            return requireVisitor(connection, townId, playerId);
        });
    }

    public UUID removeVisitor(UUID townId, UUID playerId, UUID actorId, String actorName) {
        requireWorkerThread();
        return transaction(connection -> {
            requireManager(connection, townId, actorId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM town_visitors WHERE town_id = ? AND player_uuid = ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setBytes(2, uuid(playerId));
                requireUpdated(statement, "目标玩家不在访客名单中");
            }
            audit(connection, null, actorId, actorName, "VISITOR_REMOVE", "TOWN",
                    townId.toString(), "镇长或副镇长移出访客", playerId.toString());
            return playerId;
        });
    }

    public Map<UUID, List<UUID>> listMemberIdsByTown() {
        requireWorkerThread();
        return query(connection -> {
            Map<UUID, List<UUID>> mutable = new java.util.LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id, player_uuid FROM town_members
                     ORDER BY town_id, joined_at, player_uuid
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

    public JoinApplicationSnapshot applyToTown(UUID townId, UUID applicantId, Duration lifetime,
                                               Duration rejectionCooldown, Duration leaveCooldown,
                                               int maximumPending) {
        requireWorkerThread();
        Objects.requireNonNull(lifetime, "lifetime");
        Objects.requireNonNull(rejectionCooldown, "rejectionCooldown");
        Objects.requireNonNull(leaveCooldown, "leaveCooldown");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("入镇申请有效期必须大于 0");
        }
        if (maximumPending < 1) {
            throw new IllegalArgumentException("入镇申请数量上限必须大于 0");
        }
        return transaction(connection -> {
            expireJoinApplications(connection);
            TownSnapshot town = requireTown(connection, townId);
            if (town.status() != TownStatus.ACTIVE) {
                throw new ConflictException("只能申请加入正常运行的小镇");
            }
            if (memberTownId(connection, applicantId).isPresent()) {
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
            if (pendingJoinApplicationCount(connection, applicantId) >= maximumPending) {
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
            audit(connection, null, applicantId, applicantId.toString(),
                    "MEMBER_APPLICATION_CREATE", "TOWN", townId.toString(),
                    "玩家申请加入小镇", applicationId.toString());
            return requireJoinApplication(connection, applicationId);
        });
    }

    public List<JoinApplicationSnapshot> listJoinApplications(UUID applicantId) {
        requireWorkerThread();
        return transaction(connection -> {
            expireJoinApplications(connection);
            return listJoinApplicationsForPlayer(connection, applicantId);
        });
    }

    public List<JoinApplicationSnapshot> listTownJoinApplications(UUID townId, UUID mayorId) {
        requireWorkerThread();
        return transaction(connection -> {
            expireJoinApplications(connection);
            requireManager(connection, townId, mayorId);
            return listJoinApplicationsForTown(connection, townId);
        });
    }

    public JoinApplicationSnapshot approveJoinApplication(UUID applicationId, UUID mayorId) {
        requireWorkerThread();
        return transaction(connection -> {
            expireJoinApplications(connection);
            JoinApplicationSnapshot application = requireJoinApplication(connection, applicationId);
            requireManager(connection, application.townId(), mayorId);
            requirePendingJoinApplication(application);
            if (memberTownId(connection, application.applicantId()).isPresent()) {
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
                requireUpdated(approved, "入镇申请已过期或已被处理");
                cancelOthers.setBytes(1, uuid(application.applicantId()));
                cancelOthers.setBytes(2, uuid(applicationId));
                cancelOthers.executeUpdate();
            }
            audit(connection, null, mayorId, mayorId.toString(),
                    "MEMBER_APPLICATION_APPROVE", "TOWN", application.townId().toString(),
                    "管理组批准入镇申请", application.applicantId().toString());
            return requireJoinApplication(connection, applicationId);
        });
    }

    public JoinApplicationSnapshot rejectJoinApplication(UUID applicationId, UUID mayorId) {
        requireWorkerThread();
        return decideJoinApplication(applicationId, mayorId, "REJECTED",
                "MEMBER_APPLICATION_REJECT", "管理组拒绝入镇申请");
    }

    public JoinApplicationSnapshot cancelJoinApplication(UUID applicationId, UUID applicantId) {
        requireWorkerThread();
        return transaction(connection -> {
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
                requireUpdated(statement, "入镇申请已过期或已被处理");
            }
            audit(connection, null, applicantId, applicantId.toString(),
                    "MEMBER_APPLICATION_CANCEL", "TOWN", application.townId().toString(),
                    "玩家撤回入镇申请", applicationId.toString());
            return requireJoinApplication(connection, applicationId);
        });
    }

    public InvitationSnapshot invite(UUID townId, UUID mayorId, UUID playerId, Duration lifetime) {
        requireWorkerThread();
        return transaction(connection -> {
            requireManager(connection, townId, mayorId);
            if (memberTownId(connection, playerId).isPresent()) {
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
            audit(connection, null, mayorId, mayorId.toString(), "MEMBER_INVITE", "TOWN",
                    townId.toString(), "管理组邀请成员", playerId.toString());
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
            try (PreparedStatement member = connection.prepareStatement("""
                    DELETE FROM town_members WHERE town_id = ? AND player_uuid = ? AND role <> 'MAYOR'
                    """);
                 PreparedStatement departure = connection.prepareStatement("""
                         INSERT INTO town_member_departures (town_id, player_uuid, departure_type)
                         VALUES (?, ?, 'VOLUNTARY')
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                requireUpdated(member, "镇长不能主动退出，请先解散小镇或由管理员转移镇长");
                departure.setBytes(1, uuid(townId));
                departure.setBytes(2, uuid(playerId));
                departure.executeUpdate();
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
            try (PreparedStatement member = connection.prepareStatement("""
                    DELETE FROM town_members WHERE town_id = ? AND player_uuid = ? AND role <> 'MAYOR'
                    """);
                 PreparedStatement departure = connection.prepareStatement("""
                         INSERT INTO town_member_departures (town_id, player_uuid, departure_type)
                         VALUES (?, ?, 'REMOVED')
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                requireUpdated(member, "成员不存在或目标是镇长");
                departure.setBytes(1, uuid(townId));
                departure.setBytes(2, uuid(playerId));
                departure.executeUpdate();
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
            try (PreparedStatement member = connection.prepareStatement("""
                    INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, 'MEMBER')
                    """);
                 PreparedStatement applications = connection.prepareStatement("""
                         UPDATE town_join_applications
                            SET status = 'CANCELLED',
                                decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE applicant_uuid = ? AND status = 'PENDING'
                         """)) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(playerId));
                member.executeUpdate();
                applications.setBytes(1, uuid(playerId));
                applications.executeUpdate();
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
            prepareTownDeletion(connection, townId, actorId, actorName, reason, false, -1);
            return null;
        });
    }

    public TownSnapshot disbandTown(UUID townId, UUID mayorId, long expectedVersion) {
        requireWorkerThread();
        return transaction(connection -> {
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
        requireWorkerThread();
        requireReason(reason);
        transaction(connection -> {
            TownSnapshot current = requireTown(connection, townId);
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
                requireUpdated(town, "小镇删除资源已被其他操作释放");
                units.setBytes(1, uuid(townId));
                units.executeUpdate();
                chunks.setBytes(1, uuid(townId));
                chunks.executeUpdate();
            }
            audit(connection, null, actorId, actorName, "TOWN_DELETE_COMPLETE", "TOWN",
                    townId.toString(), reason, "Residence 已移除；名称、小镇代码和区块已允许复用");
            return null;
        });
    }

    public boolean archiveTownForMissingProjection(UUID townId, String detail) {
        requireWorkerThread();
        requireReason(detail);
        return transaction(connection -> {
            try (PreparedStatement town = connection.prepareStatement("""
                    UPDATE towns
                       SET status = 'ARCHIVED', reuse_blocked = TRUE, archived_at = ?,
                           archive_reason = ?, version = version + 1
                     WHERE town_id = ? AND status = 'ACTIVE'
                    """);
                 PreparedStatement units = connection.prepareStatement("""
                         UPDATE territory_units
                            SET projection_status = 'FAILED', projection_error = ?, reuse_blocked = TRUE
                          WHERE town_id = ?
                         """);
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
                            SET status = 'CANCELLED',
                                decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE town_id = ? AND status = 'PENDING'
                         """)) {
                snapshotMembers(connection, townId);
                town.setLong(1, Instant.now().toEpochMilli());
                town.setString(2, detail);
                town.setBytes(3, uuid(townId));
                if (town.executeUpdate() != 1) {
                    return false;
                }
                units.setString(1, detail);
                units.setBytes(2, uuid(townId));
                units.executeUpdate();
                members.setBytes(1, uuid(townId));
                members.executeUpdate();
                visitors.setBytes(1, uuid(townId));
                visitors.executeUpdate();
                invitations.setBytes(1, uuid(townId));
                invitations.executeUpdate();
                joinApplications.setBytes(1, uuid(townId));
                joinApplications.executeUpdate();
            }
            audit(connection, null, null, "SYSTEM", "TOWN_SAFETY_ARCHIVE", "TOWN",
                    townId.toString(), "Residence 投影缺失", detail
                            + "；名称、小镇代码和区块继续锁定，禁止自动复用");
            return true;
        });
    }

    public TownSnapshot updateTownProfile(UUID townId, ApplicationText profile, long expectedVersion,
                                          UUID actorId, String actorName, String reason) {
        requireWorkerThread();
        profile.requireValid();
        return transaction(connection -> {
            requireManager(connection, townId, actorId);
            TownSnapshot current = requireTown(connection, townId);
            if (!current.profile().name().equals(profile.name())
                    || !current.profile().shortName().equals(profile.shortName())
                    || !current.profile().normalizedResidenceName()
                    .equals(profile.normalizedResidenceName())) {
                throw new ConflictException("玩家资料入口不能修改小镇名称或小镇代码");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns SET description = ?, rules_text = ?,
                        rules_revision = rules_revision + CASE WHEN rules_text <> ? THEN 1 ELSE 0 END,
                        version = version + 1
                     WHERE town_id = ? AND version = ? AND status <> 'ARCHIVED'
                    """)) {
                statement.setString(1, profile.description());
                statement.setString(2, String.join(RULE_SEPARATOR, profile.rules()));
                statement.setString(3, String.join(RULE_SEPARATOR, profile.rules()));
                statement.setBytes(4, uuid(townId));
                statement.setLong(5, expectedVersion);
                requireUpdated(statement, "小镇资料已被其他操作修改，请重新读取后再试");
            }
            audit(connection, null, actorId, actorName, "PROFILE_UPDATE", "TOWN", townId.toString(),
                    reason, current.profile().name());
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

    private JoinApplicationSnapshot decideJoinApplication(UUID applicationId, UUID mayorId,
                                                           String status, String action,
                                                           String reason) {
        return transaction(connection -> {
            expireJoinApplications(connection);
            JoinApplicationSnapshot application = requireJoinApplication(connection, applicationId);
            requireManager(connection, application.townId(), mayorId);
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
                requireUpdated(statement, "入镇申请已过期或已被处理");
            }
            audit(connection, null, mayorId, mayorId.toString(), action, "TOWN",
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

    private List<JoinApplicationSnapshot> listJoinApplicationsForPlayer(Connection connection,
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

    private List<JoinApplicationSnapshot> listJoinApplicationsForTown(Connection connection,
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
                return result.next() ? Optional.of(readJoinApplication(result)) : Optional.empty();
            }
        }
    }

    private JoinApplicationSnapshot requireJoinApplication(Connection connection, UUID applicationId)
            throws SQLException {
        return findJoinApplication(connection, applicationId)
                .orElseThrow(() -> new NotFoundException("找不到入镇申请 " + applicationId));
    }

    private static JoinApplicationSnapshot readJoinApplication(ResultSet result) throws SQLException {
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

    private static void requirePendingJoinApplication(JoinApplicationSnapshot application) {
        if (application.status() != JoinApplicationSnapshot.Status.PENDING
                || !application.expiresAt().isAfter(Instant.now())) {
            throw new ConflictException("入镇申请已过期或已被处理");
        }
    }

    private static int pendingJoinApplicationCount(Connection connection, UUID applicantId)
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

    private TownSnapshot prepareTownDeletion(Connection connection, UUID townId, UUID actorId,
                                              String actorName, String reason, boolean mayorOnly,
                                              long expectedVersion) throws SQLException {
        TownSnapshot current = requireTown(connection, townId);
        if (mayorOnly) {
            requireManager(connection, townId, actorId);
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
                requireUpdated(town, "小镇不存在或已经归档");
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
        audit(connection, null, actorId, actorName, action, "TOWN", townId.toString(), reason,
                "已安全归档；名称、小镇代码和区块保持锁定，等待移除投影");
        return current;
    }

    private ApplicationSnapshot reviewTransition(UUID applicationId, UUID reviewerId,
                                                 String reviewerName, ApplicationStatus target,
                                                 String reason, String action) {
        requireWorkerThread();
        return transaction(connection -> {
            ApplicationSnapshot current = requireApplication(connection, applicationId);
            ApplicationWorkflow.requireAllowed(current.status(), target, ApplicationActor.ADMINISTRATOR);
            updateStatus(connection, applicationId, current.version(), target, reason, null);
            if (target == ApplicationStatus.REJECTED) {
                releaseReservation(connection, applicationId);
            }
            insertReview(connection, applicationId, reviewerId, action, reason);
            audit(connection, null, reviewerId, reviewerName, "APPLICATION_" + action,
                    "APPLICATION", applicationId.toString(), reason, "");
            return requireApplication(connection, applicationId);
        });
    }

    private void insertTown(Connection connection, UUID townId, ApplicationSnapshot application,
                            String residenceName, UUID unitId, long applicationFeeMinor,
                            String applicantName)
            throws SQLException {
        ApplicationText text = application.text();
        try (PreparedStatement town = connection.prepareStatement("""
                INSERT INTO towns (town_id, name, normalized_name, short_name, normalized_short_name,
                                   description, rules_text, status, mayor_uuid)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PROVISIONING', ?)
                """);
             PreparedStatement member = connection.prepareStatement("""
                     INSERT INTO town_members (town_id, player_uuid, role) VALUES (?, ?, ?)
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
            member.setString(3, MemberRole.MAYOR.name());
            member.executeUpdate();
            for (InitialMemberConfirmation initial : application.initialMembers()) {
                member.setBytes(1, uuid(townId));
                member.setBytes(2, uuid(initial.playerId()));
                member.setString(3, MemberRole.MEMBER.name());
                member.addBatch();
            }
            member.executeBatch();
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
        try (PreparedStatement account = connection.prepareStatement("""
                UPDATE town_accounts SET balance_minor = ?, version = version + 1
                 WHERE town_id = ? AND balance_minor = 0
                """);
             PreparedStatement ledger = connection.prepareStatement("""
                INSERT INTO ledger_entries
                    (entry_id, town_id, entry_type, amount_minor, balance_after_minor,
                     actor_uuid, actor_name, business_key, note)
                VALUES (?, ?, 'APPLICATION_FEE', ?, ?, ?, ?, ?, ?)
                """)) {
            account.setLong(1, applicationFeeMinor);
            account.setBytes(2, uuid(townId));
            requireUpdated(account, "无法写入建镇初始资金");
            ledger.setBytes(1, uuid(UUID.randomUUID()));
            ledger.setBytes(2, uuid(townId));
            ledger.setLong(3, applicationFeeMinor);
            ledger.setLong(4, applicationFeeMinor);
            ledger.setBytes(5, uuid(application.applicantId()));
            ledger.setString(6, applicantName == null || applicantName.isBlank()
                    ? application.applicantId().toString() : applicantName);
            ledger.setString(7, "application-fee:" + application.id());
            ledger.setString(8, "建镇申请费转为小镇初始公共资金");
            ledger.executeUpdate();
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

    private void ensureNameAvailable(Connection connection, ApplicationText text,
                                     UUID ignoredApplicationId) throws SQLException {
        ensureTownNameAvailable(connection, text, null);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT application_id FROM town_applications
                 WHERE (active_name = ? OR active_short_name = ? OR active_residence_name = ?)
                   AND (? IS NULL OR application_id <> ?) LIMIT 1
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

    private void ensureTownNameAvailable(Connection connection, ApplicationText text,
                                         UUID ignoredTownId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT town_id FROM towns
                 WHERE reuse_blocked = TRUE
                   AND (normalized_name = ? OR normalized_short_name = ?)
                  AND (? IS NULL OR town_id <> ?) LIMIT 1
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
                    throw new ConflictException("小镇名称或代码已被现有小镇使用");
                }
            }
        }
    }

    private Optional<ApplicationSnapshot> findApplication(Connection connection, UUID applicationId)
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

    private ApplicationSnapshot withInitialMembers(Connection connection,
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
            Optional<UUID> existingTownId = memberTownId(connection, memberId);
            if (existingTownId.isPresent()) {
                TownSnapshot existingTown = requireTown(connection, existingTownId.get());
                throw new MemberConflictException(new MemberConflict(memberId,
                        "ALREADY_MEMBER", existingTown.id(), existingTown.profile().name()));
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM application_initial_members
                 WHERE application_id = ? AND player_uuid NOT IN (?, ?)
                """);
             PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO application_initial_members (application_id, player_uuid)
                VALUES (?, ?) ON CONFLICT (application_id, player_uuid) DO NOTHING
                """)) {
            delete.setBytes(1, uuid(applicationId));
            delete.setBytes(2, uuid(memberIds.get(0)));
            delete.setBytes(3, uuid(memberIds.get(1)));
            delete.executeUpdate();
            for (UUID memberId : memberIds) {
                insert.setBytes(1, uuid(applicationId));
                insert.setBytes(2, uuid(memberId));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void requireConfirmedInitialMembers(Connection connection,
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

    private ApplicationSnapshot requireApplication(Connection connection, UUID applicationId)
            throws SQLException {
        return findApplication(connection, applicationId)
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
                result.getString("last_error"), List.of(),
                result.getLong("application_fee_minor"),
                ApplicationSnapshot.FeeStatus.valueOf(
                        result.getString("application_fee_status")), result.getLong("version"),
                result.getTimestamp("submitted_at") == null ? null
                        : result.getTimestamp("submitted_at").toInstant(),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private ApplicationFormDraft requireFormDraft(Connection connection, UUID applicantId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM application_form_drafts WHERE applicant_uuid = ?")) {
            statement.setBytes(1, uuid(applicantId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new NotFoundException("找不到申请表单草稿");
                }
                return readFormDraft(result);
            }
        }
    }

    private static ApplicationFormDraft readFormDraft(ResultSet result) throws SQLException {
        byte[] application = result.getBytes("application_id");
        byte[] memberOne = result.getBytes("member_one_uuid");
        byte[] memberTwo = result.getBytes("member_two_uuid");
        String rulesText = result.getString("rules_text");
        List<String> rules = rulesText == null || rulesText.isEmpty() ? List.of()
                : List.of(rulesText.split(RULE_SEPARATOR, -1));
        return new ApplicationFormDraft(readUuid(result, "applicant_uuid"),
                application == null ? null : uuid(application),
                result.getLong("application_version"), result.getInt("current_step"),
                result.getString("name"), result.getString("short_name"),
                result.getString("residence_name"), result.getString("description"), rules,
                memberOne == null ? null : uuid(memberOne), result.getString("member_one_name"),
                memberTwo == null ? null : uuid(memberTwo), result.getString("member_two_name"),
                Instant.ofEpochMilli(result.getLong("updated_at")));
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
                        readUuid(result, "mayor_uuid"), result.getLong("rules_revision"),
                        result.getLong("version"),
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

    private boolean visitorExists(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_visitors WHERE town_id = ? AND player_uuid = ? LIMIT 1
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private TownSnapshot.Visitor requireVisitor(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, invited_by, added_at FROM town_visitors
                 WHERE town_id = ? AND player_uuid = ?
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new NotFoundException("访客记录不存在");
                }
                return new TownSnapshot.Visitor(readUuid(result, "player_uuid"),
                        readUuid(result, "invited_by"),
                        result.getTimestamp("added_at").toInstant());
            }
        }
    }

    private void requireManager(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        if (!canReviewJoinApplications(connection, townId, playerId)) {
            throw new ConflictException("只有正常运行小镇的镇长或副镇长可以执行该操作");
        }
    }

    private boolean canReviewJoinApplications(Connection connection, UUID townId, UUID playerId)
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
                UPDATE site_reservations
                   SET released_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
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
        statement.setString(start + 4, text.normalizedResidenceName());
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
            boolean begun = false;
            try {
                executeTransactionCommand(connection, "BEGIN IMMEDIATE");
                begun = true;
                T result = work.run(connection);
                executeTransactionCommand(connection, "COMMIT");
                begun = false;
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, begun, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private static void rollback(Connection connection, boolean begun, Throwable failure) {
        if (!begun) {
            return;
        }
        try {
            executeTransactionCommand(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void executeTransactionCommand(Connection connection, String command)
            throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private static RuntimeException translate(SQLException exception) {
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 19) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("SQLite 操作失败: " + exception.getMessage(), exception);
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static void setNullableUuid(PreparedStatement statement, int index, UUID value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BLOB);
        } else {
            statement.setBytes(index, uuid(value));
        }
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

    public enum RecoveryMode {
        UNLOCK_FOR_CHANGES,
        CANCEL_AND_REFUND,
        FORCE_CLEANUP
    }

    public record PlayerDashboard(TownSnapshot town, ApplicationSnapshot application,
                                  List<JoinApplicationSnapshot> joinApplications,
                                  List<JoinApplicationSnapshot> incomingJoinApplications) {
        public PlayerDashboard {
            joinApplications = List.copyOf(joinApplications);
            incomingJoinApplications = List.copyOf(incomingJoinApplications);
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

    public record MemberConflict(UUID playerId, String conflictType, UUID townId,
                                 String townName) {
    }

    public static final class MemberConflictException extends ConflictException {
        private final MemberConflict conflict;

        public MemberConflictException(MemberConflict conflict) {
            super("初始成员已经属于小镇“" + conflict.townName() + "”");
            this.conflict = Objects.requireNonNull(conflict, "conflict");
        }

        public MemberConflict conflict() {
            return conflict;
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

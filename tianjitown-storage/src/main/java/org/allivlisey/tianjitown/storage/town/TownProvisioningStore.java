package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationActor;
import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.application.ApplicationWorkflow;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TownResidenceName;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownRepository.ConflictException;
import org.allivlisey.tianjitown.storage.town.TownRepository.Provisioning;
import org.allivlisey.tianjitown.storage.town.TownRepository.RecoveryMode;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

/** Town provisioning and failed-provision recovery transactions. */
final class TownProvisioningStore {
    private final TownDatabase database;

    TownProvisioningStore(TownDatabase database) {
        this.database = database;
    }

    Provisioning beginProvision(UUID applicationId, UUID reviewerId, String reviewerName,
                                       String reason, String idempotencyKey,
                                       long applicationFeeMinor) {
        return beginProvision(applicationId, reviewerId, reviewerName, reason, idempotencyKey,
                applicationFeeMinor, null);
    }

    Provisioning beginProvision(UUID applicationId, UUID reviewerId, String reviewerName,
                                       String reason, String idempotencyKey,
                                       long applicationFeeMinor, String applicantName) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("批准操作必须提供幂等键");
        }
        return database.transaction(connection -> {
            ApplicationSnapshot application = TownPersistence.requireApplication(connection, applicationId);
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
                TownPersistence.updateStatus(connection, applicationId, application.version(),
                        ApplicationStatus.APPROVED_PROVISIONING, null, null);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE territory_units SET projection_status = 'PENDING', projection_error = NULL
                         WHERE town_id = ?
                        """)) {
                    statement.setBytes(1, uuid(application.townId()));
                    statement.executeUpdate();
                }
                TownPersistence.audit(connection, idempotencyKey, reviewerId, reviewerName, "PROVISION_RETRY",
                        "APPLICATION", applicationId.toString(), reason, "");
                return provisioning(connection, TownPersistence.requireApplication(connection, applicationId));
            }
            ApplicationWorkflow.requireAllowed(application.status(),
                    ApplicationStatus.APPROVED_PROVISIONING, ApplicationActor.ADMINISTRATOR);
            if (TownPersistence.memberTownId(connection, application.applicantId()).isPresent()) {
                throw new ConflictException("申请人已经加入其他小镇");
            }
            if (applicationFeeMinor <= 0) {
                throw new IllegalArgumentException("建镇申请费用必须大于 0");
            }
            TownPersistence.requireConfirmedInitialMembers(connection, application);
            if (application.territory() == null || application.reservationExpiresAt() == null
                    || !application.reservationExpiresAt().isAfter(Instant.now())) {
                throw new ConflictException("选址预留已经过期，不能批准");
            }
            TownPersistence.ensureNameAvailable(connection, application.text(), applicationId);
            TownPersistence.requireDatabaseSiteAvailable(connection, applicationId, application.territory(), 0);

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
                TownPersistence.requireUpdated(statement, "申请已被其他管理员处理");
            }
            TownPersistence.releaseReservation(connection, applicationId);
            TownPersistence.insertReview(connection, applicationId, reviewerId, "APPROVE", reason);
            TownPersistence.audit(connection, idempotencyKey, reviewerId, reviewerName, "APPLICATION_APPROVE",
                    "APPLICATION", applicationId.toString(), reason, townId.toString());
            return provisioning(connection, TownPersistence.requireApplication(connection, applicationId));
        });
    }

    ApplicationSnapshot finishProvision(UUID applicationId, boolean success, String detail) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            ApplicationSnapshot application = TownPersistence.requireApplication(connection, applicationId);
            if (success && application.status() == ApplicationStatus.ACTIVE) {
                return application;
            }
            if (!success && application.status() == ApplicationStatus.PROVISION_FAILED) {
                return application;
            }
            ApplicationStatus target = success ? ApplicationStatus.ACTIVE
                    : ApplicationStatus.PROVISION_FAILED;
            ApplicationWorkflow.requireAllowed(application.status(), target, ApplicationActor.SYSTEM);
            TownPersistence.updateStatus(connection, applicationId, application.version(), target, null,
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
            TownPersistence.audit(connection, null, null, "SYSTEM", success ? "PROVISION_COMPLETE" : "PROVISION_FAIL",
                    "TOWN", application.townId().toString(), success ? "自动创建完成" : "自动创建失败",
                    safeDetail(detail));
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    int recoverInterruptedProvisions(String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        return database.transaction(connection -> {
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
                ApplicationSnapshot application = TownPersistence.requireApplication(connection, applicationId);
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
                    TownPersistence.requireUpdated(applicationUpdate, "中断的建镇申请已被其他恢复任务处理");
                    if (application.townId() != null) {
                        unitUpdate.setString(1, reason);
                        unitUpdate.setBytes(2, uuid(application.townId()));
                        unitUpdate.executeUpdate();
                    }
                }
                TownPersistence.audit(connection, null, null, "SYSTEM", "PROVISION_RECOVER", "APPLICATION",
                        applicationId.toString(), reason, "已转为 PROVISION_FAILED，可由管理员幂等重试");
            }
            return applicationIds.size();
        });
    }

    Provisioning failedProvision(UUID applicationId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            ApplicationSnapshot application = TownPersistence.requireApplication(connection, applicationId);
            if (application.status() != ApplicationStatus.PROVISION_FAILED
                    || application.townId() == null) {
                throw new ConflictException("申请当前不是可恢复的创建失败状态");
            }
            return requireProvisioningTownStatus(connection, application,
                    TownStatus.PROVISIONING);
        });
    }

    ApplicationSnapshot recoverFailedProvision(UUID applicationId, UUID reviewerId,
                                                       String reviewerName, String reason,
                                                       RecoveryMode mode) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        Objects.requireNonNull(mode, "mode");
        return database.transaction(connection -> {
            ApplicationSnapshot application = TownPersistence.requireApplication(connection, applicationId);
            if (application.status() != ApplicationStatus.PROVISION_FAILED
                    || application.townId() == null) {
                throw new ConflictException("申请当前不是可恢复的创建失败状态");
            }
            TownSnapshot town = TownPersistence.requireTown(connection, application.townId());
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
                TownPersistence.requireUpdated(statement, "失败申请已被其他管理员处理");
            }
            deleteProvisioningTown(connection, town.id());
            TownPersistence.insertReview(connection, applicationId, reviewerId,
                    mode == RecoveryMode.UNLOCK_FOR_CHANGES ? "UNLOCK_FOR_CHANGES"
                            : mode.name(), reason);
            TownPersistence.audit(connection, null, reviewerId, reviewerName,
                    mode == RecoveryMode.UNLOCK_FOR_CHANGES
                            ? "PROVISION_UNLOCK_FOR_CHANGES"
                            : mode == RecoveryMode.CANCEL_AND_REFUND
                            ? "PROVISION_CANCEL_AND_REFUND" : "PROVISION_FORCE_CLEANUP",
                    "APPLICATION", applicationId.toString(), reason,
                    "已事务回滚临时小镇、成员、领地和初始资金账本；申请费="
                            + application.applicationFeeMinor() + "，状态=" + feeStatus);
            return TownPersistence.requireApplication(connection, applicationId);
        });
    }

    ApplicationSnapshot completeApplicationFeeRefund(UUID applicationId, UUID reviewerId,
                                                              String reviewerName,
                                                              String detail) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            ApplicationSnapshot application = TownPersistence.requireApplication(connection, applicationId);
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
                TownPersistence.requireUpdated(statement, "退款状态已被其他操作修改");
            }
            TownPersistence.audit(connection, "application-fee-refund:" + applicationId, reviewerId,
                    reviewerName, "APPLICATION_FEE_REFUND", "APPLICATION",
                    applicationId.toString(), "管理员取消失败申请并退款", safeDetail(detail));
            return TownPersistence.requireApplication(connection, applicationId);
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
            TownPersistence.requireUpdated(account, "无法写入建镇初始资金");
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
        TownSnapshot town = TownPersistence.requireTown(connection, application.townId());
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

    private static void setTownText(PreparedStatement statement, int start,
                                    ApplicationText text) throws SQLException {
        statement.setString(start, text.name());
        statement.setString(start + 1, text.normalizedName());
        statement.setString(start + 2, text.shortName());
        statement.setString(start + 3, text.normalizedShortName());
        statement.setString(start + 4, text.description());
        statement.setString(start + 5, String.join(RULE_SEPARATOR, text.rules()));
    }

    private static String safeDetail(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.length() <= 2_000 ? detail : detail.substring(0, 2_000);
    }
}
